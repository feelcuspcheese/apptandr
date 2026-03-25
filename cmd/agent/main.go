package main

import (
    "agent/pkg/booker"
    "agent/pkg/config"
    "agent/pkg/httpclient"
    "agent/pkg/notifier"
    "agent/pkg/parser"
    "agent/pkg/scheduler"
    "agent/pkg/scraper"
    "agent/pkg/state"
    "context"
    "fmt"
    "os"
    "os/signal"
    "syscall"
    "time"

    "github.com/sirupsen/logrus"
)

func main() {
    logger := logrus.New()
    logger.SetFormatter(&logrus.TextFormatter{
        FullTimestamp: true,
    })
    logger.SetLevel(logrus.InfoLevel)

    cfg, err := config.LoadConfig("configs/config.yaml")
    if err != nil {
        logger.Fatalf("Failed to load config: %v", err)
    }

    // Build HTTP client with realistic headers
    headers := map[string]string{
        "User-Agent":      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept":          "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Accept-Encoding": "gzip, deflate, br",
        "Connection":      "keep-alive",
        "Referer":         cfg.Site.BaseURL,
    }
    client, err := httpclient.NewClient(headers, 30*time.Second)
    if err != nil {
        logger.Fatalf("Failed to create HTTP client: %v", err)
    }

    // Components
    stateManager := state.NewState()
    ntfy := notifier.NewNtfy(cfg.NtfyTopic)
    scraperInst := scraper.NewScraper(client, cfg.Site.AvailabilityEndpoint, cfg.RequestJitter, logger)

    // Task that returns (stop bool, error)
    task := func(ctx context.Context) (bool, error) {
        availabilities, err := scraperInst.FetchAvailability(ctx)
        if err != nil {
            logger.WithError(err).Error("Failed to fetch availability")
            return false, err
        }
        if len(availabilities) == 0 {
            logger.Info("No availabilities found")
            return false, nil
        }

        // Filter unseen
        newAvails := make([]parser.Availability, 0)
        for _, a := range availabilities {
            if !stateManager.IsSeen(a.Date) {
                newAvails = append(newAvails, a)
            }
        }
        if len(newAvails) == 0 {
            logger.Info("No new availabilities")
            return false, nil
        }

        if cfg.Mode == "alert" {
            // Send ntfy notification with buttons
            actions := notifier.PrioritizeActions(newAvails)
            err := ntfy.SendNotification(
                "Appointment Available!",
                "Found new appointment dates",
                notifier.PriorityHigh,
                actions,
            )
            if err != nil {
                logger.WithError(err).Error("Failed to send notification")
            } else {
                for _, a := range newAvails {
                    stateManager.MarkSeen(a.Date)
                }
            }
            return false, nil
        } else if cfg.Mode == "booking" {
            bookerInst := booker.NewBooker(client, cfg.Site, stateManager, logger)
            for _, prefDay := range cfg.PreferredDays {
                for _, a := range newAvails {
                    if isDayOfWeek(a.Date, prefDay) {
                        logger.WithField("date", a.Date).Info("Attempting to book preferred day")
                        if err := bookerInst.Book(ctx, a); err != nil {
                            logger.WithError(err).Error("Booking failed")
                            _ = ntfy.SendNotification(
                                "Booking Failed",
                                fmt.Sprintf("Failed to book %s: %v", a.Date, err),
                                notifier.PriorityHigh,
                                nil,
                            )
                            return false, nil
                        } else {
                            _ = ntfy.SendNotification(
                                "Booking Successful",
                                fmt.Sprintf("Successfully booked %s", a.Date),
                                notifier.PriorityUrgent,
                                nil,
                            )
                            stateManager.MarkSeen(a.Date)
                            return true, nil // stop further checks
                        }
                    }
                }
            }
            logger.Info("No preferred day found in new availabilities")
            return false, nil
        }
        return false, nil
    }

    // Pre‑warm task: make a warm‑up request to the base URL (or any endpoint) and if booking mode, log in.
    preWarmTask := func(ctx context.Context) error {
        logger.Info("Performing pre‑warm tasks")
        // Warm up connection with a request to the base URL
        req, err := http.NewRequestWithContext(ctx, "GET", cfg.Site.BaseURL, nil)
        if err != nil {
            return err
        }
        resp, err := client.Do(req)
        if err != nil {
            return fmt.Errorf("warm‑up request failed: %w", err)
        }
        defer resp.Body.Close()
        logger.WithField("status", resp.StatusCode).Info("Warm‑up request completed")

        // If booking mode, perform login pre‑warm
        if cfg.Mode == "booking" {
            bookerInst := booker.NewBooker(client, cfg.Site, stateManager, logger)
            if err := bookerInst.PreLogin(ctx); err != nil {
                return fmt.Errorf("pre‑login failed: %w", err)
            }
            logger.Info("Pre‑login completed")
        }
        return nil
    }

    sched, err := scheduler.NewScheduler(
        cfg.StrikeTime,
        cfg.RequestJitter,
        cfg.CheckWindow,
        cfg.CheckInterval,
        cfg.PreWarmOffset,
        logger,
        task,
        preWarmTask,
    )
    if err != nil {
        logger.Fatalf("Failed to create scheduler: %v", err)
    }

    ctx, cancel := context.WithCancel(context.Background())
    defer cancel()

    // Handle shutdown
    sigChan := make(chan os.Signal, 1)
    signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
    go func() {
        <-sigChan
        logger.Info("Shutting down...")
        cancel()
    }()

    sched.Run(ctx)
}

func isDayOfWeek(dateStr, dayName string) bool {
    t, err := time.Parse("2006-01-02", dateStr)
    if err != nil {
        return false
    }
    return t.Weekday().String() == dayName
}

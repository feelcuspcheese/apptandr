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
        "User-Agent":      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
        "Accept":          "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language": "en-US,en;q=0.9",
        "Accept-Encoding": "gzip, deflate, br, zstd",
        "Connection":      "keep-alive",
        "Referer":         cfg.Site.BaseURL,
        "X-Requested-With": "XMLHttpRequest", // for AJAX endpoint
    }
    client, err := httpclient.NewClient(headers, 30*time.Second)
    if err != nil {
        logger.Fatalf("Failed to create HTTP client: %v", err)
    }

    // Components
    stateManager := state.NewState()
    ntfy := notifier.NewNtfy(cfg.NtfyTopic)

    // Build availability endpoint URL with static parameters
    availEndpoint := fmt.Sprintf("%s/pass/availability/institution?museum=%s&date=%s&digital=%t&physical=%t&location=%s",
        cfg.Site.BaseURL,
        cfg.Site.MuseumID,
        "{date}", // we'll replace date later
        cfg.Site.Digital,
        cfg.Site.Physical,
        cfg.Site.Location,
    )

    // We'll create a scraper that can take a date parameter
    scraperInst := scraper.NewScraper(client, availEndpoint, cfg.RequestJitter, logger)

    // Task that returns (stop bool, error)
    task := func(ctx context.Context) (bool, error) {
        // Get current date (or we could loop through a range? For simplicity, we'll check today and maybe next few days)
        // The actual drop time is for a specific date. We'll configure the date to check in the config.
        // For now, we'll assume we check for a specific date, which we can get from config.
        // We'll add a config field "target_date" maybe. For simplicity, we'll check today and tomorrow?
        // The user will need to set the date in the config.
        // We'll use a config field "target_date" in the site config.
        targetDate := cfg.Site.TargetDate // we need to add this field
        if targetDate == "" {
            // fallback: today
            targetDate = time.Now().Format("2006-01-02")
        }

        // Replace {date} in endpoint
        endpoint := strings.Replace(availEndpoint, "{date}", targetDate, 1)
        scraperInst.endpoint = endpoint // temporarily modify
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
                    // Extract full date from booking URL
                    fullDate, err := extractDateFromURL(a.BookingURL)
                    if err != nil {
                        logger.WithError(err).Warn("Failed to extract date from URL, skipping")
                        continue
                    }
                    if isDayOfWeek(fullDate, prefDay) {
                        logger.WithField("date", fullDate).Info("Attempting to book preferred day")
                        if err := bookerInst.Book(ctx, a); err != nil {
                            logger.WithError(err).Error("Booking failed")
                            _ = ntfy.SendNotification(
                                "Booking Failed",
                                fmt.Sprintf("Failed to book %s: %v", fullDate, err),
                                notifier.PriorityHigh,
                                nil,
                            )
                            return false, nil
                        } else {
                            _ = ntfy.SendNotification(
                                "Booking Successful",
                                fmt.Sprintf("Successfully booked %s", fullDate),
                                notifier.PriorityUrgent,
                                nil,
                            )
                            stateManager.MarkSeen(fullDate)
                            return true, nil
                        }
                    }
                }
            }
            logger.Info("No preferred day found in new availabilities")
            return false, nil
        }
        return false, nil
    }

    // Pre-warm task: we might not need it for now, but we keep a placeholder
    preWarmTask := func(ctx context.Context) error {
        logger.Info("Pre‑warm: making a request to base URL")
        req, err := http.NewRequestWithContext(ctx, "GET", cfg.Site.BaseURL, nil)
        if err != nil {
            return err
        }
        resp, err := client.Do(req)
        if err != nil {
            return fmt.Errorf("pre‑warm request failed: %w", err)
        }
        defer resp.Body.Close()
        logger.WithField("status", resp.StatusCode).Info("Pre‑warm completed")
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

func extractDateFromURL(rawURL string) (string, error) {
    u, err := url.Parse(rawURL)
    if err != nil {
        return "", err
    }
    q := u.Query()
    date := q.Get("date")
    if date == "" {
        return "", fmt.Errorf("no date parameter in URL")
    }
    return date, nil
}

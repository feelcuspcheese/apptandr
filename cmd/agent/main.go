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
    "math/rand"
    "net/url"
    "os"
    "os/signal"
    "strings"
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

    // Select the active site
    activeSite, ok := cfg.Sites[cfg.PreferredSlug]
    if !ok {
        // If not found, pick the first site
        for _, s := range cfg.Sites {
            activeSite = s
            break
        }
        if activeSite.Slug == "" {
            logger.Fatal("No sites configured and no preferred slug set")
        }
        logger.WithField("slug", activeSite.Slug).Warn("Preferred slug not found, using first site")
    }

    logger.WithFields(logrus.Fields{
        "slug": activeSite.Slug,
        "mode": cfg.Mode,
    }).Info("Using active site")

    // HTTP client with realistic headers (AJAX endpoint requires X-Requested-With)
    headers := map[string]string{
        "User-Agent":      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
        "Accept":          "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language": "en-US,en;q=0.9",
        "Accept-Encoding": "gzip, deflate, br, zstd",
        "Connection":      "keep-alive",
        "Referer":         activeSite.BaseURL + "/passes/" + activeSite.Slug,
        "X-Requested-With": "XMLHttpRequest", // required for AJAX endpoint
    }
    client, err := httpclient.NewClient(headers, 30*time.Second)
    if err != nil {
        logger.Fatalf("Failed to create HTTP client: %v", err)
    }

    // Components
    stateManager := state.NewState()
    ntfy := notifier.NewNtfy(cfg.NtfyTopic)

    // Build endpoint template
    availEndpointTemplate := fmt.Sprintf("%s%s?museum=%s&date={date}&digital=%t&physical=%t&location=%s",
        activeSite.BaseURL,
        activeSite.AvailabilityEndpoint,
        activeSite.MuseumID,
        activeSite.Digital,
        activeSite.Physical,
        activeSite.Location,
    )

    scraperInst := scraper.NewScraper(client, availEndpointTemplate, cfg.RequestJitter, logger)

    // Task that returns (stop bool, error)
    task := func(ctx context.Context) (bool, error) {
        // Determine months to fetch
        now := time.Now()
        months := cfg.MonthsToCheck
        if months < 1 {
            months = 1
        }

        allAvailabilities := make([]parser.Availability, 0)
        for i := 0; i < months; i++ {
            // Compute date for this month (first day of month)
            date := now.AddDate(0, i, 0)
            // Use the first day of month as representative for the calendar fetch
            targetDate := date.Format("2006-01-02")
            logger.WithField("date", targetDate).Debug("Fetching availability for month")

            availabilities, err := scraperInst.FetchForDate(ctx, targetDate)
            if err != nil {
                logger.WithError(err).WithField("date", targetDate).Error("Failed to fetch availability")
                // Continue to next month? Maybe skip, but continue.
                continue
            }
            allAvailabilities = append(allAvailabilities, availabilities...)

            // Add jitter between month fetches (but not after last)
            if i < months-1 && cfg.RequestJitter > 0 {
                jitter := time.Duration(rand.Int63n(int64(cfg.RequestJitter)))
                logger.WithField("jitter", jitter).Debug("Applying jitter between month fetches")
                time.Sleep(jitter)
            }
        }

        logger.WithField("total", len(allAvailabilities)).Info("Availability fetched")

        if len(allAvailabilities) == 0 {
            logger.Info("No availabilities found")
            return false, nil
        }

        // Filter unseen and extract full dates
        newAvails := make([]parser.Availability, 0)
        for _, a := range allAvailabilities {
            fullDate, err := extractDateFromURL(a.BookingURL)
            if err != nil {
                logger.WithError(err).Warn("Failed to extract date from URL, skipping")
                continue
            }
            if !stateManager.IsSeen(fullDate) {
                a.Date = fullDate
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
            bookerInst := booker.NewBooker(client, activeSite, stateManager, logger)
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

    // Pre-warm task: warm up connection with a base request
    preWarmTask := func(ctx context.Context) error {
        logger.Info("Pre‑warm: making a request to base URL")
        req, err := http.NewRequestWithContext(ctx, "GET", activeSite.BaseURL, nil)
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

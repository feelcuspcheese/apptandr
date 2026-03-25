// Inside main, after creating scraperInst:
// Build endpoint template with {date} placeholder
availEndpointTemplate := fmt.Sprintf("%s%s?museum=%s&date={date}&digital=%t&physical=%t&location=%s",
    cfg.Site.BaseURL,
    cfg.Site.AvailabilityEndpoint,
    cfg.Site.MuseumID,
    cfg.Site.Digital,
    cfg.Site.Physical,
    cfg.Site.Location,
)

scraperInst := scraper.NewScraper(client, availEndpointTemplate, cfg.RequestJitter, logger)

// Task that returns (stop bool, error)
task := func(ctx context.Context) (bool, error) {
    targetDate := cfg.Site.TargetDate
    if targetDate == "" {
        targetDate = time.Now().Format("2006-01-02")
    }

    // Parse target date to compute next month
    t, err := time.Parse("2006-01-02", targetDate)
    if err != nil {
        logger.WithError(err).Warn("Invalid target_date, using today")
        t = time.Now()
    }
    nextMonth := t.AddDate(0, 1, 0)
    nextMonthDate := nextMonth.Format("2006-01-02")

    // Fetch current month
    availabilities1, err := scraperInst.FetchForDate(ctx, targetDate)
    if err != nil {
        logger.WithError(err).Error("Failed to fetch availability for target month")
        return false, err
    }

    // Apply jitter between requests
    if cfg.RequestJitter > 0 {
        jitter := time.Duration(rand.Int63n(int64(cfg.RequestJitter)))
        logger.WithField("jitter", jitter).Debug("Applying jitter between month fetches")
        time.Sleep(jitter)
    }

    // Fetch next month
    availabilities2, err := scraperInst.FetchForDate(ctx, nextMonthDate)
    if err != nil {
        logger.WithError(err).Error("Failed to fetch availability for next month")
        return false, err
    }

    // Combine results
    allAvailabilities := append(availabilities1, availabilities2...)
    logger.WithFields(logrus.Fields{
        "current_month": len(availabilities1),
        "next_month":    len(availabilities2),
        "total":         len(allAvailabilities),
    }).Info("Availability fetched")

    if len(allAvailabilities) == 0 {
        logger.Info("No availabilities found")
        return false, nil
    }

    // Filter unseen
    newAvails := make([]parser.Availability, 0)
    for _, a := range allAvailabilities {
        // Extract full date from booking URL
        fullDate, err := extractDateFromURL(a.BookingURL)
        if err != nil {
            logger.WithError(err).Warn("Failed to extract date from URL, skipping")
            continue
        }
        if !stateManager.IsSeen(fullDate) {
            // Store the full date for later use
            a.Date = fullDate // Override the day-number with full date
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

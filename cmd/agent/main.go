package main

import (
    "agent/pkg/agent"
    "agent/pkg/config"
    "agent/pkg/web"
    "flag"
    "os"
    "os/signal"
    "syscall"
    "time"

    "github.com/google/uuid"
    "github.com/sirupsen/logrus"
)

func main() {
    webMode := flag.Bool("web", false, "Run as web server with dashboard")
    configPath := flag.String("config", "configs/config.yaml", "Path to config file")
    flag.Parse()

    logger := logrus.New()
    logger.SetFormatter(&logrus.TextFormatter{
        FullTimestamp:   true,
        TimestampFormat: "2006-01-02T15:04:05.000000Z07:00", // microseconds
    })
    logger.SetLevel(logrus.InfoLevel)

    cfg, err := config.LoadConfig(*configPath)
    if err != nil {
        logger.Fatalf("Failed to load config: %v", err)
    }

    if *webMode {
        server := web.NewServer(cfg, logger)
        logger.Info("Starting web server on :8080")
        if err := server.Run(":8080"); err != nil {
            logger.Fatal(err)
        }
    } else {
        logger.Info("Starting in standalone mode")

        // Compute the strike time (today or tomorrow)
        now := time.Now()
        strikeTimeStr := cfg.StrikeTime
        t, err := time.Parse("15:04:05", strikeTimeStr)
        if err != nil {
            logger.Fatalf("Invalid strike time format: %v", err)
        }
        strike := time.Date(now.Year(), now.Month(), now.Day(), t.Hour(), t.Minute(), t.Second(), 0, time.Local)
        if strike.Before(now) {
            strike = strike.Add(24 * time.Hour)
        }

        // Get the active site and preferred museum
        siteKey := cfg.ActiveSite
        site, ok := cfg.Sites[siteKey]
        if !ok {
            logger.Fatalf("Active site %s not found", siteKey)
        }
        museumSlug := site.PreferredSlug
        if museumSlug == "" {
            // fallback to first museum
            for slug := range site.Museums {
                museumSlug = slug
                break
            }
        }
        if museumSlug == "" {
            logger.Fatal("No museum selected")
        }

        // Create a scheduled run
        runID := uuid.New().String()
        run := config.ScheduledRun{
            ID:         runID,
            SiteKey:    siteKey,
            MuseumSlug: museumSlug,
            DropTime:   strike,
            Mode:       cfg.Mode,
        }
        cfg.ScheduledRuns = []config.ScheduledRun{run}
        if err := config.SaveConfig(*configPath, cfg); err != nil {
            logger.Fatalf("Failed to save config: %v", err)
        }

        // Start the agent
        ag := agent.NewAgent(cfg, logger)
        if err := ag.Start(runID); err != nil {
            logger.Fatalf("Agent start failed: %v", err)
        }

        // Handle signals to allow graceful stop
        sigChan := make(chan os.Signal, 1)
        signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
        go func() {
            <-sigChan
            logger.Info("Shutting down...")
            ag.Stop()
            os.Exit(0)
        }()

        // Wait for the agent to finish
        for {
            if !ag.IsRunning() {
                break
            }
            time.Sleep(1 * time.Second)
        }
        logger.Info("Agent finished")
    }
}

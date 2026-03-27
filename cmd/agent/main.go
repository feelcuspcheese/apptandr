package main

import (
    "agent/pkg/agent"
    "agent/pkg/config"
    "context"
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
        FullTimestamp: true,
    })
    logger.SetLevel(logrus.InfoLevel)

    cfg, err := config.LoadConfig(*configPath)
    if err != nil {
        logger.Fatalf("Failed to load config: %v", err)
    }

    if *webMode {
        // Start web server
        server := web.NewServer(cfg, logger)
        logger.Info("Starting web server on :8080")
        if err := server.Run(":8080"); err != nil {
            logger.Fatal(err)
        }
    } else {
        // Standalone mode: create a single run using the current config
        logger.Info("Starting in standalone mode")

        // Use the active site and its preferred museum
        siteKey := cfg.ActiveSite
        site, ok := cfg.Sites[siteKey]
        if !ok {
            logger.Fatalf("Active site %s not found", siteKey)
        }
        museumSlug := site.PreferredSlug
        if museumSlug == "" {
            // Fallback to first museum
            for slug := range site.Museums {
                museumSlug = slug
                break
            }
        }
        if museumSlug == "" {
            logger.Fatal("No museum selected")
        }

        // Compute drop time from strike time (today or tomorrow)
        now := time.Now()
        strikeTimeStr := cfg.StrikeTime
        t, err := time.Parse("15:04:05", strikeTimeStr)
        if err != nil {
            logger.Fatalf("Invalid strike time format: %v", err)
        }
        dropTime := time.Date(now.Year(), now.Month(), now.Day(), t.Hour(), t.Minute(), t.Second(), 0, time.Local)
        if dropTime.Before(now) {
            dropTime = dropTime.Add(24 * time.Hour)
        }

        // Create a scheduled run (temporary)
        runID := uuid.New().String()
        run := config.ScheduledRun{
            ID:         runID,
            SiteKey:    siteKey,
            MuseumSlug: museumSlug,
            DropTime:   dropTime,
            Mode:       cfg.Mode,
        }

        // Save the run to config (so the agent can find it)
        cfg.ScheduledRuns = []config.ScheduledRun{run}
        if err := config.SaveConfig(*configPath, cfg); err != nil {
            logger.Fatalf("Failed to save run: %v", err)
        }

        // Create agent and start
        ag := agent.NewAgent(cfg, logger)
        ctx, cancel := context.WithCancel(context.Background())
        defer cancel()

        // Handle signals
        sigChan := make(chan os.Signal, 1)
        signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
        go func() {
            <-sigChan
            logger.Info("Shutting down...")
            cancel()
        }()

        logger.Infof("Starting agent for run %s, drop time: %v", runID, dropTime)
        if err := ag.Start(runID); err != nil {
            logger.Fatalf("Agent start failed: %v", err)
        }
    }
}

package main

import (
    "agent/pkg/agent"
    "agent/pkg/config"
    "agent/pkg/web"
    "context"
    "flag"
    "os"
    "os/signal"
    "syscall"
    "time"

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
        // Standalone mode: run as original scheduler
        logger.Info("Starting in standalone mode")

        // Context to stop the agent gracefully
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

        // Main loop: run every day at strike time
        for {
            // Compute next strike time (today or tomorrow)
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

            // Wait until strike time
            wait := strike.Sub(now)
            if wait > 0 {
                logger.WithField("wait", wait).Info("Waiting for strike time")
                select {
                case <-time.After(wait):
                case <-ctx.Done():
                    return
                }
            }

            // Run the agent for this day (blocking)
            logger.Info("Starting agent for the day")
            ag := agent.NewAgent(cfg, logger)
            if err := ag.Start(strike); err != nil {
                logger.WithError(err).Error("Agent start failed")
            }

            // If context was cancelled, exit
            select {
            case <-ctx.Done():
                return
            default:
            }

            // The loop will continue to the next day automatically
        }
    }
}

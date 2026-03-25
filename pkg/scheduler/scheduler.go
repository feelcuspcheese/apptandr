package scheduler

import (
    "context"
    "math/rand"
    "time"

    "github.com/sirupsen/logrus"
)

type Scheduler struct {
    strikeTime    time.Time
    jitter        time.Duration
    checkWindow   time.Duration
    checkInterval time.Duration
    logger        *logrus.Logger
    task          func(context.Context) (bool, error) // returns stop flag and error
}

func NewScheduler(strikeTimeStr string, jitter, checkWindow, checkInterval time.Duration, logger *logrus.Logger, task func(context.Context) (bool, error)) (*Scheduler, error) {
    t, err := time.Parse("15:04:05", strikeTimeStr)
    if err != nil {
        return nil, err
    }
    now := time.Now()
    strike := time.Date(now.Year(), now.Month(), now.Day(), t.Hour(), t.Minute(), t.Second(), 0, now.Location())
    if strike.Before(now) {
        strike = strike.Add(24 * time.Hour)
    }
    return &Scheduler{
        strikeTime:    strike,
        jitter:        jitter,
        checkWindow:   checkWindow,
        checkInterval: checkInterval,
        logger:        logger,
        task:          task,
    }, nil
}

func (s *Scheduler) Run(ctx context.Context) {
    for {
        // Wait until strike time
        wait := s.strikeTime.Sub(time.Now())
        if wait > 0 {
            s.logger.WithField("wait", wait).Info("Waiting for strike time")
            select {
            case <-time.After(wait):
            case <-ctx.Done():
                return
            }
        }

        // Start checking window
        deadline := time.Now().Add(s.checkWindow)
        s.logger.WithFields(logrus.Fields{
            "window":   s.checkWindow,
            "deadline": deadline,
        }).Info("Starting check window")

        for {
            // Apply jitter before each check (including the first)
            if s.jitter > 0 {
                jitter := time.Duration(rand.Int63n(int64(s.jitter)))
                s.logger.WithField("jitter", jitter).Debug("Applying jitter")
                select {
                case <-time.After(jitter):
                case <-ctx.Done():
                    return
                }
            }

            // Execute the task
            stop, err := s.task(ctx)
            if err != nil {
                s.logger.WithError(err).Error("Task failed")
            }
            if stop {
                s.logger.Info("Task requested stop; ending check window")
                break
            }

            // Check if window expired
            if time.Now().After(deadline) {
                s.logger.Info("Check window expired")
                break
            }

            // Wait for next interval (with jitter)
            interval := s.checkInterval
            if s.jitter > 0 {
                // Add random jitter to interval to mimic human variation
                jitter := time.Duration(rand.Int63n(int64(s.jitter)))
                interval += jitter
            }
            s.logger.WithField("next_in", interval).Debug("Waiting before next check")
            select {
            case <-time.After(interval):
            case <-ctx.Done():
                return
            }
        }

        // Schedule next strike (tomorrow)
        s.strikeTime = s.strikeTime.Add(24 * time.Hour)
    }
}

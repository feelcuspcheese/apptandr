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
    preWarmOffset time.Duration
    logger        *logrus.Logger
    task          func(context.Context) (bool, error) // returns stop flag and error
    preWarmTask   func(context.Context) error
}

func NewScheduler(
    strikeTimeStr string,
    jitter, checkWindow, checkInterval, preWarmOffset time.Duration,
    logger *logrus.Logger,
    task func(context.Context) (bool, error),
    preWarmTask func(context.Context) error,
) (*Scheduler, error) {
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
        preWarmOffset: preWarmOffset,
        logger:        logger,
        task:          task,
        preWarmTask:   preWarmTask,
    }, nil
}

// PreWarm runs the pre‑warm task once, before the first waiting cycle.
func (s *Scheduler) PreWarm(ctx context.Context) error {
    if s.preWarmTask == nil {
        return nil
    }
    s.logger.Info("Starting pre‑warm phase")
    // Apply jitter before pre‑warm to mimic human browsing
    if s.jitter > 0 {
        jitter := time.Duration(rand.Int63n(int64(s.jitter)))
        s.logger.WithField("jitter", jitter).Debug("Applying jitter before pre‑warm")
        select {
        case <-time.After(jitter):
        case <-ctx.Done():
            return ctx.Err()
        }
    }
    return s.preWarmTask(ctx)
}

func (s *Scheduler) Run(ctx context.Context) {
    for {
        // Wait until strike time minus pre‑warm offset (but only if pre‑warm is needed again tomorrow)
        // Actually, we already did pre‑warm once before this loop. For subsequent days, we should re‑warm.
        // We'll handle re‑warm each day before the strike.
        // So we need to recalc the strike time for the next day after each cycle.

        // Compute next strike time (today's strike if not passed, else tomorrow)
        now := time.Now()
        if s.strikeTime.Before(now) {
            s.strikeTime = s.strikeTime.Add(24 * time.Hour)
        }

        // Pre‑warm for this day
        warmTime := s.strikeTime.Add(-s.preWarmOffset)
        if warmTime.After(now) {
            wait := warmTime.Sub(now)
            s.logger.WithFields(logrus.Fields{
                "warm_time": warmTime,
                "wait":      wait,
            }).Info("Waiting for pre‑warm time")
            select {
            case <-time.After(wait):
            case <-ctx.Done():
                return
            }
        }
        // Execute pre‑warm task
        if err := s.PreWarm(ctx); err != nil {
            s.logger.WithError(err).Error("Pre‑warm failed")
        }

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
            // Apply jitter before each check
            if s.jitter > 0 {
                jitter := time.Duration(rand.Int63n(int64(s.jitter)))
                s.logger.WithField("jitter", jitter).Debug("Applying jitter before check")
                select {
                case <-time.After(jitter):
                case <-ctx.Done():
                    return
                }
            }

            // Execute task
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

        // Move to next day (tomorrow's strike)
        s.strikeTime = s.strikeTime.Add(24 * time.Hour)
    }
}

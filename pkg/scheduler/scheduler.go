package scheduler

import (
    "context"
    "time"
    "github.com/sirupsen/logrus"
)

type Scheduler struct {
    strikeTime time.Time
    jitter     time.Duration
    logger     *logrus.Logger
    task       func(context.Context) error
}

func NewScheduler(strikeTimeStr string, jitter time.Duration, logger *logrus.Logger, task func(context.Context) error) (*Scheduler, error) {
    // Parse strike time (format "HH:MM:SS")
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
        strikeTime: strike,
        jitter:     jitter,
        logger:     logger,
        task:       task,
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

        // Apply jitter (random delay before executing)
        if s.jitter > 0 {
            jitter := time.Duration(rand.Int63n(int64(s.jitter)))
            s.logger.WithField("jitter", jitter).Debug("Applying jitter")
            select {
            case <-time.After(jitter):
            case <-ctx.Done():
                return
            }
        }

        s.logger.Info("Executing task")
        if err := s.task(ctx); err != nil {
            s.logger.WithError(err).Error("Task failed")
        }

        // Schedule next strike (tomorrow)
        s.strikeTime = s.strikeTime.Add(24 * time.Hour)
    }
}

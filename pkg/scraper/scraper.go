package scraper

import (
    "agent/pkg/httpclient"
    "agent/pkg/parser"
    "context"
    "fmt"
    "math/rand"
    "net/http"
    "strings"
    "time"

    "github.com/sirupsen/logrus"
)

type Scraper struct {
    client           *httpclient.Client
    endpointTemplate string // e.g., "...?date={date}..."
    logger           *logrus.Logger
    jitter           time.Duration
}

// NewScraper creates a scraper that uses a template with {date} placeholder.
func NewScraper(client *httpclient.Client, endpointTemplate string, jitter time.Duration, logger *logrus.Logger) *Scraper {
    return &Scraper{
        client:           client,
        endpointTemplate: endpointTemplate,
        logger:           logger,
        jitter:           jitter,
    }
}

// FetchForDate fetches availability for a specific date (month).
func (s *Scraper) FetchForDate(ctx context.Context, date string) ([]parser.Availability, error) {
    // Build endpoint by replacing {date}
    endpoint := strings.Replace(s.endpointTemplate, "{date}", date, 1)

    // Apply jitter before request
    if s.jitter > 0 {
        jitter := time.Duration(rand.Int63n(int64(s.jitter)))
        s.logger.WithField("jitter", jitter).Debug("Applying jitter before request")
        time.Sleep(jitter)
    }

    req, err := http.NewRequestWithContext(ctx, "GET", endpoint, nil)
    if err != nil {
        return nil, err
    }
    resp, err := s.client.Do(req)
    if err != nil {
        return nil, err
    }
    defer resp.Body.Close()

    if resp.StatusCode != http.StatusOK {
        return nil, fmt.Errorf("unexpected status: %d", resp.StatusCode)
    }

    return parser.ParseAvailability(resp.Body, s.logger)
}

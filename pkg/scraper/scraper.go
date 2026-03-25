package scraper

import (
    "agent/pkg/httpclient"
    "agent/pkg/parser"
    "context"
    "fmt"
    "github.com/sirupsen/logrus"
    "net/http"
    "time"
)

type Scraper struct {
    client   *httpclient.Client
    endpoint string
    logger   *logrus.Logger
    jitter   time.Duration
}

func NewScraper(client *httpclient.Client, endpoint string, jitter time.Duration, logger *logrus.Logger) *Scraper {
    return &Scraper{
        client:   client,
        endpoint: endpoint,
        logger:   logger,
        jitter:   jitter,
    }
}

func (s *Scraper) FetchAvailability(ctx context.Context) ([]parser.Availability, error) {
    // Apply jitter before request
    if s.jitter > 0 {
        jitter := time.Duration(rand.Int63n(int64(s.jitter)))
        time.Sleep(jitter)
    }

    req, err := http.NewRequestWithContext(ctx, "GET", s.endpoint, nil)
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

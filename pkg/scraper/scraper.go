package scraper

import (
    "agent/pkg/httpclient"
    "agent/pkg/parser"
    "bytes"
    "compress/gzip"
    "context"
    "fmt"
    "io"
    "math/rand"
    "net/http"
    "strings"
    "time"

    "github.com/sirupsen/logrus"
)

type Scraper struct {
    client           *httpclient.Client
    endpointTemplate string
    logger           *logrus.Logger
    jitter           time.Duration
}

func NewScraper(client *httpclient.Client, endpointTemplate string, jitter time.Duration, logger *logrus.Logger) *Scraper {
    return &Scraper{
        client:           client,
        endpointTemplate: endpointTemplate,
        logger:           logger,
        jitter:           jitter,
    }
}

// FetchForDate returns parsed availabilities for a given date.
func (s *Scraper) FetchForDate(ctx context.Context, date string) ([]parser.Availability, error) {
    avails, _, err := s.FetchForDateWithBody(ctx, date)
    return avails, err
}

// FetchForDateWithBody returns both the parsed availabilities and the raw response body.
func (s *Scraper) FetchForDateWithBody(ctx context.Context, date string) ([]parser.Availability, string, error) {
    endpoint := strings.Replace(s.endpointTemplate, "{date}", date, 1)

    if s.jitter > 0 {
        jitter := time.Duration(rand.Int63n(int64(s.jitter)))
        s.logger.WithField("jitter", jitter).Debug("Applying jitter before request")
        time.Sleep(jitter)
    }

    req, err := http.NewRequestWithContext(ctx, "GET", endpoint, nil)
    if err != nil {
        return nil, "", err
    }
    resp, err := s.client.Do(req)
    if err != nil {
        return nil, "", err
    }
    defer resp.Body.Close()

    // Read raw body
    rawBody, err := io.ReadAll(resp.Body)
    if err != nil {
        return nil, "", err
    }

    // Check if body is gzipped (starts with \x1f\x8b)
    var bodyBytes []byte
    if len(rawBody) >= 2 && rawBody[0] == 0x1f && rawBody[1] == 0x8b {
        // Decompress
        gzReader, err := gzip.NewReader(bytes.NewReader(rawBody))
        if err != nil {
            return nil, "", fmt.Errorf("failed to create gzip reader: %w", err)
        }
        decompressed, err := io.ReadAll(gzReader)
        gzReader.Close()
        if err != nil {
            return nil, "", fmt.Errorf("failed to decompress gzip: %w", err)
        }
        bodyBytes = decompressed
    } else {
        bodyBytes = rawBody
    }

    body := string(bodyBytes)

    if resp.StatusCode != http.StatusOK {
        return nil, body, fmt.Errorf("unexpected status: %d", resp.StatusCode)
    }

    avails, err := parser.ParseAvailabilityFromString(body, s.logger)
    return avails, body, err
}

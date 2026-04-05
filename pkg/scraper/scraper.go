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
	return &Scraper{client, endpointTemplate, logger, jitter}
}

func (s *Scraper) FetchForDateWithBody(ctx context.Context, date string) ([]parser.Availability, string, error) {
	endpoint := strings.Replace(s.endpointTemplate, "{date}", date, 1)

	if s.jitter > 0 {
		jitterVal := time.Duration(rand.Int63n(int64(s.jitter)))
		select {
		case <-time.After(jitterVal):
		case <-ctx.Done():
			return nil, "", ctx.Err()
		}
	}

	s.logger.WithField("url", endpoint).Info("Scraper: Checking Availability (AJAX)")

	req, err := http.NewRequestWithContext(ctx, "GET", endpoint, nil)
	if err != nil { return nil, "", err }

	req.Header.Set("X-Requested-With", "XMLHttpRequest")

	resp, err := s.client.Do(req)
	if err != nil { return nil, "", err }
	defer resp.Body.Close()

	rawBody, _ := io.ReadAll(resp.Body)
	
	// Manual decompression handles the identity-specific encoding
	var bodyBytes []byte
	if len(rawBody) >= 2 && rawBody[0] == 0x1f && rawBody[1] == 0x8b {
		gzReader, err := gzip.NewReader(bytes.NewReader(rawBody))
		if err == nil {
			bodyBytes, _ = io.ReadAll(gzReader)
			gzReader.Close()
		} else {
			bodyBytes = rawBody
		}
	} else {
		bodyBytes = rawBody
	}

	body := string(bodyBytes)
	if resp.StatusCode != http.StatusOK {
		return nil, body, fmt.Errorf("unexpected status %d", resp.StatusCode)
	}

	avails, err := parser.ParseAvailabilityFromString(body, s.logger)
	return avails, body, err
}

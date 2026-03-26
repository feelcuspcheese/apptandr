package parser

import (
    "io"
    "strings"
    "github.com/PuerkitoBio/goquery"
    "github.com/sirupsen/logrus"
)

type Availability struct {
    Date       string
    BookingURL string
}

func ParseAvailability(r io.Reader, logger *logrus.Logger) ([]Availability, error) {
    doc, err := goquery.NewDocumentFromReader(r)
    if err != nil {
        return nil, err
    }
    return parseFromDoc(doc, logger)
}

func ParseAvailabilityFromString(html string, logger *logrus.Logger) ([]Availability, error) {
    doc, err := goquery.NewDocumentFromReader(strings.NewReader(html))
    if err != nil {
        return nil, err
    }
    return parseFromDoc(doc, logger)
}

func parseFromDoc(doc *goquery.Document, logger *logrus.Logger) ([]Availability, error) {
    var availabilities []Availability
    doc.Find("a.s-lc-pass-availability.s-lc-pass-digital.s-lc-pass-available").Each(func(i int, s *goquery.Selection) {
        dateText := strings.TrimSpace(s.Text())
        href, exists := s.Attr("href")
        if exists && dateText != "" {
            availabilities = append(availabilities, Availability{
                Date:       dateText,
                BookingURL: href,
            })
        }
    })
    logger.WithField("count", len(availabilities)).Info("Parsed availabilities")
    return availabilities, nil
}

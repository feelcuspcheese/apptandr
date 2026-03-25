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

    var availabilities []Availability
    // Find all <a> elements with class "s-lc-pass-availability s-lc-pass-digital s-lc-pass-available"
    doc.Find("a.s-lc-pass-availability.s-lc-pass-digital.s-lc-pass-available").Each(func(i int, s *goquery.Selection) {
        // The date is the text inside the <a> tag (the day number)
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

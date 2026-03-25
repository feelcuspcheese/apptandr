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
            // The href is relative, e.g., "/passes/SAM/book?date=2026-04-24&pass=63ea409f9fab&digital=1&physical=0&location=0"
            // We'll store the full URL later, but we can keep it as is and later combine with base URL.
            availabilities = append(availabilities, Availability{
                Date:       dateText, // Just the day number; we might need full date from the URL? Actually the date is in the URL query param.
                BookingURL: href,
            })
        }
    })
    // Alternatively, we could extract the date from the URL if needed. For now, we use the text as date.
    logger.WithField("count", len(availabilities)).Info("Parsed availabilities")
    return availabilities, nil
}

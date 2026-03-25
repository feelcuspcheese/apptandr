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
    // Example: find table rows with date and link
    doc.Find("tr.available").Each(func(i int, s *goquery.Selection) {
        date := s.Find(".date").Text()
        link, exists := s.Find("a.book-link").Attr("href")
        if exists && date != "" {
            availabilities = append(availabilities, Availability{
                Date:       strings.TrimSpace(date),
                BookingURL: link,
            })
        }
    })
    logger.WithField("count", len(availabilities)).Info("Parsed availabilities")
    return availabilities, nil
}

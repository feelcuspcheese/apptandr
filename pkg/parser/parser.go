package parser

import (
    "io"
    "regexp"
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
    // First, try the specific selector used by the browser
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

    // If none found, try a more generic selector (just available)
    if len(availabilities) == 0 {
        doc.Find("a.s-lc-pass-availability.s-lc-pass-available").Each(func(i int, s *goquery.Selection) {
            dateText := strings.TrimSpace(s.Text())
            href, exists := s.Attr("href")
            if exists && dateText != "" {
                availabilities = append(availabilities, Availability{
                    Date:       dateText,
                    BookingURL: href,
                })
            }
        })
    }

    // If still none, use regex to find any <a> with href containing "/book"
    if len(availabilities) == 0 {
        html, err := doc.Html()
        if err == nil {
            // regex to find <a href=".../book...">...</a>
            re := regexp.MustCompile(`<a\s+[^>]*href="([^"]*\/book[^"]*)"[^>]*>(\d+)</a>`)
            matches := re.FindAllStringSubmatch(html, -1)
            for _, match := range matches {
                if len(match) >= 3 {
                    href := match[1]
                    dateText := match[2]
                    availabilities = append(availabilities, Availability{
                        Date:       dateText,
                        BookingURL: href,
                    })
                }
            }
        }
    }

    // If still none, look for any <a> with href containing "book" (debug)
    if len(availabilities) == 0 {
        var bookLinks []string
        doc.Find("a[href*='book']").Each(func(i int, s *goquery.Selection) {
            href, _ := s.Attr("href")
            if href != "" {
                bookLinks = append(bookLinks, href)
            }
        })
        if len(bookLinks) > 0 {
            logger.WithField("book_links", bookLinks).Warn("Found book links but no available class")
        }
    }

    logger.WithField("count", len(availabilities)).Info("Parsed availabilities")
    return availabilities, nil
}

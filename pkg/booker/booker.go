package booker

import (
	"agent/pkg/config"
	"agent/pkg/httpclient"
	"agent/pkg/parser"
	"agent/pkg/state"
	"bytes"
	"compress/gzip"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/PuerkitoBio/goquery"
	"github.com/sirupsen/logrus"
)

type Booker struct {
	client     *httpclient.Client
	siteConfig config.Site
	museum     config.Museum
	state      *state.State
	logger     *logrus.Logger
}

func NewBooker(client *httpclient.Client, site config.Site, museum config.Museum, state *state.State, logger *logrus.Logger) *Booker {
	return &Booker{
		client:     client,
		siteConfig: site,
		museum:     museum,
		state:      state,
		logger:     logger,
	}
}

/**
 * decompressBody ensures that the Booker can read optimized server responses.
 * Since we manually set "Accept-Encoding: gzip" in client.go for mimicry,
 * Go's automatic decompression is disabled. We must handle it here.
 */
func decompressBody(body []byte) ([]byte, error) {
	if len(body) >= 2 && body[0] == 0x1f && body[1] == 0x8b {
		gzReader, err := gzip.NewReader(bytes.NewReader(body))
		if err != nil {
			return nil, fmt.Errorf("gzip decompression failed: %w", err)
		}
		defer gzReader.Close()
		return io.ReadAll(gzReader)
	}
	return body, nil
}

// Book executes the full booking flow for a given availability.
func (b *Booker) Book(ctx context.Context, avail parser.Availability) error {
	// 1. Concurrency/Duplicate Guard
	if !b.state.StartProcessing(avail.Date) {
		b.logger.WithField("date", avail.Date).Info("Already processing this date")
		return nil
	}
	defer b.state.StopProcessing(avail.Date)

	// 2. Build absolute Booking URL
	bookingURL := avail.BookingURL
	if !strings.HasPrefix(bookingURL, "http") {
		bookingURL = b.siteConfig.BaseURL + bookingURL
	}

	b.logger.WithField("url", bookingURL).Info("Starting booking flow")

	// 3. Initial Request (Identity Sticky)
	req, err := http.NewRequestWithContext(ctx, "GET", bookingURL, nil)
	if err != nil {
		return err
	}

	// PRISTINE MIMICRY: Ensure AJAX header is NOT present during human page navigation
	req.Header.Del("X-Requested-With")
	req.Header.Set("Referer", b.siteConfig.BaseURL+"/passes/"+b.museum.Slug)

	resp, err := b.client.Do(req)
	if err != nil {
		return fmt.Errorf("initial booking request failed: %w", err)
	}
	defer resp.Body.Close()

	rawBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	bodyBytes, err := decompressBody(rawBody)
	if err != nil {
		return err
	}
	bodyStr := string(bodyBytes)

	b.logger.WithField("final_url", resp.Request.URL.String()).Info("After initial request (including redirects)")

	doc, err := goquery.NewDocumentFromReader(strings.NewReader(bodyStr))
	if err != nil {
		return err
	}

	// 4. Handle Login Form Detection & Submission
	loginForm := doc.Find("form[action*='form_login']")
	var finalResp *http.Response
	var finalBody []byte

	if loginForm.Length() > 0 {
		b.logger.Info("Login required, performing login")
		authID := loginForm.Find("input[name='auth_id']").AttrOr("value", "")
		loginURLField := loginForm.Find("input[name='login_url']").AttrOr("value", "")
		if authID == "" || loginURLField == "" {
			return fmt.Errorf("could not extract auth_id or login_url from login form")
		}

		loginData := url.Values{}
		loginData.Set("auth_id", authID)
		loginData.Set("login_url", loginURLField)
		loginData.Set(b.siteConfig.LoginForm.UsernameField, b.siteConfig.LoginForm.Username)
		loginData.Set(b.siteConfig.LoginForm.PasswordField, b.siteConfig.LoginForm.Password)

		loginAction := loginForm.AttrOr("action", "")
		if strings.HasPrefix(loginAction, "/") {
			u, _ := url.Parse(loginAction)
			if u.Host == "" {
				loginAction = "https://" + resp.Request.URL.Host + loginAction
			}
		} else if !strings.HasPrefix(loginAction, "http") {
			loginAction = "https://" + resp.Request.URL.Host + "/" + loginAction
		}

		b.logger.WithField("login_action", loginAction).Info("POST to login")

		loginReq, err := http.NewRequestWithContext(ctx, "POST", loginAction, strings.NewReader(loginData.Encode()))
		if err != nil {
			return err
		}
		loginReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		loginReq.Header.Set("Referer", resp.Request.URL.String())
		loginReq.Header.Del("X-Requested-With") // Ensure no AJAX headers

		loginResp, err := b.client.Do(loginReq)
		if err != nil {
			return fmt.Errorf("login POST failed: %w", err)
		}
		defer loginResp.Body.Close()

		finalBodyRaw, err := io.ReadAll(loginResp.Body)
		if err != nil {
			return err
		}
		finalBody, err = decompressBody(finalBodyRaw)
		if err != nil {
			return err
		}
		finalResp = loginResp
		b.logger.WithField("final_url", finalResp.Request.URL.String()).Info("After login (including redirects)")
		
		doc, err = goquery.NewDocumentFromReader(strings.NewReader(string(finalBody)))
		if err != nil {
			return err
		}
	} else {
		finalBody = bodyBytes
		finalResp = resp
		b.logger.Info("No login form found – assuming already logged in")
	}

	// 5. Booking Form Extraction (CSRF tokens and fields)
	bookingForm := doc.Find("form#s-lc-bform")
	if bookingForm.Length() == 0 {
		snippet := string(finalBody)
		if len(snippet) > 1000 {
			snippet = snippet[:1000] + "..."
		}
		b.logger.WithFields(logrus.Fields{
			"url":     finalResp.Request.URL.String(),
			"snippet": snippet,
		}).Error("Booking form not found")

		if strings.Contains(doc.Text(), "Sorry, this would exceed the monthly booking limit") {
			return fmt.Errorf("booking limit exceeded")
		}
		if strings.Contains(finalResp.Request.URL.String(), "unavailable") {
			return fmt.Errorf("spot already taken (unavailable in URL)")
		}
		return fmt.Errorf("booking form not found")
	}

	formData := url.Values{}
	bookingForm.Find("input[type='hidden']").Each(func(i int, s *goquery.Selection) {
		name := s.AttrOr("name", "")
		value := s.AttrOr("value", "")
		if name != "" {
			formData.Set(name, value)
		}
	})
	
	emailKey := b.siteConfig.BookingForm.EmailField
	if emailKey == "" {
		emailKey = "email"
	}
	formData.Set(emailKey, b.siteConfig.LoginForm.Email)

	action := bookingForm.AttrOr("action", "")
	if strings.HasPrefix(action, "/") {
		u, _ := url.Parse(action)
		if u.Host == "" {
			action = "https://" + finalResp.Request.URL.Host + action
		}
	} else if !strings.HasPrefix(action, "http") {
		action = "https://" + finalResp.Request.URL.Host + "/" + action
	}

	b.logger.WithField("action", action).Info("Submitting booking form")

	// 6. Final Booking Submission
	submitReq, err := http.NewRequestWithContext(ctx, "POST", action, strings.NewReader(formData.Encode()))
	if err != nil {
		return err
	}
	submitReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	submitReq.Header.Set("Referer", finalResp.Request.URL.String())
	submitReq.Header.Del("X-Requested-With") // Maintain header purity

	submitResp, err := b.client.Do(submitReq)
	if err != nil {
		return fmt.Errorf("booking submission failed: %w", err)
	}
	defer submitResp.Body.Close()

	resultRaw, err := io.ReadAll(submitResp.Body)
	if err != nil {
		return err
	}
	resultDecompressed, err := decompressBody(resultRaw)
	if err != nil {
		return err
	}
	resultStr := string(resultDecompressed)

	// 7. Verify Result based on Technical Spec indicators
	if strings.Contains(resultStr, "Thank you!") || strings.Contains(resultStr, "The following Digital Pass reservation was made:") {
		b.logger.Info("Booking successful")
		b.state.MarkSeen(avail.Date)
		return nil
	}

	// 8. Handle Failures based on your Logic
	if !(strings.Contains(resultStr, "Thank you!") || strings.Contains(resultStr, "The following Digital Pass reservation was made:")) {
		snippet := resultStr
		if len(snippet) > 500 {
			snippet = snippet[:500] + "..."
		}
		b.logger.WithField("snippet", snippet).Warn("Unexpected final response")
	}

	if strings.Contains(resultStr, "Sorry, this would exceed the monthly booking limit") {
		return fmt.Errorf("booking limit exceeded")
	}
	if strings.Contains(resultStr, "unavailable") || strings.Contains(resultStr, "not available") {
		return fmt.Errorf("spot no longer available")
	}

	return fmt.Errorf("booking failed: unknown response")
}

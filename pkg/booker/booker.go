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
 * decompressBody handles the manual unzipping required because of the
 * mimicry headers used in client.go.
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

func (b *Booker) Book(ctx context.Context, avail parser.Availability) error {
	if !b.state.StartProcessing(avail.Date) {
		b.logger.WithField("date", avail.Date).Info("Already processing this date")
		return nil
	}
	defer b.state.StopProcessing(avail.Date)

	bookingURL := avail.BookingURL
	if !strings.HasPrefix(bookingURL, "http") {
		bookingURL = b.siteConfig.BaseURL + bookingURL
	}

	b.logger.WithField("url", bookingURL).Info("Starting booking flow")

	req, err := http.NewRequestWithContext(ctx, "GET", bookingURL, nil)
	if err != nil {
		return err
	}

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

	doc, err := goquery.NewDocumentFromReader(strings.NewReader(bodyStr))
	if err != nil {
		return err
	}

	// --- STEP: Authentication ---
	loginForm := doc.Find("form[action*='form_login']")
	var finalResp *http.Response
	var finalBody []byte

	if loginForm.Length() > 0 {
		b.logger.Info("Login required, performing login")
		authID := loginForm.Find("input[name='auth_id']").AttrOr("value", "")
		loginURLField := loginForm.Find("input[name='login_url']").AttrOr("value", "")
		if authID == "" || loginURLField == "" {
			return fmt.Errorf("could not extract session tokens")
		}

		loginData := url.Values{}
		loginData.Set("auth_id", authID)
		loginData.Set("login_url", loginURLField)
		loginData.Set(b.siteConfig.LoginForm.UsernameField, b.siteConfig.LoginForm.Username)
		loginData.Set(b.siteConfig.LoginForm.PasswordField, b.siteConfig.LoginForm.Password)

		loginAction := loginForm.AttrOr("action", "")
		if strings.HasPrefix(loginAction, "/") {
			loginAction = "https://" + resp.Request.URL.Host + loginAction
		}

		loginReq, err := http.NewRequestWithContext(ctx, "POST", loginAction, strings.NewReader(loginData.Encode()))
		if err != nil {
			return err
		}
		loginReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		loginReq.Header.Set("Referer", resp.Request.URL.String())
		loginReq.Header.Del("X-Requested-With")

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
		
		doc, err = goquery.NewDocumentFromReader(strings.NewReader(string(finalBody)))
		if err != nil {
			return err
		}

		/**
		 * SCENARIO 2: INVALID CREDENTIALS
		 * Deep Audit: Check for the specific alert-danger div with the message.
		 */
		errMsg := doc.Find("#s-libapps-public-message.alert.alert-danger").Text()
		if strings.Contains(errMsg, "Your credentials are not working") {
			return fmt.Errorf("FATAL: credentials are not working")
		}

	} else {
		finalBody = bodyBytes
		finalResp = resp
		b.logger.Info("No login form found – assuming already logged in")
	}

	// --- STEP: Form Extraction ---
	bookingForm := doc.Find("form#s-lc-bform")
	if bookingForm.Length() == 0 {
		// Detect limit exceeded even before submission if possible
		if strings.Contains(doc.Text(), "monthly booking limit") {
			return fmt.Errorf("FATAL: booking limit exceeded")
		}
		
		if strings.Contains(finalResp.Request.URL.String(), "unavailable") {
			return fmt.Errorf("spot already taken")
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
	if emailKey == "" { emailKey = "email" }
	formData.Set(emailKey, b.siteConfig.LoginForm.Email)

	action := bookingForm.AttrOr("action", "")
	if strings.HasPrefix(action, "/") {
		action = "https://" + finalResp.Request.URL.Host + action
	}

	b.logger.WithField("action", action).Info("Submitting booking form")

	submitReq, err := http.NewRequestWithContext(ctx, "POST", action, strings.NewReader(formData.Encode()))
	if err != nil {
		return err
	}
	submitReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	submitReq.Header.Set("Referer", finalResp.Request.URL.String())
	submitReq.Header.Del("X-Requested-With")

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

	// Check for Success
	if strings.Contains(resultStr, "Thank you!") || strings.Contains(resultStr, "The following Digital Pass reservation was made:") {
		b.logger.Info("Booking successful")
		b.state.MarkSeen(avail.Date)
		return nil
	}

	/**
	 * SCENARIO 1: BOOKING LIMIT EXCEEDED
	 * Check final result page for limit messages.
	 */
	if strings.Contains(resultStr, "Sorry, this would exceed the monthly booking limit") || 
	   strings.Contains(resultStr, "limit reached") {
		return fmt.Errorf("FATAL: booking limit exceeded")
	}

	if strings.Contains(resultStr, "unavailable") || strings.Contains(resultStr, "not available") {
		return fmt.Errorf("spot no longer available")
	}

	return fmt.Errorf("booking failed: unknown response")
}

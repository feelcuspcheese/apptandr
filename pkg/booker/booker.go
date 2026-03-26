package booker

import (
    "agent/pkg/config"
    "agent/pkg/httpclient"
    "agent/pkg/parser"
    "agent/pkg/state"
    "context"
    "fmt"
    "github.com/PuerkitoBio/goquery"
    "github.com/sirupsen/logrus"
    "io"
    "net/http"
    "net/url"
    "strings"
)

type Booker struct {
    client     *httpclient.Client
    siteConfig config.SiteInfo
    state      *state.State
    logger     *logrus.Logger
}

func NewBooker(client *httpclient.Client, cfg config.SiteInfo, state *state.State, logger *logrus.Logger) *Booker {
    return &Booker{
        client:     client,
        siteConfig: cfg,
        state:      state,
        logger:     logger,
    }
}

// PreLogin attempts to log in and get a valid session before strike time.
func (b *Booker) PreLogin(ctx context.Context) error {
    b.logger.Info("Pre‑warming login")
    return nil
}

// Book executes the full booking flow for a given availability.
func (b *Booker) Book(ctx context.Context, avail parser.Availability) error {
    if !b.state.StartProcessing(avail.Date) {
        b.logger.WithField("date", avail.Date).Info("Already processing this date")
        return nil
    }
    defer b.state.StopProcessing(avail.Date)

    // Build full booking URL
    bookingURL := avail.BookingURL
    if !strings.HasPrefix(bookingURL, "http") {
        bookingURL = b.siteConfig.BaseURL + bookingURL
    }

    b.logger.WithField("url", bookingURL).Info("Starting booking flow")

    // Step 1: Follow the initial booking link (which may redirect to auth)
    req, err := http.NewRequestWithContext(ctx, "GET", bookingURL, nil)
    if err != nil {
        return err
    }
    req.Header.Set("Referer", b.siteConfig.BaseURL+"/passes/"+b.siteConfig.Slug)
    resp, err := b.client.Do(req)
    if err != nil {
        return fmt.Errorf("initial booking request failed: %w", err)
    }
    defer resp.Body.Close()

    doc, err := goquery.NewDocumentFromReader(resp.Body)
    if err != nil {
        return err
    }

    // Check if we need to login
    loginForm := doc.Find("form[action*='form_login']")
    var finalResp *http.Response
    if loginForm.Length() > 0 {
        b.logger.Info("Login required, performing login")
        authID := loginForm.Find("input[name='auth_id']").AttrOr("value", "")
        loginURL := loginForm.Find("input[name='login_url']").AttrOr("value", "")
        if authID == "" || loginURL == "" {
            return fmt.Errorf("could not extract auth_id or login_url from login form")
        }

        loginData := url.Values{}
        loginData.Set("auth_id", authID)
        loginData.Set("login_url", loginURL)
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

        loginReq, err := http.NewRequestWithContext(ctx, "POST", loginAction, strings.NewReader(loginData.Encode()))
        if err != nil {
            return err
        }
        loginReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
        loginReq.Header.Set("Referer", resp.Request.URL.String())
        loginResp, err := b.client.Do(loginReq)
        if err != nil {
            return fmt.Errorf("login POST failed: %w", err)
        }
        defer loginResp.Body.Close()

        finalResp = loginResp
        doc, err = goquery.NewDocumentFromReader(finalResp.Body)
        if err != nil {
            return err
        }
    } else {
        finalResp = resp
    }

    // Now doc should contain the booking form
    bookingForm := doc.Find("form#s-lc-bform")
    if bookingForm.Length() == 0 {
        if strings.Contains(doc.Text(), "Sorry, this would exceed the monthly booking limit") {
            return fmt.Errorf("booking limit exceeded")
        }
        if strings.Contains(finalResp.Request.URL.String(), "unavailable") {
            return fmt.Errorf("spot already taken (unavailable in URL)")
        }
        return fmt.Errorf("booking form not found")
    }

    // Extract hidden fields
    formData := url.Values{}
    bookingForm.Find("input[type='hidden']").Each(func(i int, s *goquery.Selection) {
        name := s.AttrOr("name", "")
        value := s.AttrOr("value", "")
        if name != "" {
            formData.Set(name, value)
        }
    })

    // Add email field
    emailFieldName := b.siteConfig.BookingForm.EmailField
    if emailFieldName == "" {
        emailFieldName = "email"
    }
    emailValue := b.siteConfig.BookingForm.EmailFieldValue
    if emailValue == "" {
        emailValue = b.siteConfig.LoginForm.Username // fallback
    }
    formData.Set(emailFieldName, emailValue)

    // Determine action URL
    action := bookingForm.AttrOr("action", "")
    if strings.HasPrefix(action, "/") {
        u, _ := url.Parse(action)
        if u.Host == "" {
            action = "https://" + finalResp.Request.URL.Host + action
        }
    } else if !strings.HasPrefix(action, "http") {
        action = "https://" + finalResp.Request.URL.Host + "/" + action
    }

    // Submit booking
    submitReq, err := http.NewRequestWithContext(ctx, "POST", action, strings.NewReader(formData.Encode()))
    if err != nil {
        return err
    }
    submitReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    submitReq.Header.Set("Referer", finalResp.Request.URL.String())
    submitResp, err := b.client.Do(submitReq)
    if err != nil {
        return fmt.Errorf("booking submission failed: %w", err)
    }
    defer submitResp.Body.Close()

    bodyBytes, err := io.ReadAll(submitResp.Body)
    if err != nil {
        return err
    }
    bodyStr := string(bodyBytes)

    if strings.Contains(bodyStr, "Thank you!") || strings.Contains(bodyStr, "The following Digital Pass reservation was made:") {
        b.logger.Info("Booking successful")
        b.state.MarkSeen(avail.Date)
        return nil
    }

    if strings.Contains(bodyStr, "Sorry, this would exceed the monthly booking limit") {
        return fmt.Errorf("booking limit exceeded")
    }
    if strings.Contains(bodyStr, "unavailable") || strings.Contains(bodyStr, "not available") {
        return fmt.Errorf("spot no longer available")
    }

    return fmt.Errorf("booking failed: unknown response")
}

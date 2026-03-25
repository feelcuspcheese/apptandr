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
    "time"
)

type Booker struct {
    client     *httpclient.Client
    siteConfig config.SiteConfig
    state      *state.State
    logger     *logrus.Logger
}

func NewBooker(client *httpclient.Client, cfg config.SiteConfig, state *state.State, logger *logrus.Logger) *Booker {
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
    // We don't have a booking URL yet, so we just perform a dummy login flow?
    // Actually we need to start from a known entry point. We can use the availability check endpoint with a dummy date.
    // But maybe easier: just fetch the base URL to establish session and then do login if needed.
    // For now, we can call login flow with a dummy date (like today) to get a session.
    // But login flow requires a booking URL to redirect back. We can use a dummy booking URL that will likely fail.
    // Let's just skip pre-login for now because the login flow is tied to a specific booking URL.
    // Instead, we rely on the normal book flow.
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
    // Set referer header to mimic browser
    req.Header.Set("Referer", b.siteConfig.BaseURL+"/passes/"+b.siteConfig.MuseumID) // adjust
    resp, err := b.client.Do(req)
    if err != nil {
        return fmt.Errorf("initial booking request failed: %w", err)
    }
    defer resp.Body.Close()

    // After following redirects, we should be on either the login page or the booking form.
    // The client automatically follows redirects, so resp is the final response.
    // Check if we are on login page: look for a form with action containing "form_login".
    doc, err := goquery.NewDocumentFromReader(resp.Body)
    if err != nil {
        return err
    }

    // Check if we need to login
    loginForm := doc.Find("form[action*='form_login']")
    if loginForm.Length() > 0 {
        b.logger.Info("Login required, performing login")
        // Extract hidden fields from the login form
        authID := loginForm.Find("input[name='auth_id']").AttrOr("value", "")
        loginURL := loginForm.Find("input[name='login_url']").AttrOr("value", "")
        if authID == "" || loginURL == "" {
            return fmt.Errorf("could not extract auth_id or login_url from login form")
        }

        // Prepare login POST data
        loginData := url.Values{}
        loginData.Set("auth_id", authID)
        loginData.Set("login_url", loginURL)
        loginData.Set(b.siteConfig.LoginForm.UsernameField, b.siteConfig.LoginForm.Username)
        loginData.Set(b.siteConfig.LoginForm.PasswordField, b.siteConfig.LoginForm.Password)

        // Determine login action URL (may be relative)
        loginAction := loginForm.AttrOr("action", "")
        if strings.HasPrefix(loginAction, "/") {
            // Resolve relative to current domain
            u, _ := url.Parse(loginAction)
            if u.Host == "" {
                // relative to current host
                loginAction = "https://" + resp.Request.URL.Host + loginAction
            }
        } else if !strings.HasPrefix(loginAction, "http") {
            loginAction = "https://" + resp.Request.URL.Host + "/" + loginAction
        }

        // POST login
        loginReq, err := http.NewRequestWithContext(ctx, "POST", loginAction, strings.NewReader(loginData.Encode()))
        if err != nil {
            return err
        }
        loginReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
        loginReq.Header.Set("Referer", resp.Request.URL.String())
        // Copy cookies from client's jar (already included)
        loginResp, err := b.client.Do(loginReq)
        if err != nil {
            return fmt.Errorf("login POST failed: %w", err)
        }
        defer loginResp.Body.Close()

        // After successful login, we should be redirected to the original booking URL with a token.
        // The client will follow the redirect automatically.
        // So we need to read the final response after login.
        // However, we need to get that final response's body to proceed to booking form.
        // The loginResp may be a redirect (302) with Location header. The client will follow it,
        // but we have the final response in loginResp after redirects.
        // Now we should parse that final response to get the booking form.
        finalResp := loginResp // after redirects
        doc, err = goquery.NewDocumentFromReader(finalResp.Body)
        if err != nil {
            return err
        }
    }

    // At this point, doc should contain the booking form (if login succeeded or was not needed)
    bookingForm := doc.Find("form#s-lc-bform")
    if bookingForm.Length() == 0 {
        // Check for error messages
        if strings.Contains(doc.Text(), "Sorry, this would exceed the monthly booking limit") {
            return fmt.Errorf("booking limit exceeded")
        }
        // Check if the URL contains "unavailable"
        if strings.Contains(resp.Request.URL.String(), "unavailable") {
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
    email := b.siteConfig.BookingForm.EmailField // we need to configure this
    if email == "" {
        email = "email" // default
    }
    formData.Set(email, b.siteConfig.LoginForm.Username) // maybe use a configured email

    // Determine action URL
    action := bookingForm.AttrOr("action", "")
    if strings.HasPrefix(action, "/") {
        u, _ := url.Parse(action)
        if u.Host == "" {
            action = "https://" + resp.Request.URL.Host + action
        }
    } else if !strings.HasPrefix(action, "http") {
        action = "https://" + resp.Request.URL.Host + "/" + action
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

    // Check success
    bodyBytes, err := io.ReadAll(submitResp.Body)
    if err != nil {
        return err
    }
    bodyStr := string(bodyBytes)

    // Success indicator: "Thank you!" or "The following Digital Pass reservation was made:"
    if strings.Contains(bodyStr, "Thank you!") || strings.Contains(bodyStr, "The following Digital Pass reservation was made:") {
        b.logger.Info("Booking successful")
        // Extract the actual date from the booking URL for marking seen
        // We'll mark the date from avail
        b.state.MarkSeen(avail.Date)
        return nil
    }

    // Check for failure messages
    if strings.Contains(bodyStr, "Sorry, this would exceed the monthly booking limit") {
        return fmt.Errorf("booking limit exceeded")
    }
    if strings.Contains(bodyStr, "unavailable") || strings.Contains(bodyStr, "not available") {
        return fmt.Errorf("spot no longer available")
    }

    return fmt.Errorf("booking failed: unknown response")
}

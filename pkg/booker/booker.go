package booker

import (
    "agent/pkg/config"
    "agent/pkg/httpclient"
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

// PreLogin performs login to establish session before strike time.
func (b *Booker) PreLogin(ctx context.Context) error {
    b.logger.Info("Pre‑warming login")
    return b.login(ctx)
}

func (b *Booker) Book(ctx context.Context, avail Availability) error {
    if !b.state.StartProcessing(avail.Date) {
        b.logger.WithField("date", avail.Date).Info("Already processing this date")
        return nil
    }
    defer b.state.StopProcessing(avail.Date)

    // Step 1: Ensure logged in (login may have been pre‑warmed, but re‑call in case session expired)
    if err := b.login(ctx); err != nil {
        return fmt.Errorf("login failed: %w", err)
    }

    // Step 2: Navigate to booking link
    bookingURL := avail.BookingURL
    if !strings.HasPrefix(bookingURL, "http") {
        bookingURL = b.siteConfig.BaseURL + bookingURL
    }
    req, err := http.NewRequestWithContext(ctx, "GET", bookingURL, nil)
    if err != nil {
        return err
    }
    resp, err := b.client.Do(req)
    if err != nil {
        return err
    }
    defer resp.Body.Close()

    // Step 3: Extract form data
    formData, err := b.extractFormData(resp.Body)
    if err != nil {
        return err
    }

    // Step 4: Submit booking form
    actionURL := b.siteConfig.BookingForm.ActionURL
    if !strings.HasPrefix(actionURL, "http") {
        actionURL = b.siteConfig.BaseURL + actionURL
    }
    form := url.Values{}
    for k, v := range formData {
        form.Set(k, v)
    }
    // Add the selected date (if needed)
    form.Set("date", avail.Date) // adjust field name as needed

    req, err = http.NewRequestWithContext(ctx, "POST", actionURL, strings.NewReader(form.Encode()))
    if err != nil {
        return err
    }
    req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    resp, err = b.client.Do(req)
    if err != nil {
        return err
    }
    defer resp.Body.Close()

    // Step 5: Check success
    success, err := b.isSuccess(resp.Body)
    if err != nil {
        return err
    }
    if success {
        b.logger.WithField("date", avail.Date).Info("Booking successful")
        b.state.MarkSeen(avail.Date)
        return nil
    }
    return fmt.Errorf("booking failed (response indicated failure)")
}

func (b *Booker) login(ctx context.Context) error {
    // Load login page to get CSRF token
    req, err := http.NewRequestWithContext(ctx, "GET", b.siteConfig.LoginURL, nil)
    if err != nil {
        return err
    }
    resp, err := b.client.Do(req)
    if err != nil {
        return err
    }
    defer resp.Body.Close()

    doc, err := goquery.NewDocumentFromReader(resp.Body)
    if err != nil {
        return err
    }
    csrfToken := ""
    if b.siteConfig.LoginForm.CSRFSelector != "" {
        csrfToken = doc.Find(b.siteConfig.LoginForm.CSRFSelector).AttrOr("value", "")
    }

    // Prepare login form
    loginData := url.Values{}
    loginData.Set(b.siteConfig.LoginForm.UsernameField, b.siteConfig.LoginForm.Username)
    loginData.Set(b.siteConfig.LoginForm.PasswordField, b.siteConfig.LoginForm.Password)
    if csrfToken != "" {
        loginData.Set("csrf_token", csrfToken)
    }

    req, err = http.NewRequestWithContext(ctx, "POST", b.siteConfig.LoginURL, strings.NewReader(loginData.Encode()))
    if err != nil {
        return err
    }
    req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
    resp, err = b.client.Do(req)
    if err != nil {
        return err
    }
    defer resp.Body.Close()

    // Check if login succeeded
    if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusFound {
        return fmt.Errorf("login returned status %d", resp.StatusCode)
    }
    b.logger.Info("Login successful")
    return nil
}

func (b *Booker) extractFormData(r io.Reader) (map[string]string, error) {
    doc, err := goquery.NewDocumentFromReader(r)
    if err != nil {
        return nil, err
    }
    data := make(map[string]string)
    for _, field := range b.siteConfig.BookingForm.Fields {
        if field.Type == "hidden" {
            val := doc.Find(field.Selector).AttrOr("value", "")
            data[field.Name] = val
        } else if field.Type == "select" {
            // pick first option maybe
            val := doc.Find(field.Selector + " option[selected]").AttrOr("value", "")
            if val == "" {
                val = doc.Find(field.Selector + " option:first-child").AttrOr("value", "")
            }
            data[field.Name] = val
        } else if field.Value != "" {
            data[field.Name] = field.Value
        }
    }
    return data, nil
}

func (b *Booker) isSuccess(r io.Reader) (bool, error) {
    doc, err := goquery.NewDocumentFromReader(r)
    if err != nil {
        return false, err
    }
    // Check if success indicator text appears
    return strings.Contains(doc.Text(), b.siteConfig.SuccessIndicator), nil
}

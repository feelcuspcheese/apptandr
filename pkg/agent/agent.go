package agent

import (
    "agent/pkg/booker"
    "agent/pkg/config"
    "agent/pkg/httpclient"
    "agent/pkg/notifier"
    "agent/pkg/parser"
    "agent/pkg/scraper"
    "agent/pkg/state"
    "context"
    "fmt"
    "math/rand"
    "net/http"
    "net/url"
    "sync"
    "time"

    "github.com/sirupsen/logrus"
)

type Agent struct {
    config       *config.AppConfig
    logger       *logrus.Logger
    stateManager *state.State
    cancelFunc   context.CancelFunc
    running      bool
    logCh        chan string
    wg           sync.WaitGroup
    doneCh       chan struct{}
}

func NewAgent(cfg *config.AppConfig, logger *logrus.Logger) *Agent {
    return &Agent{
        config:       cfg,
        logger:       logger,
        stateManager: state.NewState(),
        logCh:        make(chan string, 100),
        doneCh:       make(chan struct{}),
    }
}

// LogChannel returns a channel that receives log messages.
func (a *Agent) LogChannel() <-chan string {
    return a.logCh
}

// IsRunning returns whether the agent is currently running.
func (a *Agent) IsRunning() bool {
    return a.running
}

// Start blocks until the agent finishes (or Stop is called).
// dropTime is the time when the check window should start.
func (a *Agent) Start(dropTime time.Time) error {
    if a.running {
        return fmt.Errorf("agent already running")
    }
    ctx, cancel := context.WithCancel(context.Background())
    a.cancelFunc = cancel
    a.running = true
    a.wg.Add(1)

    go func() {
        defer a.wg.Done()
        defer close(a.doneCh)
        a.run(ctx, dropTime)
    }()

    // Wait for the agent to finish
    select {
    case <-a.doneCh:
        a.running = false
        return nil
    case <-ctx.Done():
        a.running = false
        return ctx.Err()
    }
}

// Stop cancels the agent execution and waits for it to finish.
func (a *Agent) Stop() {
    if a.cancelFunc != nil {
        a.cancelFunc()
    }
    a.wg.Wait()
    a.running = false
}

func (a *Agent) run(ctx context.Context, dropTime time.Time) {
    defer func() { a.running = false }()
    a.log("Agent starting, drop time: %v", dropTime)

    // Select the active site (preferred slug)
    activeSite, ok := a.config.Sites[a.config.PreferredSlug]
    if !ok {
        // Fallback to first site
        for _, s := range a.config.Sites {
            activeSite = s
            break
        }
        if activeSite.Slug == "" {
            a.log("No sites configured and no preferred slug set")
            return
        }
        a.log("Preferred slug not found, using first site: %s", activeSite.Slug)
    }

    // Prepare HTTP client with realistic headers
    headers := map[string]string{
        "User-Agent":      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
        "Accept":          "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language": "en-US,en;q=0.9",
        "Accept-Encoding": "gzip, deflate, br, zstd",
        "Connection":      "keep-alive",
        "Referer":         activeSite.BaseURL + "/passes/" + activeSite.Slug,
        "X-Requested-With": "XMLHttpRequest",
    }
    client, err := httpclient.NewClient(headers, 30*time.Second)
    if err != nil {
        a.log("Failed to create HTTP client: %v", err)
        return
    }

    // Build availability endpoint template
    endpointTemplate := fmt.Sprintf("%s%s?museum=%s&date={date}&digital=%t&physical=%t&location=%s",
        activeSite.BaseURL,
        activeSite.AvailabilityEndpoint,
        activeSite.MuseumID,
        activeSite.Digital,
        activeSite.Physical,
        activeSite.Location,
    )
    scraperInst := scraper.NewScraper(client, endpointTemplate, a.config.RequestJitter, a.logger)

    // Pre-warm connection
    a.log("Pre‑warming...")
    if err := a.preWarm(client, activeSite.BaseURL); err != nil {
        a.log("Pre‑warm failed: %v", err)
    } else {
        a.log("Pre‑warm completed")
    }

    // Wait until drop time
    now := time.Now()
    if dropTime.After(now) {
        wait := dropTime.Sub(now)
        a.log("Waiting %v until drop time", wait)
        select {
        case <-time.After(wait):
        case <-ctx.Done():
            a.log("Agent stopped before drop time")
            return
        }
    }

    // Start check window
    deadline := time.Now().Add(a.config.CheckWindow)
    a.log("Check window started, deadline: %v", deadline)

    // Main check loop
    ntfy := notifier.NewNtfy(a.config.NtfyTopic)

    for {
        // Check if window expired
        if time.Now().After(deadline) {
            a.log("Check window expired")
            break
        }

        // Apply jitter before check
        if a.config.RequestJitter > 0 {
            jitter := time.Duration(rand.Int63n(int64(a.config.RequestJitter)))
            a.log("Applying jitter %v before check", jitter)
            select {
            case <-time.After(jitter):
            case <-ctx.Done():
                return
            }
        }

        // Perform availability check
        stop, err := a.checkAvailability(ctx, scraperInst, ntfy, activeSite, client)
        if err != nil {
            a.log("Check error: %v", err)
        }
        if stop {
            a.log("Booking successful, stopping checks")
            break
        }

        // Wait for next interval (with jitter)
        interval := a.config.CheckInterval
        if a.config.RequestJitter > 0 {
            interval += time.Duration(rand.Int63n(int64(a.config.RequestJitter)))
        }
        a.log("Waiting %v before next check", interval)
        select {
        case <-time.After(interval):
        case <-ctx.Done():
            a.log("Agent stopped during wait")
            return
        }
    }
    a.log("Agent finished")
}

// checkAvailability performs a single availability check and returns whether to stop.
func (a *Agent) checkAvailability(ctx context.Context, scraperInst *scraper.Scraper, ntfy *notifier.Ntfy, activeSite config.SiteInfo, client *httpclient.Client) (bool, error) {
    // Determine months to fetch
    now := time.Now()
    months := a.config.MonthsToCheck
    if months < 1 {
        months = 1
    }

    allAvails := []parser.Availability{}
    for i := 0; i < months; i++ {
        date := now.AddDate(0, i, 0).Format("2006-01-02")
        a.log("Fetching availability for month starting %s", date)
        avails, err := scraperInst.FetchForDate(ctx, date)
        if err != nil {
            a.log("Error fetching availability for %s: %v", date, err)
            continue
        }
        allAvails = append(allAvails, avails...)
        if i < months-1 && a.config.RequestJitter > 0 {
            time.Sleep(time.Duration(rand.Int63n(int64(a.config.RequestJitter))))
        }
    }

    a.log("Total availabilities found: %d", len(allAvails))
    if len(allAvails) == 0 {
        return false, nil
    }

    // Filter unseen and extract full dates
    newAvails := []parser.Availability{}
    for _, av := range allAvails {
        fullDate, err := extractDateFromURL(av.BookingURL)
        if err != nil {
            a.log("Failed to extract date from URL %s: %v", av.BookingURL, err)
            continue
        }
        if !a.stateManager.IsSeen(fullDate) {
            a.stateManager.MarkSeen(fullDate)
            av.Date = fullDate
            newAvails = append(newAvails, av)
        }
    }

    if len(newAvails) == 0 {
        a.log("No new availabilities")
        return false, nil
    }

    if a.config.Mode == "alert" {
        // Send notification with buttons
        notifyAvails := make([]notifier.AvailabilityWithLink, len(newAvails))
        for i, av := range newAvails {
            notifyAvails[i] = notifier.AvailabilityWithLink{
                Date:       av.Date,
                BookingURL: av.BookingURL,
            }
        }
        actions := notifier.PrioritizeActions(notifyAvails)
        err := ntfy.SendNotification(
            "Appointment Available!",
            "Found new appointment dates",
            notifier.PriorityHigh,
            actions,
        )
        if err != nil {
            a.log("Failed to send notification: %v", err)
        } else {
            a.log("Notification sent for %d dates", len(newAvails))
        }
        return false, nil
    } else if a.config.Mode == "booking" {
        // Try to book
        bookerInst := booker.NewBooker(client, activeSite, a.stateManager, a.logger)
        for _, prefDay := range a.config.PreferredDays {
            for _, av := range newAvails {
                if isDayOfWeek(av.Date, prefDay) {
                    a.log("Attempting to book %s", av.Date)
                    if err := bookerInst.Book(ctx, av); err != nil {
                        a.log("Booking failed for %s: %v", av.Date, err)
                        _ = ntfy.SendNotification(
                            "Booking Failed",
                            fmt.Sprintf("Failed to book %s: %v", av.Date, err),
                            notifier.PriorityHigh,
                            nil,
                        )
                        return false, nil
                    } else {
                        a.log("Booking successful for %s", av.Date)
                        _ = ntfy.SendNotification(
                            "Booking Successful",
                            fmt.Sprintf("Successfully booked %s", av.Date),
                            notifier.PriorityUrgent,
                            nil,
                        )
                        return true, nil // stop checking
                    }
                }
            }
        }
        a.log("No preferred day found among new availabilities")
        return false, nil
    }
    return false, nil
}

func (a *Agent) preWarm(client *httpclient.Client, baseURL string) error {
    req, err := http.NewRequest("GET", baseURL, nil)
    if err != nil {
        return err
    }
    resp, err := client.Do(req)
    if err != nil {
        return err
    }
    defer resp.Body.Close()
    return nil
}

func (a *Agent) log(format string, args ...interface{}) {
    msg := fmt.Sprintf(format, args...)
    a.logger.Info(msg)
    select {
    case a.logCh <- msg:
    default:
    }
}

func extractDateFromURL(rawURL string) (string, error) {
    u, err := url.Parse(rawURL)
    if err != nil {
        return "", err
    }
    q := u.Query()
    date := q.Get("date")
    if date == "" {
        return "", fmt.Errorf("no date parameter in URL")
    }
    return date, nil
}

func isDayOfWeek(dateStr, dayName string) bool {
    t, err := time.Parse("2006-01-02", dateStr)
    if err != nil {
        return false
    }
    return t.Weekday().String() == dayName
}

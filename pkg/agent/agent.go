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
	"strings"
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
	mu           sync.RWMutex
	currentRunID string
}

func NewAgent(cfg *config.AppConfig, logger *logrus.Logger) *Agent {
	return &Agent{
		config:       cfg,
		logger:       logger,
		stateManager: state.NewState(),
		logCh:        make(chan string, 100),
	}
}

func (a *Agent) LogChannel() <-chan string {
	return a.logCh
}

func (a *Agent) IsRunning() bool {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.running
}

func (a *Agent) GetCurrentRunID() string {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.currentRunID
}

// Start runs the agent for the given scheduled run ID.
func (a *Agent) Start(runID string) error {
	a.mu.Lock()
	if a.running {
		a.mu.Unlock()
		return fmt.Errorf("agent already running")
	}
	a.running = true
	a.currentRunID = runID
	a.mu.Unlock()

	// Find the run in the config
	var run *config.ScheduledRun
	for _, r := range a.config.ScheduledRuns {
		if r.ID == runID {
			run = &r
			break
		}
	}
	if run == nil {
		a.mu.Lock()
		a.running = false
		a.currentRunID = ""
		a.mu.Unlock()
		return fmt.Errorf("scheduled run %s not found", runID)
	}

	// Create a per-run done channel
	doneCh := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	a.cancelFunc = cancel
	a.wg.Add(1)

	go func() {
		defer a.wg.Done()
		defer close(doneCh)
		a.run(ctx, run)
		a.mu.Lock()
		a.running = false
		a.currentRunID = ""
		a.mu.Unlock()
	}()

	// Wait for completion or stop signal
	select {
	case <-doneCh:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (a *Agent) Stop() {
	if a.cancelFunc != nil {
		a.cancelFunc()
	}
	a.wg.Wait()
}

func (a *Agent) run(ctx context.Context, run *config.ScheduledRun) {
	defer func() {
		// Clean up the run from the scheduled list on finish
		a.removeRun(run.ID)
	}()

	a.log("Agent starting for run %s, drop time: %v", run.ID, run.DropTime)

	site, ok := a.config.Sites[run.SiteKey]
	if !ok {
		a.log("Site %s not found", run.SiteKey)
		return
	}
	museum, ok := site.Museums[run.MuseumSlug]
	if !ok {
		a.log("Museum %s not found in site %s", run.MuseumSlug, run.SiteKey)
		return
	}

	// 1. Create Identity-Sticky Mimic Client
	// This client randomly picks a device (iOS/Android/Chrome) and STICKS to it.
	headers := map[string]string{
		"Accept":           "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
		"Accept-Language": "en-US,en;q=0.9",
		"Referer":         site.BaseURL + "/passes/" + museum.Slug,
		"X-Requested-With": "XMLHttpRequest", // Necessary for AJAX Strike
	}
	
	client, err := httpclient.NewClient(headers, 30*time.Second)
	if err != nil {
		a.log("Failed to initialize mimicry stack: %v", err)
		return
	}

	// Log the identity for debugging and WAF verification
	a.log("Run Identity Locked: %s", client.ChosenProfile.Name)

	// Build availability endpoint template
	endpointTemplate := fmt.Sprintf("%s%s?museum=%s&date={date}&digital=%t&physical=%t&location=%s",
		site.BaseURL,
		site.AvailabilityEndpoint,
		museum.MuseumID,
		site.Digital,
		site.Physical,
		site.Location,
	)
	scraperInst := scraper.NewScraper(client, endpointTemplate, a.config.RequestJitter, a.logger)

	// Pre-warm window
	now := time.Now()
	preWarmTime := run.DropTime.Add(-a.config.PreWarmOffset)
	if preWarmTime.After(now) {
		wait := preWarmTime.Sub(now)
		a.log("Waiting %v until pre-warm offset", wait)
		select {
		case <-time.After(wait):
		case <-ctx.Done():
			a.log("Agent stopped during pre-warm wait")
			return
		}
	}

	// Pre-warm current session identity
	a.log("Pre-warming identity session on %s...", site.BaseURL)
	if site.BaseURL != "" {
		if err := a.preWarm(client, site.BaseURL); err != nil {
			a.log("Pre-warm connection failed: %v", err)
		} else {
			a.log("Session pre-warmed successfully")
		}
	}

	// High-Precision Strike Wait
	a.log("Standby: Final microsecond countdown started")
	if err := waitUntil(ctx, run.DropTime); err != nil {
		a.log("Agent cancelled before strike time")
		return
	}

	// Strike Window Start
	deadline := time.Now().Add(a.config.CheckWindow)
	a.log("STRIKE INITIATED. Window ends: %v", deadline)

	ntfy := notifier.NewNtfy(a.config.NtfyTopic)

	// Rest cycle management for long check windows
	restEnabled := a.config.CheckWindow > 1*time.Minute
	checksCount := 0

	for {
		if time.Now().After(deadline) {
			a.log("Strike window expired. No slots found.")
			break
		}

		// Perform availability check
		stop, err := a.checkAvailability(ctx, scraperInst, ntfy, site, museum, client, run.DropTime, run.Mode)
		if err != nil {
			a.log("Check Warning: %v", err)
		}
		if stop {
			if run.Mode == "alert" {
				a.log("Alert successfully dispatched. Ending run.")
			} else {
				a.log("Booking flow completed. Ending run.")
			}
			break
		}

		if restEnabled {
			checksCount++
		}

		// Next Interval Calculation with Jitter
		interval := a.config.CheckInterval
		if a.config.RequestJitter > 0 {
			interval += time.Duration(rand.Int63n(int64(a.config.RequestJitter)))
		}
		
		a.log("Waiting %v before next check", interval)
		select {
		case <-time.After(interval):
		case <-ctx.Done():
			a.log("Agent stopped during interval delay")
			return
		}

		// Human Mimicry Rest Cycle
		if restEnabled && checksCount >= a.config.RestCycleChecks {
			a.log("Resting for %v (WAF evasion rest cycle)", a.config.RestCycleDuration)
			select {
			case <-time.After(a.config.RestCycleDuration):
				checksCount = 0
			case <-ctx.Done():
				return
			}
		}
	}
	a.log("Run %s finished", run.ID)
}

func (a *Agent) checkAvailability(ctx context.Context, scraperInst *scraper.Scraper, ntfy *notifier.Ntfy, site config.Site, museum config.Museum, client *httpclient.Client, dropTime time.Time, mode string) (bool, error) {
	startDate := dropTime
	months := a.config.MonthsToCheck
	if months < 1 { months = 1 }

	allAvails := []parser.Availability{}
	for i := 0; i < months; i++ {
		var targetDate time.Time
		if i == 0 {
			targetDate = startDate
		} else {
			targetMonth := startDate.AddDate(0, i, 0)
			targetDate = time.Date(targetMonth.Year(), targetMonth.Month(), 1, 0, 0, 0, 0, targetMonth.Location())
		}
		
		dateStr := targetDate.Format("2006-01-02")
		a.log("Scanning %s - Month %d...", site.Name, i+1)

		avails, _, err := scraperInst.FetchForDateWithBody(ctx, dateStr)
		if err != nil {
			a.log("Fetch Error [%s]: %v", dateStr, err)
			continue
		}
		if len(avails) == 0 {
			a.log("No availabilities found for %s", dateStr)
		}
	allAvails = append(allAvails, avails...)
		
		// Internal jitter between months to look more human
		if i < months-1 && a.config.RequestJitter > 0 {
			jitter := time.Duration(rand.Int63n(int64(a.config.RequestJitter)))
			select {
			case <-time.After(jitter):
			case <-ctx.Done():
				return false, ctx.Err()
			}
		}
	}

	a.log("Total availabilities found: %d", len(allAvails))
	if len(allAvails) == 0 {
		return false, nil
	}

	// Filter for previously unseen dates
	newAvails := []parser.Availability{}
	for _, av := range allAvails {
		fullDate, err := extractDateFromURL(av.BookingURL)
		if err != nil { continue }
		
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

	if mode == "alert" {
		notifyAvails := make([]notifier.AvailabilityWithLink, len(newAvails))
		for i, av := range newAvails {
			notifyAvails[i] = notifier.AvailabilityWithLink{
				Date:       av.Date,
				BookingURL: ensureAbsoluteURL(av.BookingURL, site.BaseURL),
			}
		}
		title, msg, actions := notifier.BuildNotification(notifyAvails, site.Name, museum.Name)
		err := ntfy.SendNotification(title, msg, notifier.PriorityHigh, actions)
		if err != nil {
			a.log("Alert Failed: %v", err)
		} else {
			a.log("ALERT SENT for %d slots", len(newAvails))
		}
		return true, nil 
	} else if mode == "booking" {
		bookerInst := booker.NewBooker(client, site, museum, a.stateManager, a.logger)
		for _, av := range newAvails {
			for _, prefDay := range a.config.PreferredDays {
				if isDayOfWeek(av.Date, prefDay) {
					a.log("PREFERENCE MATCH: Attempting to book %s", av.Date)
					if err := bookerInst.Book(ctx, av); err != nil {
						a.log("BOOKING FAILED: %v", err)
						_ = ntfy.SendNotification(
							fmt.Sprintf("%s - %s - Booking Failed", site.Name, museum.Name),
							fmt.Sprintf("Failed to book %s: %v", av.Date, err),
							notifier.PriorityHigh,
							nil,
						)
						return false, nil
					} else {
						a.log("SUCCESS! Appointment booked for %s", av.Date)
						_ = ntfy.SendNotification(
							fmt.Sprintf("%s - %s - Booking SUCCESS!", site.Name, museum.Name),
							fmt.Sprintf("Successfully booked %s", av.Date),
							notifier.PriorityUrgent,
							nil,
						)
						return true, nil
					}
				}
			}
		}
		a.log("No available slots matched your preferred days.")
		return false, nil
	}
	return false, nil
}

func (a *Agent) preWarm(client *httpclient.Client, baseURL string) error {
	req, err := http.NewRequest("GET", baseURL, nil)
	if err != nil { return err }
	resp, err := client.Do(req)
	if err == nil { resp.Body.Close() }
	return err
}

func (a *Agent) log(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	a.logger.Info(msg)
	select {
	case a.logCh <- msg:
	default:
	}
}

func (a *Agent) removeRun(runID string) {
	newRuns := []config.ScheduledRun{}
	for _, r := range a.config.ScheduledRuns {
		if r.ID != runID {
			newRuns = append(newRuns, r)
		}
	}
	a.config.ScheduledRuns = newRuns
	_ = config.SaveConfig("configs/config.yaml", a.config)
}

func ensureAbsoluteURL(rawURL, baseURL string) string {
	if strings.HasPrefix(rawURL, "http") {
		return rawURL
	}
	return baseURL + "/" + strings.TrimPrefix(rawURL, "/")
}

func extractDateFromURL(rawURL string) (string, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return "", err
	}
	date := u.Query().Get("date")
	if date == "" {
		return "", fmt.Errorf("no date in URL")
	}
	return date, nil
}

func isDayOfWeek(dateStr, dayName string) bool {
	t, err := time.Parse("2006-01-02", dateStr)
	if err != nil { return false }
	return t.Weekday().String() == dayName
}

func waitUntil(ctx context.Context, t time.Time) error {
	now := time.Now()
	if t.Before(now) { return nil }
	delta := t.Sub(now)
	
	// Sleep for the majority of the time
	if delta > 5*time.Millisecond {
		sleepTime := delta - 5*time.Millisecond
		select {
		case <-time.After(sleepTime):
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	
	// High-precision spin for the last 5ms
	for time.Now().Before(t) {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
			// keep spinning
		}
	}
	return nil
}

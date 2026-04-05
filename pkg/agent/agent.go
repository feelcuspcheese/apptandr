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
	notifyFunc   func(title, message string)
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

func (a *Agent) SetNotifyFunc(f func(title, message string)) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.notifyFunc = f
}

func (a *Agent) LogChannel() <-chan string { return a.logCh }
func (a *Agent) IsRunning() bool           { a.mu.RLock(); defer a.mu.RUnlock(); return a.running }

func (a *Agent) Start(runID string) error {
	a.mu.Lock()
	if a.running {
		a.mu.Unlock()
		return fmt.Errorf("agent already running")
	}
	a.running = true
	a.currentRunID = runID
	a.mu.Unlock()

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

	select {
	case <-doneCh:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (a *Agent) Stop() {
	if a.cancelFunc != nil { a.cancelFunc() }
	a.wg.Wait()
}

func (a *Agent) run(ctx context.Context, run *config.ScheduledRun) {
	defer a.removeRun(run.ID)

	a.log("Agent starting for run %s, drop time: %v", run.ID, run.DropTime)

	site, ok := a.config.Sites[run.SiteKey]
	if !ok {
		a.log("Site %s not found", run.SiteKey)
		return
	}
	museum, ok := site.Museums[run.MuseumSlug]
	if !ok {
		a.log("Museum %s not found", run.MuseumSlug)
		return
	}

	headers := map[string]string{
		"Accept":           "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
		"Accept-Language": "en-US,en;q=0.9",
		"Referer":         site.BaseURL + "/passes/" + museum.Slug,
	}
	
	client, err := httpclient.NewClient(headers, 30*time.Second)
	if err != nil {
		a.log("Failed to initialize mimicry stack: %v", err)
		return
	}

	a.log("Network Identity Locked: %s", client.ChosenProfile.Name)

	endpointTemplate := fmt.Sprintf("%s%s?museum=%s&date={date}&digital=%t&physical=%t&location=%s",
		site.BaseURL, site.AvailabilityEndpoint, museum.MuseumID, site.Digital, site.Physical, site.Location)

	scraperInst := scraper.NewScraper(client, endpointTemplate, a.config.RequestJitter, a.logger)

	now := time.Now()
	preWarmTime := run.DropTime.Add(-a.config.PreWarmOffset)
	if preWarmTime.After(now) {
		select {
		case <-time.After(preWarmTime.Sub(now)):
		case <-ctx.Done():
			return
		}
	}

	a.log("Pre-warming identity session on %s...", site.BaseURL)
	if site.BaseURL != "" {
		_ = a.preWarm(client, site.BaseURL)
	}

	if err := waitUntil(ctx, run.DropTime); err != nil {
		return
	}

	deadline := time.Now().Add(a.config.CheckWindow)
	ntfy := notifier.NewNtfy(a.config.NtfyTopic)
	checksCount := 0

	for {
		if time.Now().After(deadline) {
			a.log("Strike window expired. No slots found.")
			break
		}

		stop, err := a.checkAvailability(ctx, scraperInst, ntfy, site, museum, client, run.DropTime, run.Mode)
		if err != nil {
			a.log("Execution Notice: %v", err)
		}
		if stop {
			break
		}

		checksCount++
		interval := a.config.CheckInterval
		if a.config.RequestJitter > 0 {
			interval += time.Duration(rand.Int63n(int64(a.config.RequestJitter)))
		}
		
		select {
		case <-time.After(interval):
		case <-ctx.Done():
			return
		}

		if a.config.CheckWindow > 1*time.Minute && checksCount >= a.config.RestCycleChecks {
			select {
			case <-time.After(a.config.RestCycleDuration):
				checksCount = 0
			case <-ctx.Done():
				return
			}
		}
	}
	a.log("Agent finished run %s", run.ID)
}

func (a *Agent) checkAvailability(ctx context.Context, scraperInst *scraper.Scraper, ntfy *notifier.Ntfy, site config.Site, museum config.Museum, client *httpclient.Client, dropTime time.Time, mode string) (bool, error) {
	startDate := dropTime
	months := a.config.MonthsToCheck
	if months < 1 { months = 1 }

	allAvails := []parser.Availability{}
	for i := 0; i < months; i++ {
		targetDate := startDate
		if i > 0 {
			targetMonth := startDate.AddDate(0, i, 0)
			targetDate = time.Date(targetMonth.Year(), targetMonth.Month(), 1, 0, 0, 0, 0, targetMonth.Location())
		}
		
		dateStr := targetDate.Format("2006-01-02")
		a.log("Scanning %s - Month %d (param: %s)...", site.Name, i+1, dateStr)

		avails, _, err := scraperInst.FetchForDateWithBody(ctx, dateStr)
		if err != nil {
			a.log("Fetch Warning: %v", err)
			continue
		}
		if len(avails) == 0 { a.log("No availabilities for %s", dateStr) }
		allAvails = append(allAvails, avails...)
		
		if i < months-1 && a.config.RequestJitter > 0 {
			jitter := time.Duration(rand.Int63n(int64(a.config.RequestJitter)))
			select {
			case <-time.After(jitter):
			case <-ctx.Done():
				return false, ctx.Err()
			}
		}
	}

	if len(allAvails) == 0 { return false, nil }

	newAvails := []parser.Availability{}
	for _, av := range allAvails {
		fullDate, _ := extractDateFromURL(av.BookingURL)
		if !a.stateManager.IsSeen(fullDate) {
			a.stateManager.MarkSeen(fullDate)
			av.Date = fullDate
			newAvails = append(newAvails, av)
		}
	}

	if len(newAvails) == 0 { return false, nil }

	if mode == "alert" {
		notifyAvails := make([]notifier.AvailabilityWithLink, len(newAvails))
		for i, av := range newAvails {
			notifyAvails[i] = notifier.AvailabilityWithLink{
				Date: av.Date,
				BookingURL: ensureAbsoluteURL(av.BookingURL, site.BaseURL),
			}
		}
		title, msg, actions := notifier.BuildNotification(notifyAvails, site.Name, museum.Name)
		_ = ntfy.SendNotification(title, msg, notifier.PriorityHigh, actions)
		if a.notifyFunc != nil { a.notifyFunc(title, msg) }
		a.log("Notification sent for %d dates", len(newAvails))
		return true, nil
	} else if mode == "booking" {
		bookerInst := booker.NewBooker(client, site, museum, a.stateManager, a.logger)
		for _, av := range newAvails {
			for _, prefDay := range a.config.PreferredDays {
				if isDayOfWeek(av.Date, prefDay) {
					a.log("Attempting to book %s", av.Date)
					if err := bookerInst.Book(ctx, av); err != nil {
						errMsg := err.Error()
						
						/**
						 * HANDLING FATAL FAILURES
						 * If the booker returns a fatal error, we notify and return stop=true
						 */
						if strings.Contains(errMsg, "FATAL") {
							a.log("CRITICAL FAILURE DETECTED: %v. Shutting down.", errMsg)
							title := fmt.Sprintf("%s - %s - AGENT STOPPED", site.Name, museum.Name)
							_ = ntfy.SendNotification(title, errMsg, notifier.PriorityHigh, nil)
							if a.notifyFunc != nil { a.notifyFunc(title, errMsg) }
							return true, err // stop=true, error=err
						}

						// Transient error: Log and keep checking other availabilities
						a.log("Booking error for %s: %v", av.Date, err)
						continue
					}
					
					// Success path
					successTitle := fmt.Sprintf("%s - Booking SUCCESS", site.Name)
					successMsg := "Booked " + av.Date
					_ = ntfy.SendNotification(successTitle, successMsg, notifier.PriorityUrgent, nil)
					if a.notifyFunc != nil { a.notifyFunc(successTitle, successMsg) }
					return true, nil // stop=true (success)
				}
			}
		}
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
		if r.ID != runID { newRuns = append(newRuns, r) }
	}
	a.config.ScheduledRuns = newRuns
	_ = config.SaveConfig("configs/config.yaml", a.config)
}

func ensureAbsoluteURL(rawURL, baseURL string) string {
	if strings.HasPrefix(rawURL, "http") { return rawURL }
	return baseURL + "/" + strings.TrimPrefix(rawURL, "/")
}

func extractDateFromURL(rawURL string) (string, error) {
	u, err := url.Parse(rawURL)
	if err != nil { return "", err }
	date := u.Query().Get("date")
	if date == "" { return "", fmt.Errorf("no date") }
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
	if delta > 5*time.Millisecond {
		select {
		case <-time.After(delta - 5*time.Millisecond):
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	for time.Now().Before(t) {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
	}
	return nil
}

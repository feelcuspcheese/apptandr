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
func (a *Agent) GetCurrentRunID() string   { a.mu.RLock(); defer a.mu.RUnlock(); return a.currentRunID }

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
		wait := preWarmTime.Sub(now)
		a.log("Waiting %v until pre-warm time", wait)
		select {
		case <-time.After(wait):
		case <-ctx.Done():
			a.log("Agent stopped before pre-warm")
			return
		}
	}

	a.log("Pre-warming identity session on %s...", site.BaseURL)
	if site.BaseURL != "" {
		if err := a.preWarm(client, site.BaseURL); err != nil {
			a.log("Pre-warm failed: %v", err)
		} else {
			a.log("Pre-warm completed")
		}
	}

	a.log("Waiting until drop time with high precision")
	if err := waitUntil(ctx, run.DropTime); err != nil {
		a.log("Agent stopped before drop time")
		return
	}

	deadline := time.Now().Add(a.config.CheckWindow)
	a.log("Check window started, deadline: %v", deadline)

	ntfy := notifier.NewNtfy(a.config.NtfyTopic)
	restEnabled := a.config.CheckWindow > 1*time.Minute
	checksCount := 0

	for {
		if time.Now().After(deadline) {
			a.log("Check window expired")
			break
		}

		stop, err := a.checkAvailability(ctx, scraperInst, ntfy, site, museum, client, run.DropTime, run.Mode)
		if err != nil { a.log("Check error: %v", err) }
		if stop {
			if run.Mode == "alert" { a.log("Alert sent, stopping checks") } else { a.log("Booking successful, stopping checks") }
			break
		}

		if restEnabled { checksCount++ }

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

		if restEnabled && checksCount >= a.config.RestCycleChecks {
			a.log("Resting for %v (human mimic)", a.config.RestCycleDuration)
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
		var targetDate time.Time
		if i == 0 { targetDate = startDate } else {
			targetMonth := startDate.AddDate(0, i, 0)
			targetDate = time.Date(targetMonth.Year(), targetMonth.Month(), 1, 0, 0, 0, 0, targetMonth.Location())
		}
		
		dateStr := targetDate.Format("2006-01-02")
		a.log("Scanning %s - Month %d (param: %s)...", site.Name, i+1, dateStr)

		avails, _, err := scraperInst.FetchForDateWithBody(ctx, dateStr)
		if err != nil {
			a.log("Error fetching %s: %v", dateStr, err)
			continue
		}
		if len(avails) == 0 { a.log("No availabilities found for %s", dateStr) }
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

	a.log("Total availabilities found: %d", len(allAvails))
	if len(allAvails) == 0 { return false, nil }

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

	if len(newAvails) == 0 { a.log("No new availabilities"); return false, nil }

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
						failTitle := fmt.Sprintf("%s - Booking Failed", site.Name)
						a.log("Booking failed for %s: %v", av.Date, err)
						_ = ntfy.SendNotification(failTitle, err.Error(), notifier.PriorityHigh, nil)
						if a.notifyFunc != nil { a.notifyFunc(failTitle, err.Error()) }
						return false, nil
					} else {
						successTitle := fmt.Sprintf("%s - Booking SUCCESS!", site.Name)
						a.log("Booking successful for %s", av.Date)
						_ = ntfy.SendNotification(successTitle, "Booked "+av.Date, notifier.PriorityUrgent, nil)
						if a.notifyFunc != nil { a.notifyFunc(successTitle, "Booked "+av.Date) }
						return true, nil
					}
				}
			}
		}
		a.log("No preferred day found among availabilities")
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

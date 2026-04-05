package mobile

import (
	"encoding/json"
	"strings"
	"sync"
	"time"

	"agent/pkg/agent"
	"agent/pkg/config"

	"github.com/sirupsen/logrus"

	// CRITICAL: This keeps the bind tool dependencies in go.mod
	_ "golang.org/x/mobile/bind"
)

// MobileAgent matches TECHNICAL_SPEC.md Section 8.
// This class is exposed to Kotlin via gomobile.
type MobileAgent struct {
	agentInst      *agent.Agent
	logCallback    LogCallback
	statusCallback StatusCallback
	notifyCallback NotifyCallback // Bridge for Native Android Alerts
	mu             sync.RWMutex
	mobileRunning  bool
}

// LogCallback matches the Android LogManager interface
type LogCallback interface {
	Log(message string)
}

// StatusCallback allows the UI to see "running" or "stopped" states
type StatusCallback interface {
	OnStatus(status string)
}

// NotifyCallback allows Go to trigger high-priority Android System Notifications
type NotifyCallback interface {
	OnNotify(title, message string)
}

func NewMobileAgent() *MobileAgent {
	return &MobileAgent{}
}

/**
 * Start initializes the Go Agent from the Android Foreground Service.
 * It handles the transition from JSON configuration to a running Sticky-Identity agent.
 */
func (m *MobileAgent) Start(configJSON string) bool {
	m.mu.Lock()
	if m.mobileRunning {
		m.mu.Unlock()
		m.fireLog("WARN", "Agent already running")
		return false
	}

	// Intermediate struct to handle duration strings (e.g., "0.81s") correctly
	type AppConfigStr struct {
		Sites             map[string]config.Site `json:"sites"`
		ActiveSite        string                 `json:"active_site"`
		Mode              string                 `json:"mode"`
		PreferredDays     []string               `json:"preferred_days"`
		StrikeTime        string                 `json:"strike_time"`
		CheckWindow       string                 `json:"check_window"`
		CheckInterval     string                 `json:"check_interval"`
		PreWarmOffset     string                 `json:"pre_warm_offset"`
		NtfyTopic         string                 `json:"ntfy_topic"`
		MaxWorkers        int                    `json:"max_workers"`
		RequestJitter     string                 `json:"request_jitter"`
		MonthsToCheck     int                    `json:"months_to_check"`
		ScheduledRuns     []config.ScheduledRun  `json:"scheduled_runs"`
		RestCycleChecks   int                    `json:"rest_cycle_checks"`
		RestCycleDuration string                 `json:"rest_cycle_duration"`
	}

	var req struct {
		SiteKey    string       `json:"siteKey"`
		MuseumSlug string       `json:"museumSlug"`
		DropTime   string       `json:"dropTime"`
		Mode       string       `json:"mode"`
		Timezone   string       `json:"timezone"`
		FullConfig AppConfigStr `json:"fullConfig"`
	}

	if err := json.Unmarshal([]byte(configJSON), &req); err != nil {
		m.mu.Unlock()
		m.fireLog("ERROR", "Go Bind: Unmarshal Error")
		return false
	}

	// Helper to ensure units are present, preventing time.ParseDuration failures
	parseDur := func(s string, def time.Duration) time.Duration {
		if s == "" {
			return def
		}
		d, err := time.ParseDuration(s)
		if err != nil {
			// Fallback: If missing unit, append "s" and try again
			d2, err2 := time.ParseDuration(s + "s")
			if err2 == nil {
				return d2
			}
			return def
		}
		return d
	}

	cfg := config.AppConfig{
		Sites:             req.FullConfig.Sites,
		ActiveSite:        req.FullConfig.ActiveSite,
		Mode:              req.FullConfig.Mode,
		PreferredDays:     req.FullConfig.PreferredDays,
		StrikeTime:        req.FullConfig.StrikeTime,
		CheckWindow:       parseDur(req.FullConfig.CheckWindow, 60*time.Second),
		CheckInterval:     parseDur(req.FullConfig.CheckInterval, 810*time.Millisecond),
		PreWarmOffset:     parseDur(req.FullConfig.PreWarmOffset, 30*time.Second),
		NtfyTopic:         req.FullConfig.NtfyTopic,
		MaxWorkers:        req.FullConfig.MaxWorkers,
		RequestJitter:     parseDur(req.FullConfig.RequestJitter, 180*time.Millisecond),
		MonthsToCheck:     req.FullConfig.MonthsToCheck,
		ScheduledRuns:     req.FullConfig.ScheduledRuns,
		RestCycleChecks:   req.FullConfig.RestCycleChecks,
		RestCycleDuration: parseDur(req.FullConfig.RestCycleDuration, 3*time.Second),
	}

	// Standardize DropTime
	dropTime, err := time.Parse(time.RFC3339, req.DropTime)
	if err != nil {
		m.mu.Unlock()
		m.fireLog("ERROR", "Go Bind: Invalid dropTime format")
		return false
	}
	runID := "run-" + time.Now().Format("150405")

	cfg.ScheduledRuns = []config.ScheduledRun{{
		ID:         runID,
		SiteKey:    req.SiteKey,
		MuseumSlug: req.MuseumSlug,
		DropTime:   dropTime,
		Mode:       req.Mode,
		Timezone:   req.Timezone,
	}}

	// Initialize Logger with the Android Hook
	logger := logrus.New()
	logger.AddHook(&androidHook{m: m})

	m.mobileRunning = true
	m.agentInst = agent.NewAgent(&cfg, logger)

	// Wire the Notification bridge into the Agent
	m.agentInst.SetNotifyFunc(func(title, message string) {
		m.fireNotify(title, message)
	})

	m.mu.Unlock()

	// Launch Agent in a dedicated goroutine
	go func() {
		m.fireStatus("running")
		_ = m.agentInst.Start(runID)
		m.fireStatus("stopped")

		m.mu.Lock()
		m.mobileRunning = false
		m.mu.Unlock()
	}()

	return true
}

func (m *MobileAgent) Stop() {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.agentInst != nil {
		m.agentInst.Stop()
	}
}

func (m *MobileAgent) IsRunning() bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.mobileRunning
}

// Callback Registration Methods

func (m *MobileAgent) SetLogCallback(cb LogCallback) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.logCallback = cb
}

func (m *MobileAgent) SetStatusCallback(cb StatusCallback) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.statusCallback = cb
}

func (m *MobileAgent) SetNotifyCallback(cb NotifyCallback) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.notifyCallback = cb
}

// Internal Event Firers

func (m *MobileAgent) fireLog(level, msg string) {
	m.mu.RLock()
	cb := m.logCallback
	m.mu.RUnlock()
	if cb != nil {
		out, _ := json.Marshal(map[string]interface{}{
			"timestamp": time.Now().UnixMilli(),
			"level":     strings.ToUpper(level),
			"message":   msg,
		})
		cb.Log(string(out))
	}
}

func (m *MobileAgent) fireStatus(status string) {
	m.mu.RLock()
	cb := m.statusCallback
	m.mu.RUnlock()
	if cb != nil {
		cb.OnStatus(status)
	}
}

func (m *MobileAgent) fireNotify(title, message string) {
	m.mu.RLock()
	cb := m.notifyCallback
	m.mu.RUnlock()
	if cb != nil {
		cb.OnNotify(title, message)
	}
}

// androidHook bridges logrus entries into the fireLog method
type androidHook struct{ m *MobileAgent }

func (h *androidHook) Levels() []logrus.Level { return logrus.AllLevels }
func (h *androidHook) Fire(e *logrus.Entry) error {
	h.m.fireLog(e.Level.String(), e.Message)
	return nil
}

// Package mobile provides a gomobile-compatible interface for the Android app.
package mobile

import (
	"agent/pkg/agent"
	"agent/pkg/config"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/sirupsen/logrus"
)

// MobileAgent is the main class exposed to Android per TECHNICAL_SPEC.md section 8.
// It provides instance methods: start(), stop(), isRunning(), setLogCallback(), setStatusCallback().
type MobileAgent struct {
	mu         sync.Mutex
	ag         *agent.Agent
	logger     *logrus.Logger
	logCb      func(string)
	statusCb   func(string)
}

// NewMobileAgent creates a new MobileAgent instance.
func NewMobileAgent() *MobileAgent {
	return &MobileAgent{}
}

// SetLogCallback sets a callback function to receive log messages from Go agent.
// The callback receives JSON strings like: {"timestamp":123456,"level":"INFO","message":"Pre-warming..."}
func (m *MobileAgent) SetLogCallback(fn func(string)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.logCb = fn
}

// SetStatusCallback sets a callback function to receive status updates.
func (m *MobileAgent) SetStatusCallback(fn func(string)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.statusCb = fn
}

// Start starts the booking agent with the given JSON configuration.
// Returns true if started successfully, false otherwise.
func (m *MobileAgent) Start(configJSON string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.ag != nil && m.ag.IsRunning() {
		m.sendLog("Agent already running")
		return false
	}

	var cfg config.AppConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		m.sendLog(fmt.Sprintf("Failed to parse config JSON: %v", err))
		return false
	}

	logger := logrus.New()
	logger.SetLevel(logrus.InfoLevel)
	logger.SetFormatter(&logrus.TextFormatter{
		FullTimestamp: true,
	})

	// Custom hook to send logs to callback
	logger.AddHook(&LogCallbackHook{callback: m.sendLog})

	a := agent.NewAgent(&cfg, logger)
	m.ag = a

	go func() {
		// Run the agent for the first scheduled run
		if len(cfg.ScheduledRuns) == 0 {
			m.sendLog("No scheduled runs found in config")
			return
		}
		runID := cfg.ScheduledRuns[0].ID
		if err := a.Start(runID); err != nil {
			m.sendLog(fmt.Sprintf("Agent error: %v", err))
		} else {
			m.sendLog("Agent finished successfully")
		}
		// Clear agent reference after completion
		m.mu.Lock()
		m.ag = nil
		m.mu.Unlock()
		m.sendStatus("stopped")
	}()

	m.sendStatus("running")
	return true
}

// Stop stops the currently running agent.
func (m *MobileAgent) Stop() {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.ag != nil {
		m.ag.Stop()
		m.ag = nil
		m.sendStatus("stopped")
		m.sendLog("Agent stopped by user")
	}
}

// IsRunning returns true if the agent is currently running.
func (m *MobileAgent) IsRunning() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.ag != nil && m.ag.IsRunning()
}

// Internal helpers
func (m *MobileAgent) sendLog(msg string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.logCb != nil {
		m.logCb(msg)
	}
}

func (m *MobileAgent) sendStatus(status string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.statusCb != nil {
		m.statusCb(status)
	}
}

// LogCallbackHook is a logrus hook that sends log entries to the callback.
type LogCallbackHook struct {
	callback func(string)
}

func (h *LogCallbackHook) Levels() []logrus.Level {
	return []logrus.Level{
		logrus.PanicLevel,
		logrus.FatalLevel,
		logrus.ErrorLevel,
		logrus.WarnLevel,
		logrus.InfoLevel,
		logrus.DebugLevel,
	}
}

func (h *LogCallbackHook) Fire(entry *logrus.Entry) error {
	// Format log entry as JSON per TECHNICAL_SPEC.md section 8
	timestamp := entry.Time.UnixMilli()
	level := entry.Level.String()
	message := entry.Message

	jsonLog := fmt.Sprintf(`{"timestamp":%d,"level":"%s","message":"%s"}`, timestamp, level, message)

	if h.callback != nil {
		h.callback(jsonLog)
	}
	return nil
}

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

var (
	currentAgent *agent.Agent
	mu           sync.Mutex
	logCallback  func(string)
	statusCallback func(string)
)

// SetLogCallback sets a callback function to receive log messages from Go agent.
// The callback receives JSON strings like: {"timestamp":123456,"level":"INFO","message":"Pre-warming..."}
func SetLogCallback(fn func(string)) {
	mu.Lock()
	defer mu.Unlock()
	logCallback = fn
}

// SetStatusCallback sets a callback function to receive status updates.
func SetStatusCallback(fn func(string)) {
	mu.Lock()
	defer mu.Unlock()
	statusCallback = fn
}

// Start starts the booking agent with the given JSON configuration.
// Returns true if started successfully, false otherwise.
func Start(configJSON string) bool {
	mu.Lock()
	defer mu.Unlock()

	if currentAgent != nil && currentAgent.IsRunning() {
		sendLog("Agent already running")
		return false
	}

	var cfg config.AppConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		sendLog(fmt.Sprintf("Failed to parse config JSON: %v", err))
		return false
	}

	logger := logrus.New()
	logger.SetLevel(logrus.InfoLevel)
	logger.SetFormatter(&logrus.TextFormatter{
		FullTimestamp: true,
	})

	// Custom hook to send logs to callback
	logger.AddHook(&LogCallbackHook{callback: sendLog})

	a := agent.NewAgent(&cfg, logger)
	currentAgent = a

	go func() {
		// Run the agent for the first scheduled run
		if len(cfg.ScheduledRuns) == 0 {
			sendLog("No scheduled runs found in config")
			return
		}
		runID := cfg.ScheduledRuns[0].ID
		if err := a.Start(runID); err != nil {
			sendLog(fmt.Sprintf("Agent error: %v", err))
		} else {
			sendLog("Agent finished successfully")
		}
	}()

	sendStatus("running")
	return true
}

// Stop stops the currently running agent.
func Stop() {
	mu.Lock()
	defer mu.Unlock()

	if currentAgent != nil {
		currentAgent.Stop()
		currentAgent = nil
		sendStatus("stopped")
		sendLog("Agent stopped by user")
	}
}

// IsRunning returns true if the agent is currently running.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return currentAgent != nil && currentAgent.IsRunning()
}

func sendLog(msg string) {
	mu.Lock()
	defer mu.Unlock()
	if logCallback != nil {
		logCallback(msg)
	}
}

func sendStatus(status string) {
	mu.Lock()
	defer mu.Unlock()
	if statusCallback != nil {
		statusCallback(status)
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

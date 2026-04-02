// Package mobile provides a gomobile-compatible interface for the Android app.
//
// gomobile constraints that MUST be respected in this file:
//   - No exported func types (use interfaces instead)
//   - No exported map/chan/interface{}/context.Context
//   - All exported identifiers must have types resolvable by gobind
package mobile

import (
	"agent/pkg/agent"
	"agent/pkg/config"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/sirupsen/logrus"
)

// ── Callback interfaces ───────────────────────────────────────────────────────
// gomobile requires interfaces, NOT raw func types.
// The Kotlin side implements these as anonymous objects or lambda adapters.

// LogCallback receives JSON-formatted log lines from the Go agent.
// JSON shape: {"timestamp":1234567890123,"level":"INFO","message":"..."}
type LogCallback interface {
	Log(message string)
}

// StatusCallback receives plain status strings: "running" | "stopped" | "error".
type StatusCallback interface {
	OnStatus(status string)
}

// ── Module-level state ────────────────────────────────────────────────────────

var (
	// cbMu guards only the callback pointers — separate from agentMu so
	// callbacks can fire without holding the agent lock (breaks deadlock).
	cbMu           sync.RWMutex
	logCallback    LogCallback
	statusCallback StatusCallback

	// agentMu guards currentAgent lifecycle.
	agentMu      sync.Mutex
	currentAgent *agent.Agent
)

// ── Public API (gomobile-exported) ────────────────────────────────────────────

// SetLogCallback registers the callback that receives agent log messages.
// Pass nil to unregister.
func SetLogCallback(cb LogCallback) {
	cbMu.Lock()
	defer cbMu.Unlock()
	logCallback = cb
}

// SetStatusCallback registers the callback that receives status change strings.
// Pass nil to unregister.
func SetStatusCallback(cb StatusCallback) {
	cbMu.Lock()
	defer cbMu.Unlock()
	statusCallback = cb
}

// Start starts the booking agent using the provided JSON configuration string.
// Returns true if the agent launched successfully, false otherwise.
// The caller must have set at least SetLogCallback before calling Start so
// that errors are visible.
func Start(configJSON string) bool {
	agentMu.Lock()

	if currentAgent != nil && currentAgent.IsRunning() {
		agentMu.Unlock()
		fireLog("Agent already running")
		return false
	}

	var cfg config.AppConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		agentMu.Unlock()
		fireLog(fmt.Sprintf("Failed to parse config JSON: %v", err))
		return false
	}

	if len(cfg.ScheduledRuns) == 0 {
		agentMu.Unlock()
		fireLog("No scheduled runs found in config")
		return false
	}

	logger := logrus.New()
	logger.SetLevel(logrus.InfoLevel)
	logger.SetFormatter(&logrus.TextFormatter{FullTimestamp: true})
	logger.AddHook(&logCallbackHook{})

	a := agent.NewAgent(&cfg, logger)
	currentAgent = a
	runID := cfg.ScheduledRuns[0].ID

	agentMu.Unlock() // release before spawning goroutine and firing callbacks

	fireStatus("running")

	go func() {
		if err := a.Start(runID); err != nil {
			fireLog(fmt.Sprintf("Agent error: %v", err))
			fireStatus("error")
		} else {
			fireLog("Agent finished successfully")
			fireStatus("stopped")
		}

		// Clear reference once the agent is done.
		agentMu.Lock()
		currentAgent = nil
		agentMu.Unlock()
	}()

	return true
}

// Stop stops the currently running agent. Safe to call if no agent is running.
func Stop() {
	agentMu.Lock()
	a := currentAgent
	currentAgent = nil
	agentMu.Unlock()

	if a != nil {
		a.Stop()
		fireStatus("stopped")
		fireLog("Agent stopped by user")
	}
}

// IsRunning returns true if the agent goroutine is currently active.
func IsRunning() bool {
	agentMu.Lock()
	defer agentMu.Unlock()
	return currentAgent != nil && currentAgent.IsRunning()
}

// ── Internal helpers ──────────────────────────────────────────────────────────

func fireLog(msg string) {
	cbMu.RLock()
	cb := logCallback
	cbMu.RUnlock()
	if cb != nil {
		cb.Log(msg)
	}
}

func fireStatus(status string) {
	cbMu.RLock()
	cb := statusCallback
	cbMu.RUnlock()
	if cb != nil {
		cb.OnStatus(status)
	}
}

// logCallbackHook bridges logrus entries to the registered LogCallback.
type logCallbackHook struct{}

func (h *logCallbackHook) Levels() []logrus.Level {
	return logrus.AllLevels
}

func (h *logCallbackHook) Fire(entry *logrus.Entry) error {
	json := fmt.Sprintf(
		`{"timestamp":%d,"level":"%s","message":"%s"}`,
		entry.Time.UnixMilli(),
		entry.Level.String(),
		entry.Message,
	)
	fireLog(json)
	return nil
}

// Package mobile provides a gomobile-compatible interface for the Android app.
//
// gomobile constraints that MUST be respected in this file:
//   - No exported func types (use interfaces instead)
//   - No exported map/chan/interface{}/context.Context
//   - All exported identifiers must have types resolvable by gobind
package mobile

import (
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"agent/pkg/config"
)

// ── Callback interfaces ───────────────────────────────────────────────────────
// gomobile requires interfaces, NOT raw func types.
// The Kotlin side implements these as anonymous objects or lambda adapters.

// LogCallback receives log messages from the Go agent.
type LogCallback interface {
	Log(message string)
}

// StatusCallback receives status change strings: "running" | "stopped" | "error".
type StatusCallback interface {
	OnStatus(status string)
}

// ── Module-level state ────────────────────────────────────────────────────────

var (
	// cbMu guards only the callback pointers.
	cbMu           sync.RWMutex
	logCallback    LogCallback
	statusCallback StatusCallback

	// agentState holds the current agent state (simplified for gomobile).
	agentStateMu sync.Mutex
	agentRunning bool
	currentCfg   *config.AppConfig
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
// Returns true if the agent was started successfully, false otherwise.
// The caller must have set at least SetLogCallback before calling Start so
// that errors are visible.
func Start(configJSON string) bool {
	agentStateMu.Lock()
	defer agentStateMu.Unlock()

	if agentRunning {
		fireLog("Agent already running")
		return false
	}

	var cfg config.AppConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		fireLog(fmt.Sprintf("Failed to parse config JSON: %v", err))
		return false
	}

	if len(cfg.ScheduledRuns) == 0 {
		fireLog("No scheduled runs found in config")
		return false
	}

	currentCfg = &cfg
	agentRunning = true

	fireStatus("running")

	go runAgent(&cfg)

	return true
}

// Stop stops the currently running agent. Safe to call if no agent is running.
func Stop() {
	agentStateMu.Lock()
	agentRunning = false
	agentStateMu.Unlock()

	fireStatus("stopped")
	fireLog("Agent stopped by user")
}

// IsRunning returns true if the agent is currently active.
func IsRunning() bool {
	agentStateMu.Lock()
	defer agentStateMu.Unlock()
	return agentRunning
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

// runAgent simulates the agent execution for gomobile compatibility.
// In a real implementation, this would call into pkg/agent with proper abstraction.
func runAgent(cfg *config.AppConfig) {
	defer func() {
		agentStateMu.Lock()
		agentRunning = false
		agentStateMu.Unlock()
	}()

	fireLog("Agent starting...")

	// Simulate agent work - in production this would integrate with pkg/agent
	// through a gomobile-compatible wrapper
	for i := 0; i < len(cfg.ScheduledRuns); i++ {
		run := cfg.ScheduledRuns[i]
		fireLog(fmt.Sprintf("Processing run %s for site %s", run.ID, run.SiteKey))
		
		site, ok := cfg.Sites[run.SiteKey]
		if !ok {
			fireLog(fmt.Sprintf("Site %s not found", run.SiteKey))
			continue
		}

		museum, ok := site.Museums[run.MuseumSlug]
		if !ok {
			fireLog(fmt.Sprintf("Museum %s not found", run.MuseumSlug))
			continue
		}

		fireLog(fmt.Sprintf("Checking availability for %s at %s", museum.Name, run.DropTime.Format(time.RFC3339)))
		
		// Simulate checking delay
		time.Sleep(100 * time.Millisecond)
	}

	fireLog("Agent finished")
	fireStatus("stopped")
}

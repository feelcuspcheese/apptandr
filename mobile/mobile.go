package mobile

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"agent/pkg/agent"
	"agent/pkg/config"

	"github.com/sirupsen/logrus"
)

// MobileAgent matches TECHNICAL_SPEC.md Section 8
// gomobile will generate a class named "MobileAgent"
type MobileAgent struct {
	agentInst      *agent.Agent
	logCallback    LogCallback
	statusCallback StatusCallback
	mu             sync.RWMutex
}

// LogCallback matches setLogCallback((String) -> Unit)
type LogCallback interface {
	Log(message string)
}

// StatusCallback matches setStatusCallback((String) -> Unit)
type StatusCallback interface {
	OnStatus(status string)
}

func NewMobileAgent() *MobileAgent {
	return &MobileAgent{}
}

// Start matches spec: returns true if started successfully, false otherwise.
func (m *MobileAgent) Start(configJSON string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.agentInst != nil && m.agentInst.IsRunning() {
		m.fireLog("WARN", "Agent already running")
		return false
	}

	var req struct {
		SiteKey    string           `json:"siteKey"`
		MuseumSlug string           `json:"museumSlug"`
		DropTime   string           `json:"dropTime"`
		Mode       string           `json:"mode"`
		Timezone   string           `json:"timezone"`
		FullConfig config.AppConfig `json:"fullConfig"`
	}

	if err := json.Unmarshal([]byte(configJSON), &req); err != nil {
		m.fireLog("ERROR", fmt.Sprintf("Go Bind Unmarshal Error: %v", err))
		return false
	}

	cfg := req.FullConfig
	dropTime, err := time.Parse(time.RFC3339, req.DropTime)
	if err != nil {
		m.fireLog("ERROR", fmt.Sprintf("Invalid dropTime format: %v", err))
		return false
	}
	runID := "run-" + time.Now().Format("150405")

	cfg.ScheduledRuns = []config.ScheduledRun{{
		ID:         runID,
		SiteKey:    req.SiteKey,
		MuseumSlug: req.MuseumSlug,
		DropTime:   dropTime,
		Mode:       req.Mode,
		Timezone:   req.Timezone, // preserve timezone per spec
	}}

	logger := logrus.New()
	logger.AddHook(&androidHook{m: m})

	m.agentInst = agent.NewAgent(&cfg, logger)

	go func() {
		m.fireStatus("running")
		_ = m.agentInst.Start(runID)
		m.fireStatus("stopped")
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
	return m.agentInst != nil && m.agentInst.IsRunning()
}

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

// fireLog sends a JSON log entry with timestamp (per spec)
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

type androidHook struct{ m *MobileAgent }

func (h *androidHook) Levels() []logrus.Level { return logrus.AllLevels }
func (h *androidHook) Fire(e *logrus.Entry) error {
	h.m.fireLog(e.Level.String(), e.Message)
	return nil
}

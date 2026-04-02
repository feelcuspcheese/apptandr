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

	// CRITICAL: This keeps the bind tool dependencies in go.mod
	_ "golang.org/x/mobile/bind"
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

// Start matches spec Section 8
func (m *MobileAgent) Start(configJSON string) {
	m.mu.Lock()
	if m.agentInst != nil && m.agentInst.IsRunning() {
		m.mu.Unlock()
		return
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
		m.mu.Unlock()
		m.fireLog("ERROR", fmt.Sprintf("Go Bind Unmarshal Error: %v", err))
		return
	}

	cfg := req.FullConfig
	dropTime, _ := time.Parse(time.RFC3339, req.DropTime)
	runID := "run-" + time.Now().Format("150405")
	
	cfg.ScheduledRuns = []config.ScheduledRun{{
		ID:         runID,
		SiteKey:    req.SiteKey,
		MuseumSlug: req.MuseumSlug,
		DropTime:   dropTime,
		Mode:       req.Mode,
	}}

	logger := logrus.New()
	logger.AddHook(&androidHook{m: m})

	m.agentInst = agent.NewAgent(&cfg, logger)
	m.mu.Unlock()

	go func() {
		m.fireStatus("running")
		_ = m.agentInst.Start(runID)
		m.fireStatus("stopped")
	}()
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
	if m.agentInst == nil {
		return false
	}
	return m.agentInst.IsRunning()
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

// Internal formatting for Android LogsScreen
func (m *MobileAgent) fireLog(level, msg string) {
	m.mu.RLock()
	cb := m.logCallback
	m.mu.RUnlock()
	if cb != nil {
		out, _ := json.Marshal(map[string]string{
			"level":   strings.ToUpper(level),
			"message": msg,
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

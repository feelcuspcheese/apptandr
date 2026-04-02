package mobile

import (
	"agent/pkg/agent"
	"agent/pkg/config"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/sirupsen/logrus"
)

// LogCallback matches the Kotlin interface for receiving JSON logs.
type LogCallback interface {
	Log(message string)
}

// StatusCallback matches the Kotlin interface for agent status updates.
type StatusCallback interface {
	OnStatus(status string)
}

// MobileAgent is the exported struct that gomobile binds to a Java class.
type MobileAgent struct {
	agent          *agent.Agent
	logCallback    LogCallback
	statusCallback StatusCallback
	mu             sync.RWMutex
}

// agentRequest matches the JSON wrapper sent by ConfigManager.kt.
type agentRequest struct {
	SiteKey    string           `json:"siteKey"`
	MuseumSlug string           `json:"museumSlug"`
	DropTime   string           `json:"dropTime"`
	Mode       string           `json:"mode"`
	Timezone   string           `json:"timezone"`
	FullConfig config.AppConfig `json:"fullConfig"`
}

func NewMobileAgent() *MobileAgent {
	return &MobileAgent{}
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

func (m *MobileAgent) Start(configJSON string) {
	m.mu.Lock()
	if m.agent != nil && m.agent.IsRunning() {
		m.mu.Unlock()
		m.fireLog("WARN", "Agent is already running")
		return
	}

	var req agentRequest
	if err := json.Unmarshal([]byte(configJSON), &req); err != nil {
		m.mu.Unlock()
		m.fireLog("ERROR", fmt.Sprintf("Failed to parse config: %v", err))
		return
	}

	// Setup internal configuration from the mobile request
	cfg := req.FullConfig
	dropTime, _ := time.Parse(time.RFC3339, req.DropTime)
	runID := "mobile-" + time.Now().Format("150405")
	
	// Ensure the agent has the specific run in its queue
	run := config.ScheduledRun{
		ID:         runID,
		SiteKey:    req.SiteKey,
		MuseumSlug: req.MuseumSlug,
		DropTime:   dropTime,
		Mode:       req.Mode,
	}
	cfg.ScheduledRuns = []config.ScheduledRun{run}

	logger := logrus.New()
	logger.SetLevel(logrus.InfoLevel)
	// Hook logrus into the Android callback
	logger.AddHook(&androidLogHook{m: m})

	m.agent = agent.NewAgent(&cfg, logger)
	m.mu.Unlock()

	go func() {
		m.fireStatus("running")
		if err := m.agent.Start(runID); err != nil {
			m.fireLog("ERROR", fmt.Sprintf("Agent failure: %v", err))
		}
		m.fireStatus("stopped")
	}()
}

func (m *MobileAgent) Stop() {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.agent != nil {
		m.agent.Stop()
	}
}

func (m *MobileAgent) IsRunning() bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.agent == nil {
		return false
	}
	return m.agent.IsRunning()
}

func (m *MobileAgent) fireLog(level, message string) {
	m.mu.RLock()
	cb := m.logCallback
	m.mu.RUnlock()
	if cb != nil {
		logObj := map[string]string{
			"level":   strings.ToUpper(level),
			"message": message,
		}
		bytes, _ := json.Marshal(logObj)
		cb.Log(string(bytes))
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

type androidLogHook struct {
	m *MobileAgent
}

func (h *androidLogHook) Levels() []logrus.Level { return logrus.AllLevels }
func (h *androidLogHook) Fire(entry *logrus.Entry) error {
	h.m.fireLog(entry.Level.String(), entry.Message)
	return nil
}

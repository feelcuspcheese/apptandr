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
        mobileRunning  bool // FIX (Bug 1b): Track running state independently of agentInst.IsRunning()
}

// LogCallback matches setLogCallback((String) -> Unit)
type LogCallback interface {
        Log(message string)
}

// StatusCallback matches setStatusCallback((String) -> Unit)
type StatusCallback interface {
        OnStatus(status string)
}

// intermediateConfig FIX (Bug 5): Duration fields as strings for JSON unmarshal
type intermediateConfig struct {
        ActiveSite         string   `json:"active_site"`
        Mode               string   `json:"mode"`
        StrikeTime         string   `json:"strike_time"`
        PreferredDays      []string `json:"preferred_days"`
        NtfyTopic          string   `json:"ntfy_topic"`
        CheckWindow        string   `json:"check_window"`
        CheckInterval      string   `json:"check_interval"`
        RequestJitter      string   `json:"request_jitter"`
        MonthsToCheck      int      `json:"months_to_check"`
        PreWarmOffset      string   `json:"pre_warm_offset"`
        MaxWorkers         int      `json:"max_workers"`
        RestCycleChecks    int      `json:"rest_cycle_checks"`
        RestCycleDuration  string   `json:"rest_cycle_duration"`
        Sites              map[string]interface{} `json:"sites"`
        Museums            map[string]interface{} `json:"museums"`
        Credentials        map[string]interface{} `json:"credentials"`
}

func NewMobileAgent() *MobileAgent {
        return &MobileAgent{}
}

// Start matches spec: returns true if started successfully, false otherwise.
func (m *MobileAgent) Start(configJSON string) bool {
        m.mu.Lock()

        if m.agentInst != nil && m.agentInst.IsRunning() {
                m.fireLog("WARN", "Agent already running")
                m.mu.Unlock()
                return false
        }

        var req struct {
                SiteKey      string            `json:"siteKey"`
                MuseumSlug   string            `json:"museumSlug"`
                DropTime     string            `json:"dropTime"`
                Mode         string            `json:"mode"`
                Timezone     string            `json:"timezone"`
                FullConfig   intermediateConfig `json:"fullConfig"` // FIX (Bug 5): Use intermediate config
        }

        if err := json.Unmarshal([]byte(configJSON), &req); err != nil {
                m.fireLog("ERROR", fmt.Sprintf("Go Bind Unmarshal Error: %v", err))
                m.mu.Unlock()
                return false
        }

        // FIX (Bug 5): Parse duration strings into time.Duration
        parseDuration := func(s string, defaultVal time.Duration) time.Duration {
                if s == "" {
                        return defaultVal
                }
                d, err := time.ParseDuration(s)
                if err != nil {
                        return defaultVal
                }
                return d
        }

        cfg := config.AppConfig{
                ActiveSite:         req.FullConfig.ActiveSite,
                Mode:               req.FullConfig.Mode,
                StrikeTime:         req.FullConfig.StrikeTime, // Already a string, matches AppConfig.StrikeTime type
                PreferredDays:      req.FullConfig.PreferredDays,
                NtfyTopic:          req.FullConfig.NtfyTopic,
                CheckWindow:        parseDuration(req.FullConfig.CheckWindow, 5*time.Second),
                CheckInterval:      parseDuration(req.FullConfig.CheckInterval, 100*time.Millisecond),
                RequestJitter:      parseDuration(req.FullConfig.RequestJitter, 50*time.Millisecond),
                MonthsToCheck:      req.FullConfig.MonthsToCheck,
                PreWarmOffset:      parseDuration(req.FullConfig.PreWarmOffset, 2*time.Minute),
                MaxWorkers:         req.FullConfig.MaxWorkers,
                RestCycleChecks:    req.FullConfig.RestCycleChecks,
                RestCycleDuration:  parseDuration(req.FullConfig.RestCycleDuration, 1*time.Second),
        }

        dropTime, err := time.Parse(time.RFC3339, req.DropTime)
        if err != nil {
                m.fireLog("ERROR", fmt.Sprintf("Invalid dropTime format: %v", err))
                m.mu.Unlock()
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

        // FIX (Bug 1b): Set mobileRunning to true BEFORE launching goroutine
        m.mobileRunning = true

        go func() {
                m.fireStatus("running")
                _ = m.agentInst.Start(runID)
                m.fireStatus("stopped")
                // FIX (Bug 1b): Clear mobileRunning when goroutine finishes
                m.mu.Lock()
                m.mobileRunning = false
                m.mu.Unlock()
        }()

        m.mu.Unlock()
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
        // FIX (Bug 1b): Return mobileRunning which is guaranteed true from the moment Start() returns
        return m.mobileRunning
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

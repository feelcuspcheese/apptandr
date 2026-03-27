package web

import (
    "agent/pkg/agent"
    "agent/pkg/config"
    "embed"
    "io/fs"
    "net/http"
    "time"

    "github.com/gin-gonic/gin"
    "github.com/google/uuid"
    "github.com/gorilla/websocket"
    "github.com/sirupsen/logrus"
)

//go:embed static/*
var staticFS embed.FS

type Server struct {
    router   *gin.Engine
    agent    *agent.Agent
    cfg      *config.AppConfig
    logger   *logrus.Logger
    upgrader websocket.Upgrader
    runSched *runScheduler
}

type runScheduler struct {
    server *Server
    stop   chan struct{}
}

func NewServer(cfg *config.AppConfig, logger *logrus.Logger) *Server {
    s := &Server{
        cfg:    cfg,
        logger: logger,
        upgrader: websocket.Upgrader{
            CheckOrigin: func(r *http.Request) bool { return true },
        },
    }
    s.agent = agent.NewAgent(cfg, logger)
    s.runSched = &runScheduler{server: s, stop: make(chan struct{})}
    s.setupRoutes()
    go s.runSched.start()
    return s
}

func (s *Server) setupRoutes() {
    gin.SetMode(gin.ReleaseMode)
    s.router = gin.New()
    s.router.Use(gin.Recovery())

    staticSub, err := fs.Sub(staticFS, "static")
    if err != nil {
        panic(err)
    }
    s.router.StaticFS("/static", http.FS(staticSub))

    s.router.GET("/", func(c *gin.Context) {
        data, err := staticFS.ReadFile("static/index.html")
        if err != nil {
            c.String(http.StatusInternalServerError, "Internal error")
            return
        }
        c.Data(http.StatusOK, "text/html; charset=utf-8", data)
    })

    api := s.router.Group("/api")
    {
        api.GET("/config", s.getConfig)
        api.PUT("/config/admin", s.updateAdminConfig)
        api.PUT("/config/user", s.updateUserConfig)
        api.POST("/run-now", s.runNow)
        api.POST("/schedule", s.schedule)
        api.GET("/runs", s.listRuns)
        api.DELETE("/runs/:id", s.deleteRun)
        api.GET("/logs", s.websocketLogs)
        api.POST("/stop", s.stopAgent)
        api.GET("/status", s.getStatus)
    }
}

func (s *Server) Run(addr string) error {
    return s.router.Run(addr)
}

// --- Handlers ---

func (s *Server) getConfig(c *gin.Context) {
    c.JSON(http.StatusOK, s.cfg)
}

func (s *Server) updateAdminConfig(c *gin.Context) {
    var req struct {
        SiteKey string      `json:"siteKey"`
        Site    config.Site `json:"site"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }

    existing, err := config.LoadConfig("configs/config.yaml")
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }

    existing.Sites[req.SiteKey] = req.Site

    if err := config.SaveConfig("configs/config.yaml", existing); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }

    s.cfg = existing
    s.agent = agent.NewAgent(existing, s.logger)

    c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

func (s *Server) updateUserConfig(c *gin.Context) {
    var req struct {
        ActiveSite     string          `json:"activeSite"`
        PreferredSlug  string          `json:"preferredSlug"`
        Mode           string          `json:"mode"`
        PreferredDays  []string        `json:"preferredDays"`
        StrikeTime     string          `json:"strikeTime"`
        CheckWindow    time.Duration   `json:"checkWindow"`
        CheckInterval  time.Duration   `json:"checkInterval"`
        RequestJitter  time.Duration   `json:"requestJitter"`
        MonthsToCheck  int             `json:"monthsToCheck"`
        NtfyTopic      string          `json:"ntfyTopic"`
        LoginUsername  string          `json:"loginUsername"`
        LoginPassword  string          `json:"loginPassword"`
        LoginEmail     string          `json:"loginEmail"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }

    existing, err := config.LoadConfig("configs/config.yaml")
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }

    existing.ActiveSite = req.ActiveSite
    existing.Mode = req.Mode
    existing.PreferredDays = req.PreferredDays
    existing.StrikeTime = req.StrikeTime
    existing.CheckWindow = req.CheckWindow
    existing.CheckInterval = req.CheckInterval
    existing.RequestJitter = req.RequestJitter
    existing.MonthsToCheck = req.MonthsToCheck
    existing.NtfyTopic = req.NtfyTopic

    if site, ok := existing.Sites[req.ActiveSite]; ok {
        site.PreferredSlug = req.PreferredSlug
        site.LoginForm.Username = req.LoginUsername
        site.LoginForm.Password = req.LoginPassword
        site.LoginForm.Email = req.LoginEmail
        existing.Sites[req.ActiveSite] = site
    }

    for k, site := range existing.Sites {
        if k != req.ActiveSite {
            site.LoginForm.Username = req.LoginUsername
            site.LoginForm.Password = req.LoginPassword
            site.LoginForm.Email = req.LoginEmail
            existing.Sites[k] = site
        }
    }

    if err := config.SaveConfig("configs/config.yaml", existing); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }

    s.cfg = existing
    s.agent = agent.NewAgent(existing, s.logger)

    c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

func (s *Server) runNow(c *gin.Context) {
    siteKey := s.cfg.ActiveSite
    site, ok := s.cfg.Sites[siteKey]
    if !ok {
        c.JSON(http.StatusBadRequest, gin.H{"error": "active site not found"})
        return
    }
    museumSlug := site.PreferredSlug
    if museumSlug == "" {
        for slug := range site.Museums {
            museumSlug = slug
            break
        }
    }
    if museumSlug == "" {
        c.JSON(http.StatusBadRequest, gin.H{"error": "no museum selected"})
        return
    }

    dropTime := time.Now().Add(30 * time.Second)
    runID := uuid.New().String()
    run := config.ScheduledRun{
        ID:         runID,
        SiteKey:    siteKey,
        MuseumSlug: museumSlug,
        DropTime:   dropTime,
        Mode:       s.cfg.Mode,
    }

    existing, err := config.LoadConfig("configs/config.yaml")
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    existing.ScheduledRuns = append(existing.ScheduledRuns, run)
    if err := config.SaveConfig("configs/config.yaml", existing); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    s.cfg = existing
    s.agent = agent.NewAgent(existing, s.logger)

    c.JSON(http.StatusOK, gin.H{"status": "started", "dropTime": dropTime.Format(time.RFC3339)})
}

func (s *Server) schedule(c *gin.Context) {
    var req struct {
        SiteKey     string `json:"siteKey"`
        MuseumSlug  string `json:"museumSlug"`
        DropTime    string `json:"dropTime"`
        Timezone    string `json:"timezone"`
        Mode        string `json:"mode"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }

    site, ok := s.cfg.Sites[req.SiteKey]
    if !ok {
        c.JSON(http.StatusBadRequest, gin.H{"error": "site not found"})
        return
    }
    if _, ok := site.Museums[req.MuseumSlug]; !ok {
        c.JSON(http.StatusBadRequest, gin.H{"error": "museum not found in site"})
        return
    }

    loc, err := time.LoadLocation(req.Timezone)
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid timezone"})
        return
    }
    t, err := time.ParseInLocation("2006-01-02T15:04", req.DropTime, loc)
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid datetime format, use YYYY-MM-DDTHH:MM"})
        return
    }
    if t.Before(time.Now()) {
        c.JSON(http.StatusBadRequest, gin.H{"error": "drop time must be in the future"})
        return
    }

    runID := uuid.New().String()
    run := config.ScheduledRun{
        ID:         runID,
        SiteKey:    req.SiteKey,
        MuseumSlug: req.MuseumSlug,
        DropTime:   t,
        Mode:       req.Mode,
    }

    existing, err := config.LoadConfig("configs/config.yaml")
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    existing.ScheduledRuns = append(existing.ScheduledRuns, run)
    if err := config.SaveConfig("configs/config.yaml", existing); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    s.cfg = existing
    s.agent = agent.NewAgent(existing, s.logger)

    c.JSON(http.StatusOK, gin.H{"status": "scheduled", "dropTime": t.Format(time.RFC3339), "id": runID})
}

func (s *Server) listRuns(c *gin.Context) {
    c.JSON(http.StatusOK, s.cfg.ScheduledRuns)
}

func (s *Server) deleteRun(c *gin.Context) {
    runID := c.Param("id")
    existing, err := config.LoadConfig("configs/config.yaml")
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    newRuns := []config.ScheduledRun{}
    for _, r := range existing.ScheduledRuns {
        if r.ID != runID {
            newRuns = append(newRuns, r)
        }
    }
    existing.ScheduledRuns = newRuns
    if err := config.SaveConfig("configs/config.yaml", existing); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    s.cfg = existing
    s.agent = agent.NewAgent(existing, s.logger)

    c.JSON(http.StatusOK, gin.H{"status": "deleted"})
}

func (s *Server) stopAgent(c *gin.Context) {
    if !s.agent.IsRunning() {
        c.JSON(http.StatusOK, gin.H{"status": "not running"})
        return
    }
    s.agent.Stop()
    c.JSON(http.StatusOK, gin.H{"status": "stopped"})
}

func (s *Server) getStatus(c *gin.Context) {
    running := s.agent.IsRunning()
    var runID string
    if running {
        runID = s.agent.GetCurrentRunID()
    }
    c.JSON(http.StatusOK, gin.H{
        "running": running,
        "runID":   runID,
    })
}

func (s *Server) websocketLogs(c *gin.Context) {
    conn, err := s.upgrader.Upgrade(c.Writer, c.Request, nil)
    if err != nil {
        s.logger.Error("WebSocket upgrade failed:", err)
        return
    }
    defer conn.Close()

    logCh := s.agent.LogChannel()
    for {
        select {
        case msg := <-logCh:
            if err := conn.WriteMessage(websocket.TextMessage, []byte(msg)); err != nil {
                return
            }
        case <-c.Request.Context().Done():
            return
        }
    }
}

// --- runScheduler ---
func (rs *runScheduler) start() {
    ticker := time.NewTicker(1 * time.Second)
    defer ticker.Stop()
    for {
        select {
        case <-ticker.C:
            rs.checkRuns()
        case <-rs.stop:
            return
        }
    }
}

func (rs *runScheduler) checkRuns() {
    if rs.server.agent.IsRunning() {
        return
    }
    // Reload config from disk to get the latest runs (ensures deletion is respected)
    cfg, err := config.LoadConfig("configs/config.yaml")
    if err != nil {
        rs.server.logger.WithError(err).Error("Failed to reload config in scheduler")
        return
    }
    now := time.Now()
    for _, run := range cfg.ScheduledRuns {
        if run.DropTime.Before(now) || run.DropTime.Equal(now) {
            // Verify run is still valid
            if site, ok := cfg.Sites[run.SiteKey]; !ok {
                rs.server.logger.WithField("run_id", run.ID).Warn("Run site not found, removing")
                rs.deleteRunByID(run.ID)
                continue
            } else if _, ok := site.Museums[run.MuseumSlug]; !ok {
                rs.server.logger.WithField("run_id", run.ID).Warn("Run museum not found, removing")
                rs.deleteRunByID(run.ID)
                continue
            }
            rs.server.logger.WithField("run_id", run.ID).Info("Starting scheduled run")
            // Update the server's config to the reloaded one (keeps everything in sync)
            rs.server.cfg = cfg
            rs.server.agent = agent.NewAgent(cfg, rs.server.logger)
            if err := rs.server.agent.Start(run.ID); err != nil {
                rs.server.logger.WithError(err).Error("Failed to start scheduled run")
                rs.deleteRunByID(run.ID)
            }
            break
        }
    }
}

func (rs *runScheduler) deleteRunByID(runID string) {
    existing, err := config.LoadConfig("configs/config.yaml")
    if err != nil {
        return
    }
    newRuns := []config.ScheduledRun{}
    for _, r := range existing.ScheduledRuns {
        if r.ID != runID {
            newRuns = append(newRuns, r)
        }
    }
    existing.ScheduledRuns = newRuns
    _ = config.SaveConfig("configs/config.yaml", existing)
    rs.server.cfg = existing
    rs.server.agent = agent.NewAgent(existing, rs.server.logger)
}

func (rs *runScheduler) Stop() {
    close(rs.stop)
}

package web

import (
    "agent/pkg/agent"
    "agent/pkg/config"
    "embed"
    "io/fs"
    "net/http"
    "time"

    "github.com/gin-gonic/gin"
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
    s.setupRoutes()
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
        api.PUT("/config", s.updateConfig)
        api.POST("/run-now", s.runNow)
        api.POST("/schedule", s.schedule)
        api.GET("/logs", s.websocketLogs)
        api.POST("/stop", s.stopAgent)
        api.GET("/status", s.getStatus)
    }
}

func (s *Server) Run(addr string) error {
    return s.router.Run(addr)
}

func (s *Server) getConfig(c *gin.Context) {
    c.JSON(http.StatusOK, s.cfg)
}

func (s *Server) updateConfig(c *gin.Context) {
    var newCfg config.AppConfig
    if err := c.ShouldBindJSON(&newCfg); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }
    if _, ok := newCfg.Sites[newCfg.PreferredSlug]; !ok && newCfg.PreferredSlug != "" {
        c.JSON(http.StatusBadRequest, gin.H{"error": "preferred slug not found in sites"})
        return
    }
    if err := config.SaveConfig("configs/config.yaml", &newCfg); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    s.cfg = &newCfg
    s.agent = agent.NewAgent(&newCfg, s.logger)
    c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

func (s *Server) runNow(c *gin.Context) {
    if s.agent.IsRunning() {
        c.JSON(http.StatusConflict, gin.H{"error": "agent already running"})
        return
    }
    dropTime := time.Now().Add(30 * time.Second)
    go func() {
        if err := s.agent.Start(dropTime); err != nil {
            s.logger.WithError(err).Error("Agent start failed")
        }
    }()
    c.JSON(http.StatusOK, gin.H{"status": "started", "dropTime": dropTime.Format(time.RFC3339)})
}

func (s *Server) schedule(c *gin.Context) {
    var req struct {
        DropTime string `json:"dropTime"`
        Timezone string `json:"timezone"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
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
    if s.agent.IsRunning() {
        c.JSON(http.StatusConflict, gin.H{"error": "agent already running"})
        return
    }
    go func() {
        if err := s.agent.Start(t); err != nil {
            s.logger.WithError(err).Error("Agent start failed")
        }
    }()
    c.JSON(http.StatusOK, gin.H{"status": "scheduled", "dropTime": t.Format(time.RFC3339)})
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
    var dropTimeStr string
    if running {
        dt := s.agent.GetDropTime()
        if !dt.IsZero() {
            dropTimeStr = dt.Format(time.RFC3339)
        }
    }
    c.JSON(http.StatusOK, gin.H{
        "running":  running,
        "dropTime": dropTimeStr,
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

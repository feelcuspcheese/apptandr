package web

import (
    "agent/pkg/agent"
    "agent/pkg/config"
    "embed"
    "encoding/json"
    "fmt"
    "net/http"
    "time"

    "github.com/gin-contrib/static"
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

    // Serve embedded static files
    s.router.Use(static.Serve("/", static.EmbedFolder(staticFS, "static")))

    // API endpoints
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
    // Validate and save
    // For simplicity, we assume it's valid; we could add checks.
    if err := config.SaveConfig("configs/config.yaml", &newCfg); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    s.cfg = &newCfg
    c.JSON(http.StatusOK, gin.H{"status": "ok"})
}

func (s *Server) runNow(c *gin.Context) {
    if s.agent.IsRunning() {
        c.JSON(http.StatusConflict, gin.H{"error": "agent already running"})
        return
    }
    dropTime := time.Now().Add(30 * time.Second)
    if err := s.agent.Start(dropTime); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    c.JSON(http.StatusOK, gin.H{"status": "started", "dropTime": dropTime.Format(time.RFC3339)})
}

func (s *Server) schedule(c *gin.Context) {
    var req struct {
        DropTime   string `json:"dropTime"`
        Timezone   string `json:"timezone"`
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
    t, err := time.ParseInLocation("2006-01-02T15:04:05", req.DropTime, loc)
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid datetime format"})
        return
    }
    if s.agent.IsRunning() {
        c.JSON(http.StatusConflict, gin.H{"error": "agent already running"})
        return
    }
    if err := s.agent.Start(t); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
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
    c.JSON(http.StatusOK, gin.H{
        "running": s.agent.IsRunning(),
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

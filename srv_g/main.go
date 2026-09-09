package main

import (
	"fmt"
	"html/template"
	"net"
	"os"
	"os/signal"
	"syscall"

	"github.com/gin-gonic/gin"
	"github.com/zngp/server/config"
	"github.com/zngp/server/internal/certgen"
	"github.com/zngp/server/internal/handler"
	"github.com/zngp/server/internal/logx"
	"github.com/zngp/server/internal/middleware"
	"github.com/zngp/server/internal/store"
)

func main() {
	// Load config
	cfgPath := "cfg.yml"
	if len(os.Args) > 1 {
		cfgPath = os.Args[1]
	}

	cfg, err := config.Load(cfgPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "config_load_failed: %v\n", err)
		os.Exit(1)
	}

	// Initialize logging (level: debug/info/warn/error, format: text/json)
	logx.Init(logx.Level(cfg.Server.LogLevel), cfg.Server.LogFormat)

	// Ensure TLS certificate exists (generate self-signed if missing)
	if err := certgen.EnsureCert(cfg.Server.CertFile, cfg.Server.KeyFile); err != nil {
		logx.Fatal("cert_ensure_failed", "err", err)
	}
	logx.Info("tls_cert_ready", "cert", cfg.Server.CertFile, "key", cfg.Server.KeyFile)

	// Initialize store (SQLite by default; MySQL if configured)
	st, err := store.New(cfg.Database)
	if err != nil {
		logx.Fatal("store_init_failed", "err", err)
	}

	// Ensure default admin user
	if err := handler.EnsureDefaultAdmin(st); err != nil {
		logx.Fatal("default_admin_create_failed", "err", err)
	}
	logx.Info("default_admin_ready")

	// Seed templates (if not existed)
	if err := SeedTemplates(st); err != nil {
		logx.Error("seed_template_insert_failed", "err", err)
	}

	// Initialize handlers
	authH := handler.NewAuthHandler(st)
	recordH := handler.NewRecordHandler(st)
	templateH := handler.NewTemplateHandler(st)
	inspectionH := handler.NewInspectionHandler(st)
	asrH := handler.NewASRHandler(st)
	webH := handler.NewWebHandler(st)
	configH := handler.NewConfigHandler()

	// Setup Gin
	r := gin.Default()

	// Template functions
	r.SetFuncMap(template.FuncMap{
		"sub": func(a, b int) int { return a - b },
		"add": func(a, b int) int { return a + b },
		"mul": func(a, b float64) float64 { return a * b },
		"transcriptStatusClass": func(s string) string {
			switch s {
			case "COMPLETED": return "COMPLETED"
			case "PROCESSING": return "PROCESSING"
			case "FAILED": return "FAILED"
			default: return "PENDING"
			}
		},
		"inspectionStatusClass": func(s string) string {
			switch s {
			case "COMPLETED": return "COMPLETED"
			case "PROCESSING": return "PROCESSING"
			case "FAILED": return "FAILED"
			default: return "NONE"
			}
		},
		"verdictClass": func(s string) string {
			switch s {
			case "通过": return "通过"
			case "未通过": return "未通过"
			default: return "未提及"
			}
		},
		"conclusionClass": func(s string) string {
			switch s {
			case "规范": return "规范"
			case "不规范": return "不规范"
			default: return "需复核"
			}
		},
		"sysName": func() string { return config.AppConfig.System.Name },
	})

	// Load templates
	r.LoadHTMLGlob("web/templates/*.html")

	// Static files for web frontend
	r.Static("/static", "./web/static")

	// Public API
	api := r.Group("/api")
	{
		api.POST("/auth/login", authH.Login)

		// Protected routes
		protected := api.Group("", middleware.AuthRequired())
		{
			protected.POST("/auth/change-password", authH.ChangePassword)
			protected.GET("/auth/status", authH.Status)

			// Records
			protected.POST("/records", recordH.Upload)
			protected.POST("/records/text", recordH.UploadText)
			protected.GET("/records", recordH.List)
			protected.GET("/records/:id", recordH.Get)
			protected.DELETE("/records/:id", recordH.Delete)
			protected.GET("/records/:id/audio", recordH.AudioStream)
			protected.GET("/records/:id/transcript-status", recordH.GetTranscriptionStatus)

			// ASR (manual trigger, mainly for retry)
			protected.POST("/records/:id/transcribe", asrH.Transcribe)

			// Inspection (to be implemented in Phase 3)
			protected.POST("/records/:id/inspect", inspectionH.Inspect)
			protected.GET("/inspections/:id", inspectionH.GetResult)

			// Templates
			protected.GET("/templates", templateH.List)
			protected.POST("/templates", templateH.Create)
			protected.GET("/templates/:id", templateH.Get)
			protected.PUT("/templates/:id", templateH.Update)
			protected.DELETE("/templates/:id", templateH.Delete)
			protected.POST("/templates/:id/items", templateH.CreateItem)
			protected.PUT("/templates/:id/items/:iid", templateH.UpdateItem)
			protected.DELETE("/templates/:id/items/:iid", templateH.DeleteItem)

			// Stats
			protected.GET("/stats/overview", func(c *gin.Context) {
				total, compliant, nonCompliant, review, err := st.GetOverview()
				if err != nil {
					c.JSON(500, gin.H{"error": err.Error()})
					return
				}
				c.JSON(200, gin.H{
					"total_records":       total,
					"compliant_count":     compliant,
					"non_compliant_count": nonCompliant,
					"review_count":        review,
				})
			})

			// Config
			protected.GET("/config", configH.GetConfig)
			protected.PUT("/config", configH.UpdateConfig)
		}
	}

	// Web pages
	r.GET("/login", webH.LoginPage)
	r.GET("/logout", func(c *gin.Context) {
		// Try to extract username from token for logging
		if tokenStr := middleware.ExtractTokenFromRequest(c); tokenStr != "" {
			if claims, err := middleware.ParseToken(tokenStr); err == nil {
				logx.Info("user_logged_out", "username", claims.Username)
			}
		}
		middleware.ClearTokenCookie(c)
		c.Redirect(302, "/login")
	})
	r.GET("/", middleware.AuthWebRequired(), webH.Dashboard)
	r.GET("/upload", middleware.AuthWebRequired(), webH.UploadPage)
	r.GET("/records", middleware.AuthWebRequired(), webH.RecordsPage)
	r.GET("/records/:id", middleware.AuthWebRequired(), webH.RecordDetail)
	r.GET("/templates", middleware.AuthWebRequired(), webH.TemplatesPage)
	r.GET("/templates/:id/edit", middleware.AuthWebRequired(), webH.TemplateEditPage)
	r.GET("/config", middleware.AuthWebRequired(), webH.ConfigPage)

	// Graceful shutdown
	go func() {
		quit := make(chan os.Signal, 1)
		signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
		<-quit
		logx.Info("server_shutting_down")
		os.Exit(0)
	}()

	addr := fmt.Sprintf("%s:%s", cfg.Server.Host, cfg.Server.Port)
	logx.Info("server_starting_https", "addr", addr)
	printAccessURLs(cfg.Server.Port)
	if err := r.RunTLS(addr, cfg.Server.CertFile, cfg.Server.KeyFile); err != nil {
		logx.Fatal("server_run_failed", "err", err)
	}
}

// printAccessURLs lists all local IP addresses with clickable access URLs.
func printAccessURLs(port string) {
	ips := collectLocalIPs()
	if len(ips) == 0 {
		logx.Info("server_access_url", "url", fmt.Sprintf("https://127.0.0.1:%s", port))
		return
	}
	for _, ip := range ips {
		logx.Info("server_access_url", "url", fmt.Sprintf("https://%s:%s", ip, port))
	}
}

// collectLocalIPs returns all non-loopback IPv4 addresses on this machine,
// plus 127.0.0.1 (and ::1) so local access always works.
func collectLocalIPs() []string {
	var ips []string
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return []string{"127.0.0.1"}
	}
	for _, addr := range addrs {
		var ip net.IP
		switch v := addr.(type) {
		case *net.IPNet:
			ip = v.IP
		case *net.IPAddr:
			ip = v.IP
		}
		if ip == nil {
			continue
		}
		ip = ip.To4()
		if ip == nil || ip.IsLoopback() {
			continue
		}
		ips = append(ips, ip.String())
	}
	// Always include loopback first
	ips = append([]string{"127.0.0.1"}, ips...)
	return ips
}

// SeedTemplates creates default inspection templates if they don't exist
func SeedTemplates(st *store.Store) error {
	templates, err := st.ListTemplates()
	if err != nil {
		return err
	}
	if len(templates) > 0 {
		return nil // already seeded
	}

	importTemplates := buildDefaultTemplates()
	for _, t := range importTemplates {
		if err := st.CreateTemplate(&t); err != nil {
			return err
		}
	}
	logx.Info("templates_seeded")
	return nil
}
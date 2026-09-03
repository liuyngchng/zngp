package main

import (
	"fmt"
	"html/template"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/gin-gonic/gin"
	"github.com/zngp/server/config"
	"github.com/zngp/server/internal/handler"
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
		log.Fatalf("config_load_failed %v", err)
	}

	// Initialize store
	st, err := store.New(cfg.Database.Path)
	if err != nil {
		log.Fatalf("store_init_failed %v", err)
	}

	// Ensure default admin user
	if err := handler.EnsureDefaultAdmin(st); err != nil {
		log.Fatalf("default_admin_create_failed %v", err)
	}
	log.Println("default_admin_ready")

	// Seed templates (if not existed)
	if err := SeedTemplates(st); err != nil {
		log.Printf("seed_template_insert_failed %v", err)
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

			// Records
			protected.POST("/records", recordH.Upload)
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
		log.Println("server_shutting_down")
		os.Exit(0)
	}()

	addr := fmt.Sprintf("%s:%s", cfg.Server.Host, cfg.Server.Port)
	log.Printf("server_starting_http %s", addr)
	log.Printf("server_local_access_127_0_0_1 %s", cfg.Server.Port)
	if err := r.Run(addr); err != nil {
		log.Fatalf("server_run_failed %v", err)
	}
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
	log.Println("templates_seeded")
	return nil
}
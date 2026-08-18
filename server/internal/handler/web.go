package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/zngp/server/config"
	"github.com/zngp/server/internal/model"
	"github.com/zngp/server/internal/store"
)

// WebHandler renders server-side HTML pages for the management console
type WebHandler struct {
	store *store.Store
}

func NewWebHandler(s *store.Store) *WebHandler {
	return &WebHandler{store: s}
}

// Dashboard renders the overview page
func (h *WebHandler) Dashboard(c *gin.Context) {
	total, compliant, nonCompliant, review, err := h.store.GetOverview()
	if err != nil {
		c.HTML(http.StatusInternalServerError, "error.html", gin.H{"content_block": "content_error", "error": err.Error()})
		return
	}

	records, _, err := h.store.ListRecords(1, 10, "")
	if err != nil {
		c.HTML(http.StatusInternalServerError, "error.html", gin.H{"content_block": "content_error", "error": err.Error()})
		return
	}

	c.HTML(http.StatusOK, "dashboard.html", gin.H{
		"content_block":     "content_dashboard",
		"title":             "仪表盘",
		"total_records":     total,
		"compliant_count":   compliant,
		"non_compliant":     nonCompliant,
		"review_count":      review,
		"recent_records":    records,
	})
}

// RecordsPage renders the records list page
func (h *WebHandler) RecordsPage(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize := 20
	keyword := c.Query("keyword")

	if page < 1 {
		page = 1
	}

	records, total, err := h.store.ListRecords(page, pageSize, keyword)
	if err != nil {
		c.HTML(http.StatusInternalServerError, "error.html", gin.H{"content_block": "content_error", "error": err.Error()})
		return
	}

	totalPages := (int(total) + pageSize - 1) / pageSize

	c.HTML(http.StatusOK, "records.html", gin.H{
		"content_block": "content_records",
		"title":         "记录列表",
		"records":       records,
		"total":         total,
		"page":          page,
		"total_pages":   totalPages,
		"keyword":       keyword,
	})
}

// RecordDetail renders a single record with transcription + inspection
func (h *WebHandler) RecordDetail(c *gin.Context) {
	id := c.Param("id")
	record, err := h.store.GetRecord(id)
	if err != nil {
		c.HTML(http.StatusNotFound, "error.html", gin.H{"content_block": "content_error", "error": "记录不存在"})
		return
	}

	var inspection *model.InspectionResult
	if ins, err := h.store.GetInspectionByRecordID(id); err == nil {
		inspection = ins
	}

	// Fetch templates for inspection trigger form
	templates, _ := h.store.ListTemplates()

	c.HTML(http.StatusOK, "record_detail.html", gin.H{
		"content_block": "content_record_detail",
		"title":         "记录详情",
		"record":        record,
		"inspection":    inspection,
		"templates":     templates,
	})
}

// UploadPage renders the manual audio upload page for admins
func (h *WebHandler) UploadPage(c *gin.Context) {
	c.HTML(http.StatusOK, "upload.html", gin.H{
		"content_block": "content_upload",
		"title":         "上传音频",
		"max_size_mb":   config.AppConfig.Upload.MaxFileSizeMB,
	})
}

// TemplatesPage renders the template management page
func (h *WebHandler) TemplatesPage(c *gin.Context) {
	templates, err := h.store.ListTemplates()
	if err != nil {
		c.HTML(http.StatusInternalServerError, "error.html", gin.H{"content_block": "content_error", "error": err.Error()})
		return
	}

	c.HTML(http.StatusOK, "templates.html", gin.H{
		"content_block": "content_templates",
		"title":         "检查项模板",
		"templates":     templates,
	})
}

// TemplateEditPage renders the template editing page
func (h *WebHandler) TemplateEditPage(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.HTML(http.StatusBadRequest, "error.html", gin.H{"content_block": "content_error", "error": "无效的模板ID"})
		return
	}

	template, err := h.store.GetTemplate(id)
	if err != nil {
		c.HTML(http.StatusNotFound, "error.html", gin.H{"content_block": "content_error", "error": "模板不存在"})
		return
	}

	c.HTML(http.StatusOK, "template_edit.html", gin.H{
		"content_block": "content_template_edit",
		"title":         "编辑模板",
		"template":      template,
	})
}

// LoginPage renders the login page
func (h *WebHandler) LoginPage(c *gin.Context) {
	c.HTML(http.StatusOK, "login.html", gin.H{"title": "登录"})
}

// ConfigPage renders the config page
func (h *WebHandler) ConfigPage(c *gin.Context) {
	c.HTML(http.StatusOK, "config.html", gin.H{"content_block": "content_config", "title": "系统配置"})
}
package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/zngp/server/internal/logx"
	"github.com/zngp/server/internal/model"
	"github.com/zngp/server/internal/service"
	"github.com/zngp/server/internal/store"
)

type InspectionHandler struct {
	store *store.Store
	svc   *service.InspectionService
}

func NewInspectionHandler(s *store.Store) *InspectionHandler {
	return &InspectionHandler{
		store: s,
		svc:   service.NewInspectionService(s),
	}
}

type InspectRequest struct {
	TemplateID int64 `json:"template_id" binding:"required"`
}

func (h *InspectionHandler) Inspect(c *gin.Context) {
	recordID := c.Param("id")

	var req InspectRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请指定 template_id（检查项模板ID）"})
		return
	}

	// Get the record
	record, err := h.store.GetRecord(recordID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "记录不存在"})
		return
	}

	// Update status to processing
	if err := h.store.UpdateRecordInspectionStatus(recordID, "PROCESSING"); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "更新状态失败"})
		return
	}

	// Run inspection asynchronously so the HTTP request does not block on the LLM call.
	// The client polls GET /records/:id (or /inspections/:id) to observe completion.
	logx.Info("llm_inspection_start", "record", recordID, "template_id", req.TemplateID)
	go h.runInspectionInBackground(record, req.TemplateID)

	c.JSON(http.StatusAccepted, gin.H{
		"record_id":  recordID,
		"status":     "PROCESSING",
		"message":    "质检已开始，正在后台处理...",
	})
}

// runInspectionInBackground executes the LLM inspection and updates the record status.
func (h *InspectionHandler) runInspectionInBackground(record *model.Record, templateID int64) {
	result, err := h.svc.Run(record, templateID)
	if err != nil {
		logx.Error("llm_inspection_failed", "record", record.ID, "err", err)
		h.store.UpdateRecordInspectionStatus(record.ID, "FAILED")
		return
	}

	if err := h.store.UpdateRecordInspectionStatus(record.ID, "COMPLETED"); err != nil {
		logx.Error("llm_inspection_status_update_failed", "record", record.ID, "err", err)
	}

	logx.Info("llm_inspection_done", "record", record.ID, "conclusion", result.OverallConclusion, "score", result.OverallScore, "tokens", result.TokensUsed)
}

func (h *InspectionHandler) GetResult(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "无效的检查结果ID"})
		return
	}

	result, err := h.store.GetInspectionResult(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "检查结果不存在"})
		return
	}

	c.JSON(http.StatusOK, result)
}
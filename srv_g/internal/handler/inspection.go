package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
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

	// Run inspection (in background would be better, but for MVP we do it synchronously)
	result, err := h.svc.Run(record, req.TemplateID)
	if err != nil {
		h.store.UpdateRecordInspectionStatus(recordID, "FAILED")
		c.JSON(http.StatusInternalServerError, gin.H{"error": "质检失败: " + err.Error()})
		return
	}

	// Update record status
	if err := h.store.UpdateRecordInspectionStatus(recordID, "COMPLETED"); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "更新状态失败"})
		return
	}

	c.JSON(http.StatusOK, result)
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
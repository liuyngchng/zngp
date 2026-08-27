package handler

import (
	"fmt"
	"log"
	"net/http"
	"path/filepath"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/zngp/server/config"
	"github.com/zngp/server/internal/model"
	"github.com/zngp/server/internal/service"
	"github.com/zngp/server/internal/store"
)

type RecordHandler struct {
	store *store.Store
}

func NewRecordHandler(s *store.Store) *RecordHandler {
	return &RecordHandler{store: s}
}

// Upload handles multipart upload of audio + metadata.
// After upload, transcription is triggered automatically in the background.
func (h *RecordHandler) Upload(c *gin.Context) {
	// Parse JSON metadata from form field
	metadataStr := c.PostForm("metadata")
	if metadataStr == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "缺少 metadata 字段"})
		return
	}

	var req model.RecordCreateRequest
	if err := jsonUnmarshal(metadataStr, &req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "metadata 格式错误: " + err.Error()})
		return
	}

	// Parse audio file
	file, header, err := c.Request.FormFile("audio")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "缺少 audio 文件"})
		return
	}
	defer file.Close()

	// Validate file size
	maxSize := int64(config.AppConfig.Upload.MaxFileSizeMB) * 1024 * 1024
	if header.Size > maxSize {
		c.JSON(http.StatusBadRequest, gin.H{"error": fmt.Sprintf("文件超过大小限制 (%dMB)", config.AppConfig.Upload.MaxFileSizeMB)})
		return
	}

	// Generate record ID if not provided
	recordID := req.ID
	if recordID == "" {
		recordID = uuid.New().String()
	}

	// Parse inspection date
	var inspectionDate time.Time
	if req.InspectionDate != "" {
		inspectionDate, _ = parseTime(req.InspectionDate)
	} else {
		inspectionDate = time.Now()
	}

	// Save audio file
	storageDir := config.AppConfig.Upload.StorageDir
	ext := filepath.Ext(header.Filename)
	if ext == "" {
		ext = ".wav"
	}
	audioFilename := recordID + ext
	audioPath := filepath.Join(storageDir, audioFilename)

	if err := saveUploadedFile(file, audioPath); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "保存音频文件失败"})
		return
	}

	// Determine audio duration from WAV header if possible
	duration := wavDuration(audioPath)

	sourceType := req.SourceType
	if sourceType == "" {
		sourceType = "RECORDING"
	}

	record := &model.Record{
		ID:               recordID,
		Title:            req.Title,
		Description:      req.Description,
		InspectorName:    req.InspectorName,
		CustomerName:     req.CustomerName,
		CustomerAddress:  req.CustomerAddress,
		InspectionDate:   inspectionDate,
		SourceType:       sourceType,
		AudioFilePath:    audioPath,
		AudioDuration:    duration,
		TranscriptStatus: "PENDING",
		InspectionStatus: "NONE",
		CreatedAt:        time.Now(),
		UpdatedAt:        time.Now(),
	}

	if err := h.store.CreateRecord(record); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "创建记录失败: " + err.Error()})
		return
	}

	// 自动触发后台转写
	go h.autoTranscribe(record)

	// 返回上传成功，附上转写状态
	c.JSON(http.StatusOK, gin.H{
		"record":            record,
		"transcript_status": "PENDING",
		"message":           "上传成功，正在后台转写...",
	})
}

// autoTranscribe runs ASR in the background and updates the record
func (h *RecordHandler) autoTranscribe(record *model.Record) {
	log.Printf("[ASR] 开始自动转写: record=%s, audio=%s", record.ID, record.AudioFilePath)

	// 更新状态为处理中
	if err := h.store.UpdateRecordTranscript(record.ID, "", "PROCESSING"); err != nil {
		log.Printf("[ASR] 更新状态失败: %v", err)
		return
	}

	// 调用 ASR 服务
	text, err := service.TranscribeAudio(record.AudioFilePath)
	if err != nil {
		log.Printf("[ASR] 转写失败: record=%s, err=%v", record.ID, err)
		h.store.UpdateRecordTranscript(record.ID, "", "FAILED")
		return
	}

	// 保存转写结果
	if err := h.store.UpdateRecordTranscript(record.ID, text, "COMPLETED"); err != nil {
		log.Printf("[ASR] 保存转写结果失败: %v", err)
		return
	}

	log.Printf("[ASR] 转写完成: record=%s, text_len=%d", record.ID, len(text))
}

func (h *RecordHandler) List(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	keyword := c.Query("keyword")

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	records, total, err := h.store.ListRecords(page, pageSize, keyword)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "查询失败"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"records":   records,
		"total":     total,
		"page":      page,
		"page_size": pageSize,
	})
}

func (h *RecordHandler) Get(c *gin.Context) {
	id := c.Param("id")
	record, err := h.store.GetRecord(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "记录不存在"})
		return
	}

	// Also fetch inspection result if exists
	var inspection *model.InspectionResult
	if ins, err := h.store.GetInspectionByRecordID(id); err == nil {
		inspection = ins
	}

	c.JSON(http.StatusOK, gin.H{
		"record":     record,
		"inspection": inspection,
	})
}

// GetTranscriptionStatus returns the current transcription status for a record
func (h *RecordHandler) GetTranscriptionStatus(c *gin.Context) {
	id := c.Param("id")
	record, err := h.store.GetRecord(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "记录不存在"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"record_id":          record.ID,
		"transcript_status":  record.TranscriptStatus,
		"transcript_text":    record.TranscriptText,
		"message":            statusMessage(record.TranscriptStatus),
	})
}

func statusMessage(status string) string {
	switch status {
	case "PENDING":
		return "排队等待转写..."
	case "PROCESSING":
		return "正在进行语音转写，请稍候..."
	case "COMPLETED":
		return "转写完成"
	case "FAILED":
		return "转写失败，请重试"
	default:
		return "未知状态"
	}
}

func (h *RecordHandler) Delete(c *gin.Context) {
	id := c.Param("id")
	if err := h.store.DeleteRecord(id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "删除失败"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "已删除"})
}

// AudioStream serves the audio file for playback
func (h *RecordHandler) AudioStream(c *gin.Context) {
	id := c.Param("id")
	record, err := h.store.GetRecord(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "记录不存在"})
		return
	}

	c.File(record.AudioFilePath)
}
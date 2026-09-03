package handler

import (
	"log"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/zngp/server/internal/service"
	"github.com/zngp/server/internal/store"
)

type ASRHandler struct {
	store *store.Store
}

func NewASRHandler(s *store.Store) *ASRHandler {
	return &ASRHandler{store: s}
}

// Transcribe triggers ASR transcription for a record's audio
func (h *ASRHandler) Transcribe(c *gin.Context) {
	recordID := c.Param("id")

	record, err := h.store.GetRecord(recordID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "记录不存在"})
		return
	}

	if record.AudioFilePath == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "记录没有音频文件"})
		return
	}

	// Update status to processing
	if err := h.store.UpdateRecordTranscript(recordID, "", "PROCESSING"); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "更新状态失败"})
		return
	}

	// Run ASR
	log.Printf("asr_manual_transcribe_start record=%s audio=%s", recordID, record.AudioFilePath)
	text, err := service.TranscribeAudio(record.AudioFilePath)
	if err != nil {
		log.Printf("asr_manual_transcribe_failed record=%s err=%v", recordID, err)
		h.store.UpdateRecordTranscript(recordID, "", "FAILED")
		c.JSON(http.StatusInternalServerError, gin.H{"error": "转写失败: " + err.Error()})
		return
	}

	// Save transcript
	if err := h.store.UpdateRecordTranscript(recordID, text, "COMPLETED"); err != nil {
		log.Printf("asr_manual_transcribe_save_failed record=%s err=%v", recordID, err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "保存转写结果失败"})
		return
	}

	log.Printf("asr_manual_transcribe_done record=%s text_len=%d", recordID, len(text))
	c.JSON(http.StatusOK, gin.H{
		"record_id":  recordID,
		"transcript": text,
		"status":     "COMPLETED",
	})
}
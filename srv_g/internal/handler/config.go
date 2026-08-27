package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/zngp/server/config"
	"gopkg.in/yaml.v3"
)

type ConfigHandler struct{}

func NewConfigHandler() *ConfigHandler {
	return &ConfigHandler{}
}

// SafeConfig is the config exposed to the API (without secrets)
type SafeConfig struct {
	ASRWorkspaceID string `json:"asr_workspace_id"`
	ASRModel       string `json:"asr_model"`
	LLMEndpoint    string `json:"llm_endpoint"`
	LLMModel       string `json:"llm_model"`
}

type UpdateConfigRequest struct {
	ASRAPIKey      string `json:"asr_api_key"`
	ASRWorkspaceID string `json:"asr_workspace_id"`
	ASRModel       string `json:"asr_model"`
	LLMAPIKey      string `json:"llm_api_key"`
	LLMEndpoint    string `json:"llm_endpoint"`
	LLMModel       string `json:"llm_model"`
}

func (h *ConfigHandler) GetConfig(c *gin.Context) {
	cfg := config.AppConfig
	c.JSON(http.StatusOK, SafeConfig{
		ASRWorkspaceID: cfg.ASR.WorkspaceID,
		ASRModel:       cfg.ASR.Model,
		LLMEndpoint:    cfg.LLM.Endpoint,
		LLMModel:       cfg.LLM.Model,
	})
}

func (h *ConfigHandler) UpdateConfig(c *gin.Context) {
	var req UpdateConfigRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求格式错误"})
		return
	}

	cfg := config.AppConfig
	if req.ASRAPIKey != "" {
		cfg.ASR.APIKey = req.ASRAPIKey
	}
	if req.ASRWorkspaceID != "" {
		cfg.ASR.WorkspaceID = req.ASRWorkspaceID
	}
	if req.ASRModel != "" {
		cfg.ASR.Model = req.ASRModel
	}
	if req.LLMAPIKey != "" {
		cfg.LLM.APIKey = req.LLMAPIKey
	}
	if req.LLMEndpoint != "" {
		cfg.LLM.Endpoint = req.LLMEndpoint
	}
	if req.LLMModel != "" {
		cfg.LLM.Model = req.LLMModel
	}

	// Save back to cfg.yml
	data, err := yaml.Marshal(cfg)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "序列化配置失败"})
		return
	}
	if err := writeConfigFile("cfg.yml", data); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "保存配置失败"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "配置已更新"})
}
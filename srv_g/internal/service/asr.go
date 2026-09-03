package service

import (
	"bytes"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/zngp/server/config"
)

// ASRResponse is the OpenAI-compatible response from qwen3-asr-flash
type ASRResponse struct {
	ID      string `json:"id"`
	Choices []struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	} `json:"choices"`
	Error *struct {
		Message string `json:"message"`
		Type    string `json:"type"`
	} `json:"error,omitempty"`
}

// TranscribeAudio sends an audio file to Aliyun Bailian ASR and returns the transcription.
func TranscribeAudio(audioPath string) (string, error) {
	cfg := config.AppConfig

	// Read audio file
	data, err := os.ReadFile(audioPath)
	if err != nil {
		return "", fmt.Errorf("读取音频文件失败: %w", err)
	}

	// Base64 encode
	b64 := base64.StdEncoding.EncodeToString(data)

	// Determine MIME type from extension
	mimeType := "audio/wav"
	if strings.HasSuffix(strings.ToLower(audioPath), ".mp3") {
		mimeType = "audio/mpeg"
	}

	dataURI := fmt.Sprintf("data:%s;base64,%s", mimeType, b64)

	// Build the base URL with workspace ID
	baseURL := strings.Replace(cfg.ASR.BaseURL, "{WorkspaceId}", cfg.ASR.WorkspaceID, 1)
	apiURL := baseURL + "/chat/completions"

	reqBody := map[string]interface{}{
		"model": cfg.ASR.Model,
		"messages": []map[string]interface{}{
			{
				"role": "user",
				"content": []map[string]interface{}{
					{
						"type": "input_audio",
						"input_audio": map[string]string{
							"data": dataURI,
						},
					},
				},
			},
		},
		"stream": false,
		"asr_options": map[string]interface{}{
			"enable_itn": false,
		},
	}

	jsonBody, err := json.Marshal(reqBody)
	if err != nil {
		return "", fmt.Errorf("序列化请求失败: %w", err)
	}

	httpReq, err := http.NewRequest("POST", apiURL, bytes.NewReader(jsonBody))
	if err != nil {
		return "", fmt.Errorf("创建请求失败: %w", err)
	}

	httpReq.Header.Set("Authorization", "Bearer "+cfg.ASR.APIKey)
	httpReq.Header.Set("Content-Type", "application/json")

	log.Printf("asr_request_start url=%s model=%s audio_size=%d audio_path=%s", apiURL, cfg.ASR.Model, len(data), audioPath)
	startTime := time.Now()

	client := createDirectClient()

	resp, err := client.Do(httpReq)
	elapsed := time.Since(startTime)
	if err != nil {
		log.Printf("asr_request_failed url=%s err=%v elapsed=%v", apiURL, err, elapsed)
		return "", fmt.Errorf("ASR API 请求失败: %w", err)
	}
	defer resp.Body.Close()

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		log.Printf("asr_read_response_failed err=%v elapsed=%v", err, elapsed)
		return "", fmt.Errorf("读取响应失败: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		log.Printf("asr_api_http_error url=%s status=%d body=%s elapsed=%v", apiURL, resp.StatusCode, string(bodyBytes), elapsed)
		return "", fmt.Errorf("ASR API 返回错误 (%d): %s", resp.StatusCode, string(bodyBytes))
	}

	var asrResp ASRResponse
	if err := json.Unmarshal(bodyBytes, &asrResp); err != nil {
		log.Printf("asr_json_parse_failed err=%v body=%s elapsed=%v", err, string(bodyBytes), elapsed)
		return "", fmt.Errorf("解析响应失败: %w", err)
	}

	if asrResp.Error != nil {
		log.Printf("asr_api_biz_error err=%s elapsed=%v", asrResp.Error.Message, elapsed)
		return "", fmt.Errorf("ASR 错误: %s", asrResp.Error.Message)
	}

	if len(asrResp.Choices) == 0 {
		log.Printf("asr_empty_result elapsed=%v", elapsed)
		return "", fmt.Errorf("ASR 返回空结果")
	}

	text := asrResp.Choices[0].Message.Content
	log.Printf("asr_request_success text_len=%d elapsed=%v", len(text), elapsed)
	return text, nil
}

// createDirectClient creates an HTTP client with no proxy and TLS verification disabled
func createDirectClient() *http.Client {
	return &http.Client{
		Timeout: 120 * time.Second,
		Transport: &http.Transport{
			MaxIdleConns:    10,
			IdleConnTimeout: 30 * time.Second,
			Proxy:           nil,
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		},
	}
}
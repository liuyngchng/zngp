package service

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/zngp/server/config"
)

// LLMRequest is an OpenAI-compatible chat completion request
type LLMRequest struct {
	Model       string    `json:"model"`
	Messages    []Message `json:"messages"`
	Temperature float64   `json:"temperature"`
	MaxTokens   int       `json:"max_tokens"`
}

// Message represents a chat message
type Message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

// LLMResponse is the OpenAI-compatible response
type LLMResponse struct {
	Choices []struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	} `json:"choices"`
	Usage *struct {
		TotalTokens int `json:"total_tokens"`
	} `json:"usage"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error,omitempty"`
}

// ChatCompletion sends a prompt to the LLM and returns the response text
func ChatCompletion(systemPrompt, userPrompt string) (string, int, error) {
	cfg := config.AppConfig

	req := LLMRequest{
		Model: cfg.LLM.Model,
		Messages: []Message{
			{Role: "system", Content: systemPrompt},
			{Role: "user", Content: userPrompt},
		},
		Temperature: 0.3,
		MaxTokens:   4096,
	}

	jsonBody, err := json.Marshal(req)
	if err != nil {
		return "", 0, fmt.Errorf("序列化请求失败: %w", err)
	}

	apiURL := buildOpenAIURL(cfg.LLM.Endpoint)

	httpReq, err := http.NewRequest("POST", apiURL, bytes.NewReader(jsonBody))
	if err != nil {
		return "", 0, fmt.Errorf("创建请求失败: %w", err)
	}

	httpReq.Header.Set("Authorization", "Bearer "+cfg.LLM.APIKey)
	httpReq.Header.Set("Content-Type", "application/json")

	client := &http.Client{
		Timeout:   180 * time.Second,
		Transport: createTransportWithProxy(cfg.LLM.Proxy),
	}

	resp, err := client.Do(httpReq)
	if err != nil {
		return "", 0, fmt.Errorf("LLM API 请求失败: %w", err)
	}
	defer resp.Body.Close()

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", 0, fmt.Errorf("读取响应失败: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return "", 0, fmt.Errorf("LLM API 返回错误 (%d): %s", resp.StatusCode, string(bodyBytes))
	}

	var llmResp LLMResponse
	if err := json.Unmarshal(bodyBytes, &llmResp); err != nil {
		return "", 0, fmt.Errorf("解析响应失败: %w", err)
	}

	if llmResp.Error != nil {
		return "", 0, fmt.Errorf("LLM 错误: %s", llmResp.Error.Message)
	}

	if len(llmResp.Choices) == 0 {
		return "", 0, fmt.Errorf("LLM 返回空结果")
	}

	tokens := 0
	if llmResp.Usage != nil {
		tokens = llmResp.Usage.TotalTokens
	}

	return llmResp.Choices[0].Message.Content, tokens, nil
}

func buildOpenAIURL(endpoint string) string {
	trimmed := strings.TrimRight(endpoint, "/")
	if strings.HasSuffix(trimmed, "/chat/completions") {
		return trimmed
	}
	return trimmed + "/v1/chat/completions"
}

func createTransportWithProxy(proxyURL string) *http.Transport {
	transport := &http.Transport{
		MaxIdleConns:    10,
		IdleConnTimeout: 30 * time.Second,
	}
	if proxyURL != "" {
		if u, err := parseProxyURL(proxyURL); err == nil {
			transport.Proxy = func(req *http.Request) (*url.URL, error) {
				return u, nil
			}
		}
	}
	return transport
}

func parseProxyURL(s string) (*url.URL, error) {
	// Go's net/url needs a scheme
	if !strings.Contains(s, "://") {
		s = "http://" + s
	}
	return url.Parse(s)
}
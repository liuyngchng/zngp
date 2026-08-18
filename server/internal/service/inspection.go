package service

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/zngp/server/config"
	"github.com/zngp/server/internal/model"
	"github.com/zngp/server/internal/store"
)

// InspectionService runs AI quality inspection on transcribed text
type InspectionService struct {
	store *store.Store
}

func NewInspectionService(s *store.Store) *InspectionService {
	return &InspectionService{store: s}
}

// InspectionResultJSON is the structure we ask the LLM to return
type InspectionResultJSON struct {
	OverallConclusion string              `json:"overall_conclusion"`
	OverallScore      int                 `json:"overall_score"`
	Summary           string              `json:"summary"`
	Items             []InspectionItemJSON `json:"items"`
}

type InspectionItemJSON struct {
	ItemName   string  `json:"item_name"`
	Verdict    string  `json:"verdict"` // 通过/未通过/未提及
	Evidence   string  `json:"evidence"`
	Confidence float64 `json:"confidence"`
	Reasoning  string  `json:"reasoning"`
}

// Run performs the inspection and stores the result
func (s *InspectionService) Run(record *model.Record, templateID int64) (*model.InspectionResult, error) {
	// Get template
	template, err := s.store.GetTemplate(templateID)
	if err != nil {
		return nil, fmt.Errorf("模板不存在: %w", err)
	}

	if len(template.Items) == 0 {
		return nil, fmt.Errorf("模板没有检查项，请先在模板中添加检查项")
	}

	if record.TranscriptText == "" {
		return nil, fmt.Errorf("记录还没有转写文本，请先执行转写")
	}

	// Build prompts
	systemPrompt := buildInspectionSystemPrompt()
	userPrompt := buildInspectionUserPrompt(record.TranscriptText, template)

	// Call LLM
	rawResponse, tokens, err := ChatCompletion(systemPrompt, userPrompt)
	if err != nil {
		return nil, err
	}

	// Parse JSON from LLM response
	parsed, err := parseInspectionJSON(rawResponse)
	if err != nil {
		return nil, err
	}

	// Build result model
	result := &model.InspectionResult{
		RecordID:          record.ID,
		TemplateID:        templateID,
		OverallConclusion: parsed.OverallConclusion,
		OverallScore:      parsed.OverallScore,
		Summary:           parsed.Summary,
		RawLLMResponse:    rawResponse,
		ModelUsed:         config.AppConfig.LLM.Model,
		TokensUsed:        tokens,
	}

	// Map items to item results, snapshot item names
	for _, item := range parsed.Items {
		result.Items = append(result.Items, model.ItemResult{
			ItemName:    item.ItemName,
			Verdict:     item.Verdict,
			Evidence:    item.Evidence,
			Confidence:  item.Confidence,
			AIReasoning: item.Reasoning,
		})
	}

	// Store result
	if err := s.store.CreateInspectionResult(result); err != nil {
		return nil, fmt.Errorf("保存质检结果失败: %w", err)
	}

	return result, nil
}

func buildInspectionSystemPrompt() string {
	return `你是一个燃气入户安检与客户拜访的合规质量审核专家。

你的任务：根据给定的「检查项清单」，逐项判断服务人员是否在「拜访记录」中执行了该检查项。

判定规则（三项取其一）：
- "通过"：记录中明确提到服务人员执行了该检查项，且有具体描述或动作。
- "未通过"：记录中提到了该检查项，但明确表示未执行、有问题、或不合规。
- "未提及"：记录中完全没有提到该检查项。

严格要求：
1. 对每个检查项都必须给出判定，不得跳过。
2. 必须引用记录中的原话作为证据（evidence 字段），不得凭空捏造。
3. 如果证据不足，判定为"未提及"而非"通过"。
4. 区分"提到要做"和"实际做了"：只有明确表示已执行才判"通过"。
5. 只返回 JSON，不要包含任何其他解释文字。

返回 JSON 格式：
{
  "overall_conclusion": "规范/不规范/需复核",
  "overall_score": 85,
  "summary": "总体评价一句话",
  "items": [
    {
      "item_name": "检查项名称",
      "verdict": "通过/未通过/未提及",
      "evidence": "记录中的原话引用",
      "confidence": 0.9,
      "reasoning": "判定理由"
    }
  ]
}

注意：
- overall_conclusion 只能取 "规范"、"不规范"、"需复核" 三个值之一。
- overall_score 是 0-100 的整数。
- confidence 是 0-1 之间的小数。`
}

func buildInspectionUserPrompt(transcript string, template *model.InspectionTemplate) string {
	var sb strings.Builder
	sb.WriteString("以下是服务人员的拜访记录：\n\n")
	sb.WriteString(transcript)
	sb.WriteString("\n\n")

	sb.WriteString("请逐项检查以下清单：\n\n")
	for i, item := range template.Items {
		sb.WriteString(fmt.Sprintf("%d. %s", i+1, item.Name))
		if item.Description != "" {
			sb.WriteString("（" + item.Description + "）")
		}
		if item.IsRequired {
			sb.WriteString("【必检】")
		}
		sb.WriteString("\n")
	}

	return sb.String()
}

func parseInspectionJSON(content string) (*InspectionResultJSON, error) {
	// Try to extract JSON from markdown code blocks
	trimmed := strings.TrimSpace(content)

	// Extract from ```json ... ``` block
	if strings.Contains(trimmed, "```") {
		start := strings.Index(trimmed, "```")
		trimmed = trimmed[start+3:]
		if idx := strings.Index(trimmed, "```"); idx > 0 {
			trimmed = trimmed[:idx]
		}
		trimmed = strings.TrimSpace(trimmed)
		// Remove leading "json" if present
		if strings.HasPrefix(trimmed, "json") {
			trimmed = strings.TrimSpace(trimmed[4:])
		}
	}

	// Extract from first { to last }
	firstBrace := strings.Index(trimmed, "{")
	lastBrace := strings.LastIndex(trimmed, "}")
	if firstBrace >= 0 && lastBrace > firstBrace {
		trimmed = trimmed[firstBrace : lastBrace+1]
	}

	var result InspectionResultJSON
	if err := json.Unmarshal([]byte(trimmed), &result); err != nil {
		return nil, fmt.Errorf("解析质检结果失败: %w", err)
	}

	return &result, nil
}


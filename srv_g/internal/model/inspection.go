package model

import (
	"time"
)

// InspectionResult represents the overall result of an AI inspection
type InspectionResult struct {
	ID                int64        `json:"id" gorm:"primaryKey;autoIncrement"`
	RecordID          string       `json:"record_id" gorm:"index;not null"`
	TemplateID        int64        `json:"template_id"`
	OverallConclusion string       `json:"overall_conclusion"` // 规范/不规范/需复核
	OverallScore      int          `json:"overall_score"`       // 0-100
	Summary           string       `json:"summary"`
	RawLLMResponse    string       `json:"raw_llm_response"`
	ModelUsed         string       `json:"model_used"`
	TokensUsed        int          `json:"tokens_used"`
	CreatedAt         time.Time    `json:"created_at"`
	Items             []ItemResult `json:"items,omitempty" gorm:"foreignKey:InspectionResultID"`
}

func (InspectionResult) TableName() string { return "inspection_results" }

// ItemResult represents a per-item verdict from the AI
type ItemResult struct {
	ID                 int64   `json:"id" gorm:"primaryKey;autoIncrement"`
	InspectionResultID int64   `json:"inspection_result_id" gorm:"index;not null"`
	ItemID             int64   `json:"item_id"`
	ItemName           string  `json:"item_name"` // snapshot
	Verdict            string  `json:"verdict"`   // 通过/未通过/未提及
	Evidence           string  `json:"evidence"`
	Confidence         float64 `json:"confidence"`
	AIReasoning        string  `json:"ai_reasoning"`
}

func (ItemResult) TableName() string { return "item_results" }
package model

import (
	"time"
)

// Record represents a voice recording uploaded from the app
type Record struct {
	ID                string    `json:"id" gorm:"primaryKey"`
	Title             string    `json:"title"`
	Description       string    `json:"description"`
	InspectorName     string    `json:"inspector_name"`
	CustomerName      string    `json:"customer_name"`
	CustomerAddress   string    `json:"customer_address"`
	InspectionDate    time.Time `json:"inspection_date"`
	SourceType        string    `json:"source_type" gorm:"default:RECORDING"`
	AudioFilePath     string    `json:"audio_file_path"`
	AudioDuration     float64   `json:"audio_duration"`
	TranscriptText    string    `json:"transcript_text"`
	TranscriptStatus  string    `json:"transcript_status" gorm:"default:PENDING"`
	InspectionStatus  string    `json:"inspection_status" gorm:"default:NONE"`
	CreatedAt         time.Time `json:"created_at"`
	UpdatedAt         time.Time `json:"updated_at"`
}

func (Record) TableName() string { return "records" }

// RecordCreateRequest is the JSON part of the upload request
type RecordCreateRequest struct {
	ID              string `json:"id"`               // optional, App can provide UUID
	Title           string `json:"title"`
	Description     string `json:"description"`
	InspectorName   string `json:"inspector_name"`
	CustomerName    string `json:"customer_name"`
	CustomerAddress string `json:"customer_address"`
	InspectionDate  string `json:"inspection_date"`  // RFC3339 or "2006-01-02T15:04"
	SourceType      string `json:"source_type"`      // RECORDING / IMPORT
}
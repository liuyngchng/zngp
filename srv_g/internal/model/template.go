package model

import (
	"time"
)

// InspectionTemplate represents a checklist template
type InspectionTemplate struct {
	ID          int64     `json:"id" gorm:"primaryKey;autoIncrement"`
	Name        string    `json:"name" gorm:"not null"`
	Description string    `json:"description"`
	Category    string    `json:"category"` // gas_safety / customer_visit
	IsActive    bool      `json:"is_active" gorm:"default:1"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
	Items       []InspectionItem `json:"items,omitempty" gorm:"foreignKey:TemplateID"`
}

func (InspectionTemplate) TableName() string { return "inspection_templates" }

// InspectionItem represents a single checklist item
type InspectionItem struct {
	ID          int64     `json:"id" gorm:"primaryKey;autoIncrement"`
	TemplateID  int64     `json:"template_id" gorm:"index;not null"`
	ItemNumber  int       `json:"item_number"`
	Name        string    `json:"name" gorm:"not null"`
	Description string    `json:"description"`
	Category    string    `json:"category"`
	IsRequired  bool      `json:"is_required" gorm:"default:1"`
	Weight      int       `json:"weight" gorm:"default:1"`
	CreatedAt   time.Time `json:"created_at"`
}

func (InspectionItem) TableName() string { return "inspection_items" }
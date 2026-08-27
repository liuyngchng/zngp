package store

import (
	"github.com/zngp/server/internal/model"
)

// ── User ──────────────────────────────────────────────────────────────

func (s *Store) FindUserByUsername(username string) (*model.User, error) {
	var user model.User
	err := s.DB.Where("username = ?", username).First(&user).Error
	if err != nil {
		return nil, err
	}
	return &user, nil
}

func (s *Store) CountUsers() (int64, error) {
	var count int64
	err := s.DB.Model(&model.User{}).Count(&count).Error
	return count, err
}

func (s *Store) CreateUser(user *model.User) error {
	return s.DB.Create(user).Error
}

func (s *Store) UpdateUserPassword(userID int64, hash string) error {
	return s.DB.Model(&model.User{}).Where("id = ?", userID).Update("password_hash", hash).Error
}

// ── Record ────────────────────────────────────────────────────────────

func (s *Store) CreateRecord(record *model.Record) error {
	return s.DB.Create(record).Error
}

func (s *Store) GetRecord(id string) (*model.Record, error) {
	var record model.Record
	err := s.DB.Where("id = ?", id).First(&record).Error
	if err != nil {
		return nil, err
	}
	return &record, nil
}

func (s *Store) ListRecords(page, pageSize int, keyword string) ([]model.Record, int64, error) {
	var records []model.Record
	var total int64

	query := s.DB.Model(&model.Record{})

	if keyword != "" {
		like := "%" + keyword + "%"
		query = query.Where("title LIKE ? OR customer_name LIKE ? OR inspector_name LIKE ? OR transcript_text LIKE ?",
			like, like, like, like)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&records).Error
	return records, total, err
}

func (s *Store) UpdateRecordTranscript(recordID, transcript, status string) error {
	return s.DB.Model(&model.Record{}).Where("id = ?", recordID).
		Updates(map[string]interface{}{
			"transcript_text":   transcript,
			"transcript_status": status,
		}).Error
}

func (s *Store) UpdateRecordInspectionStatus(recordID, status string) error {
	return s.DB.Model(&model.Record{}).Where("id = ?", recordID).
		Update("inspection_status", status).Error
}

func (s *Store) DeleteRecord(id string) error {
	return s.DB.Where("id = ?", id).Delete(&model.Record{}).Error
}

// ── Template ──────────────────────────────────────────────────────────

func (s *Store) CreateTemplate(t *model.InspectionTemplate) error {
	return s.DB.Create(t).Error
}

func (s *Store) GetTemplate(id int64) (*model.InspectionTemplate, error) {
	var t model.InspectionTemplate
	err := s.DB.Preload("Items").Where("id = ?", id).First(&t).Error
	if err != nil {
		return nil, err
	}
	return &t, nil
}

func (s *Store) ListTemplates() ([]model.InspectionTemplate, error) {
	var templates []model.InspectionTemplate
	err := s.DB.Preload("Items").Order("id ASC").Find(&templates).Error
	return templates, err
}

func (s *Store) UpdateTemplate(t *model.InspectionTemplate) error {
	return s.DB.Model(&model.InspectionTemplate{}).Where("id = ?", t.ID).
		Updates(map[string]interface{}{
			"name":        t.Name,
			"description": t.Description,
			"is_active":   t.IsActive,
		}).Error
}

func (s *Store) DeleteTemplate(id int64) error {
	return s.DB.Where("id = ?", id).Delete(&model.InspectionTemplate{}).Error
}

func (s *Store) CreateItem(item *model.InspectionItem) error {
	return s.DB.Create(item).Error
}

func (s *Store) UpdateItem(item *model.InspectionItem) error {
	return s.DB.Model(&model.InspectionItem{}).Where("id = ?", item.ID).
		Updates(map[string]interface{}{
			"name":        item.Name,
			"description": item.Description,
			"category":    item.Category,
			"is_required": item.IsRequired,
			"weight":      item.Weight,
			"item_number": item.ItemNumber,
		}).Error
}

func (s *Store) DeleteItem(id int64) error {
	return s.DB.Where("id = ?", id).Delete(&model.InspectionItem{}).Error
}

func (s *Store) GetItem(id int64) (*model.InspectionItem, error) {
	var item model.InspectionItem
	err := s.DB.Where("id = ?", id).First(&item).Error
	if err != nil {
		return nil, err
	}
	return &item, nil
}

// ── Inspection ────────────────────────────────────────────────────────

func (s *Store) CreateInspectionResult(r *model.InspectionResult) error {
	return s.DB.Create(r).Error
}

func (s *Store) GetInspectionResult(id int64) (*model.InspectionResult, error) {
	var result model.InspectionResult
	err := s.DB.Preload("Items").Where("id = ?", id).First(&result).Error
	if err != nil {
		return nil, err
	}
	return &result, nil
}

func (s *Store) GetInspectionByRecordID(recordID string) (*model.InspectionResult, error) {
	var result model.InspectionResult
	err := s.DB.Preload("Items").Where("record_id = ?", recordID).Order("created_at DESC").First(&result).Error
	if err != nil {
		return nil, err
	}
	return &result, nil
}

func (s *Store) CreateItemResults(items []model.ItemResult) error {
	return s.DB.Create(&items).Error
}

// ── Stats ─────────────────────────────────────────────────────────────

func (s *Store) GetOverview() (totalRecords int64, compliantCount int64, nonCompliantCount int64, reviewCount int64, err error) {
	err = s.DB.Model(&model.Record{}).Count(&totalRecords).Error
	if err != nil {
		return
	}
	err = s.DB.Model(&model.InspectionResult{}).Where("overall_conclusion = ?", "规范").Count(&compliantCount).Error
	if err != nil {
		return
	}
	err = s.DB.Model(&model.InspectionResult{}).Where("overall_conclusion = ?", "不规范").Count(&nonCompliantCount).Error
	if err != nil {
		return
	}
	err = s.DB.Model(&model.InspectionResult{}).Where("overall_conclusion = ?", "需复核").Count(&reviewCount).Error
	return
}
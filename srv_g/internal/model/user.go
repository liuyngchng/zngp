package model

import (
	"time"
)

// User represents an admin user
type User struct {
	ID                 int64      `json:"id" gorm:"primaryKey;autoIncrement"`
	Username           string     `json:"username" gorm:"uniqueIndex;not null"`
	PasswordHash       string     `json:"-" gorm:"not null"`
	Role               string     `json:"role" gorm:"default:admin"`
	MustChangePassword bool       `json:"must_change_password" gorm:"default:false"`
	PasswordExpiresAt  *time.Time `json:"password_expires_at,omitempty"`
	CreatedAt          time.Time  `json:"created_at"`
}

func (User) TableName() string { return "users" }
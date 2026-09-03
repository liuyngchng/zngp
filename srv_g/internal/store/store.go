package store

import (
	"log"
	"os"
	"path/filepath"

	"github.com/glebarez/sqlite"
	"github.com/zngp/server/internal/model"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// Store wraps the GORM database handle and provides data access methods.
type Store struct {
	DB *gorm.DB
}

func New(dbPath string) (*Store, error) {
	// Ensure directory exists
	dir := filepath.Dir(dbPath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}

	db, err := gorm.Open(sqlite.Open(dbPath), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Warn),
	})
	if err != nil {
		return nil, err
	}

	// Enable foreign keys for SQLite
	db.Exec("PRAGMA foreign_keys = ON")

	s := &Store{DB: db}
	if err := s.Migrate(); err != nil {
		return nil, err
	}

	return s, nil
}

func (s *Store) Migrate() error {
	if err := s.DB.AutoMigrate(
		&model.User{},
		&model.Record{},
		&model.InspectionTemplate{},
		&model.InspectionItem{},
		&model.InspectionResult{},
		&model.ItemResult{},
	); err != nil {
		return err
	}
	log.Println("db_migration_done")
	return nil
}
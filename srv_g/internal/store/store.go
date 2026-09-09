package store

import (
	"os"
	"path/filepath"
	"time"

	"github.com/glebarez/sqlite"
	"github.com/zngp/server/config"
	"github.com/zngp/server/internal/logx"
	"github.com/zngp/server/internal/model"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// Store wraps the GORM database handle and provides data access methods.
type Store struct {
	DB *gorm.DB
}

// New opens a database connection based on cfg.
// If cfg.Type is "mysql" and a DSN is configured, MySQL is used;
// otherwise it falls back to SQLite (the default).
func New(cfg config.DatabaseConfig) (*Store, error) {
	var dialector gorm.Dialector

	if cfg.Type == "mysql" && cfg.DSN != "" {
		dialector = mysql.Open(cfg.DSN)
	} else {
		if cfg.Type == "mysql" && cfg.DSN == "" {
			logx.Warn("mysql_dsn_empty_falling_back_to_sqlite")
		}

		dbPath := cfg.Path
		if dbPath == "" {
			dbPath = "./data/voice_note.db"
		}

		// Ensure directory exists
		dir := filepath.Dir(dbPath)
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, err
		}

		dialector = sqlite.Open(dbPath)
	}

	db, err := gorm.Open(dialector, &gorm.Config{
		Logger: logger.Default.LogMode(logger.Warn),
	})
	if err != nil {
		return nil, err
	}

	// Configure the underlying database/sql connection pool.
	// MySQL defaults (MaxOpenConns = 0) allow unbounded growth, which can
	// exhaust the database server, so cap it explicitly. SQLite serializes
	// writes and keeps a single connection.
	sqlDB, err := db.DB()
	if err != nil {
		return nil, err
	}
	if cfg.Type == "mysql" {
		sqlDB.SetMaxOpenConns(25)
		sqlDB.SetMaxIdleConns(25)
		sqlDB.SetConnMaxLifetime(3 * time.Minute)
		sqlDB.SetConnMaxIdleTime(3 * time.Minute)
	} else {
		sqlDB.SetMaxOpenConns(1)
		sqlDB.SetMaxIdleConns(1)
		sqlDB.SetConnMaxLifetime(3 * time.Minute)
		sqlDB.SetConnMaxIdleTime(3 * time.Minute)
	}

	// Enable foreign keys for SQLite
	if cfg.Type != "mysql" {
		db.Exec("PRAGMA foreign_keys = ON")
	}

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
	logx.Info("db_migration_done")
	return nil
}
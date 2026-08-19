package config

import (
	"os"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Server   ServerConfig   `yaml:"server"`
	System   SystemConfig   `yaml:"system"`
	Database DatabaseConfig `yaml:"database"`
	Auth     AuthConfig     `yaml:"auth"`
	ASR      ASRConfig      `yaml:"asr"`
	LLM      LLMConfig      `yaml:"llm"`
	Upload   UploadConfig   `yaml:"upload"`
}

type ServerConfig struct {
	Port string `yaml:"port"`
	Host string `yaml:"host"`
}

type SystemConfig struct {
	Name string `yaml:"name"`
}

type DatabaseConfig struct {
	Path string `yaml:"path"`
}

type AuthConfig struct {
	Username  string `yaml:"username"`
	Password  string `yaml:"password"`
	JWTSecret string `yaml:"jwt_secret"`
}

type ASRConfig struct {
	Provider    string `yaml:"provider"`
	WorkspaceID string `yaml:"workspace_id"`
	APIKey      string `yaml:"api_key"`
	Model       string `yaml:"model"`
	BaseURL     string `yaml:"base_url"`
}

type LLMConfig struct {
	Provider string `yaml:"provider"`
	Endpoint string `yaml:"endpoint"`
	APIKey   string `yaml:"api_key"`
	Model    string `yaml:"model"`
}

type UploadConfig struct {
	MaxFileSizeMB int    `yaml:"max_file_size_mb"`
	StorageDir    string `yaml:"storage_dir"`
}

var AppConfig *Config

func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	cfg := &Config{}
	if err := yaml.Unmarshal(data, cfg); err != nil {
		return nil, err
	}

	// Defaults
	if cfg.Server.Port == "" {
		cfg.Server.Port = "8080"
	}
	if cfg.Server.Host == "" {
		cfg.Server.Host = "0.0.0.0"
	}
	if cfg.System.Name == "" {
		cfg.System.Name = "ZNGP 服务质量平台"
	}
	if cfg.Database.Path == "" {
		cfg.Database.Path = "./data/voice_note.db"
	}
	if cfg.Upload.MaxFileSizeMB == 0 {
		cfg.Upload.MaxFileSizeMB = 100
	}
	if cfg.Upload.StorageDir == "" {
		cfg.Upload.StorageDir = "./data/uploads"
	}
	if cfg.ASR.Model == "" {
		cfg.ASR.Model = "qwen3-asr-flash"
	}

	AppConfig = cfg
	return cfg, nil
}
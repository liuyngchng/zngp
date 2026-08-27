package handler

import (
	"os"
	"path/filepath"
)

func writeConfigFile(path string, data []byte) error {
	absPath, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	return os.WriteFile(absPath, data, 0o644)
}
package handler

import (
	"encoding/binary"
	"encoding/json"
	"io"
	"mime/multipart"
	"os"
	"path/filepath"
	"time"
)

func jsonUnmarshal(s string, v interface{}) error {
	return json.Unmarshal([]byte(s), v)
}

func parseTime(s string) (time.Time, error) {
	formats := []string{
		time.RFC3339,
		"2006-01-02T15:04",
		"2006-01-02T15:04:05",
		"2006-01-02 15:04:05",
		"2006-01-02",
	}
	for _, f := range formats {
		if t, err := time.Parse(f, s); err == nil {
			return t, nil
		}
	}
	return time.Time{}, nil
}

func saveUploadedFile(file multipart.File, path string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}

	dst, err := os.Create(path)
	if err != nil {
		return err
	}
	defer dst.Close()

	_, err = io.Copy(dst, file)
	return err
}

// wavDuration reads the WAV header to calculate duration in seconds.
// Returns 0 if the file cannot be read.
func wavDuration(path string) float64 {
	f, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer f.Close()

	// WAV header: RIFF(4) + filesize(4) + WAVE(4) + fmt(4) + fmtSize(4) + ...
	header := make([]byte, 44)
	n, _ := io.ReadFull(f, header)
	if n < 44 {
		return 0
	}

	// Check "RIFF" and "WAVE"
	if string(header[0:4]) != "RIFF" || string(header[8:12]) != "WAVE" {
		return 0
	}

	// Sample rate at offset 24 (4 bytes)
	sampleRate := binary.LittleEndian.Uint32(header[24:28])

	// Byte rate at offset 28 (4 bytes)
	byteRate := binary.LittleEndian.Uint32(header[28:32])

	// Data size at offset 40 (4 bytes)
	dataSize := binary.LittleEndian.Uint32(header[40:44])

	if byteRate == 0 {
		// Fallback: 16kHz * 16bit * 1ch = 32000 bytes/sec
		byteRate = 32000
	}

	_ = sampleRate
	return float64(dataSize) / float64(byteRate)
}
package logx

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// Level represents a log severity level.
type Level string

const (
	DEBUG Level = "debug"
	INFO  Level = "info"
	WARN  Level = "warn"
	ERROR Level = "error"
)

var defaultLogger *slog.Logger
var projectRoot string

// Init initializes the global logger with the given level and format.
// format: "text" or "json".
func Init(level Level, format string) {
	// Find project root (directory containing go.mod) for source shortening
	projectRoot = findProjectRoot()

	var l slog.Level
	switch level {
	case "debug":
		l = slog.LevelDebug
	case "info":
		l = slog.LevelInfo
	case "warn":
		l = slog.LevelWarn
	case "error":
		l = slog.LevelError
	default:
		l = slog.LevelInfo
	}

	var handler slog.Handler
	if format == "json" {
		opts := &slog.HandlerOptions{
			Level:     l,
			AddSource: true,
			ReplaceAttr: func(groups []string, a slog.Attr) slog.Attr {
				if a.Key == slog.SourceKey {
					if src, ok := a.Value.Any().(*slog.Source); ok {
						short := shortSource(src.File, src.Line)
						// override both File and Line for JSON output
						src.File = short
						src.Line = 0
					}
				}
				return a
			},
		}
		handler = slog.NewJSONHandler(os.Stderr, opts)
	} else {
		handler = newPatternHandler(os.Stderr, l)
	}

	defaultLogger = slog.New(handler)
	slog.SetDefault(defaultLogger)
}

// patternHandler formats log output as:
//
//	2006-01-02 15:04:05 [INFO] i/h/asr:108 message key=value
type patternHandler struct {
	w     io.Writer
	level slog.Level
	attrs []slog.Attr
	group string
}

func newPatternHandler(w io.Writer, level slog.Level) *patternHandler {
	return &patternHandler{w: w, level: level}
}

func (h *patternHandler) Enabled(_ context.Context, level slog.Level) bool {
	return level >= h.level
}

func (h *patternHandler) Handle(_ context.Context, r slog.Record) error {
	var buf strings.Builder

	// 2006-01-02 15:04:05
	buf.WriteString(r.Time.Format("2006-01-02 15:04:05"))

	// [INFO]
	levelStr := "INFO"
	switch r.Level {
	case slog.LevelDebug:
		levelStr = "DEBUG"
	case slog.LevelInfo:
		levelStr = "INFO"
	case slog.LevelWarn:
		levelStr = "WARN"
	case slog.LevelError:
		levelStr = "ERROR"
	}
	buf.WriteString(" [")
	buf.WriteString(levelStr)
	buf.WriteString("]")

	// i/h/asr:108
	if r.PC != 0 {
		fs := runtime.CallersFrames([]uintptr{r.PC})
		f, _ := fs.Next()
		if f.File != "" {
			buf.WriteString(" ")
			buf.WriteString(shortSource(f.File, f.Line))
		}
	}

	// message
	buf.WriteString(" ")
	buf.WriteString(r.Message)

	// key=value pairs
	r.Attrs(func(a slog.Attr) bool {
		buf.WriteString(" ")
		buf.WriteString(a.Key)
		buf.WriteString("=")
		buf.WriteString(fmt.Sprintf("%v", a.Value.Any()))
		return true
	})

	buf.WriteString("\n")
	_, err := h.w.Write([]byte(buf.String()))
	return err
}

// shortSource converts a full file path to a compact form.
// If the file is under the project root, the root prefix is stripped first.
// internal/handler/asr.go → i/h/asr:108
// main.go → main:108
func shortSource(file string, line int) string {
	// Strip project root prefix
	if projectRoot != "" && strings.HasPrefix(file, projectRoot) {
		file = file[len(projectRoot)+1:] // +1 for the trailing '/'
	}

	base := filepath.Base(file)
	name := strings.TrimSuffix(base, ".go")
	dir := filepath.Dir(file)
	if dir == "." {
		return fmt.Sprintf("%s:%d", name, line)
	}
	parts := strings.Split(filepath.ToSlash(dir), "/")
	var initials []string
	for _, p := range parts {
		if p != "" && p != "." {
			initials = append(initials, p[:1])
		}
	}
	initials = append(initials, name)
	return fmt.Sprintf("%s:%d", strings.Join(initials, "/"), line)
}

// findProjectRoot walks up from the current working directory to find go.mod.
func findProjectRoot() string {
	dir, err := os.Getwd()
	if err != nil {
		return ""
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "go.mod")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return ""
		}
		dir = parent
	}
}

func (h *patternHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	newH := *h
	newH.attrs = append(newH.attrs, attrs...)
	return &newH
}

func (h *patternHandler) WithGroup(name string) slog.Handler {
	newH := *h
	newH.group = name
	return &newH
}

// ---- convenience wrappers ----

func Debug(msg string, args ...any) {
	logAt(slog.LevelDebug, msg, args...)
}

func Info(msg string, args ...any) {
	logAt(slog.LevelInfo, msg, args...)
}

func Warn(msg string, args ...any) {
	logAt(slog.LevelWarn, msg, args...)
}

func Error(msg string, args ...any) {
	logAt(slog.LevelError, msg, args...)
}

// Fatal logs at ERROR level and exits with code 1.
func Fatal(msg string, args ...any) {
	logAt(slog.LevelError, msg, args...)
	os.Exit(1)
}

func logAt(level slog.Level, msg string, args ...any) {
	if defaultLogger == nil {
		// fallback to slog default
		slog.Log(context.Background(), level, msg, args...)
		return
	}
	var pcs [1]uintptr
	runtime.Callers(3, pcs[:])
	r := slog.NewRecord(time.Now(), level, msg, pcs[0])
	r.Add(args...)
	defaultLogger.Handler().Handle(context.Background(), r)
}
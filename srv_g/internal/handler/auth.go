package handler

import (
	"crypto/rand"
	"math/big"
	"net/http"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/zngp/server/config"
	"github.com/zngp/server/internal/logx"
	"github.com/zngp/server/internal/middleware"
	"github.com/zngp/server/internal/model"
	"github.com/zngp/server/internal/store"
	"golang.org/x/crypto/bcrypt"
)

type AuthHandler struct {
	store *store.Store
}

func NewAuthHandler(s *store.Store) *AuthHandler {
	return &AuthHandler{store: s}
}

type LoginRequest struct {
	Username string `json:"username" binding:"required"`
	Password string `json:"password" binding:"required"`
}

type LoginResponse struct {
	Token              string `json:"token"`
	Username           string `json:"username"`
	MustChangePassword bool   `json:"must_change_password,omitempty"`
}

func (h *AuthHandler) Login(c *gin.Context) {
	var req LoginRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		logx.Warn("login_failed_missing_params")
		c.JSON(http.StatusBadRequest, gin.H{"error": "请输入用户名和密码"})
		return
	}

	user, err := h.store.FindUserByUsername(req.Username)
	if err != nil {
		logx.Warn("login_failed_user_not_found", "username", req.Username)
		c.JSON(http.StatusUnauthorized, gin.H{"error": "用户名或密码错误"})
		return
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.Password)); err != nil {
		logx.Warn("login_failed_wrong_password", "username", req.Username)
		c.JSON(http.StatusUnauthorized, gin.H{"error": "用户名或密码错误"})
		return
	}

	// Check if initial password has expired
	if user.MustChangePassword && user.PasswordExpiresAt != nil {
		if time.Now().After(*user.PasswordExpiresAt) {
			logx.Warn("login_failed_password_expired", "username", req.Username)
			c.JSON(http.StatusUnauthorized, gin.H{"error": "初始密码已过期，请联系管理员重置"})
			return
		}
	}

	token, err := middleware.GenerateTokenWithFlags(user.ID, user.Username, user.MustChangePassword)
	if err != nil {
		logx.Error("login_failed_token_generation", "username", req.Username, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "生成令牌失败"})
		return
	}

	// 浏览器登录：通过 Cookie 传递 token，同时返回 JSON 给 App 端
	middleware.SetTokenCookie(c, token)

	logx.Info("login_success", "username", user.Username, "must_change_password", user.MustChangePassword)

	resp := LoginResponse{
		Token:              token,
		Username:           user.Username,
		MustChangePassword: user.MustChangePassword,
	}

	c.JSON(http.StatusOK, resp)
}

type ChangePasswordRequest struct {
	OldPassword string `json:"old_password" binding:"required"`
	NewPassword string `json:"new_password" binding:"required"`
}

func (h *AuthHandler) ChangePassword(c *gin.Context) {
	var req ChangePasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		logx.Warn("change_password_failed_invalid_params")
		c.JSON(http.StatusBadRequest, gin.H{"error": "请输入旧密码和新密码"})
		return
	}

	// Validate password strength
	if msg := validatePassword(req.NewPassword); msg != "" {
		logx.Warn("change_password_failed_weak_password")
		c.JSON(http.StatusBadRequest, gin.H{"error": msg})
		return
	}

	userID := c.GetInt64("user_id")
	username := c.GetString("username")
	user, err := h.store.FindUserByUsername(username)
	if err != nil {
		logx.Error("change_password_failed_user_not_found", "username", username)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "用户不存在"})
		return
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.OldPassword)); err != nil {
		logx.Warn("change_password_failed_wrong_old_password", "username", username)
		c.JSON(http.StatusUnauthorized, gin.H{"error": "旧密码错误"})
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(req.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		logx.Error("change_password_failed_hash_error", "username", username, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "密码加密失败"})
		return
	}

	if err := h.store.UpdateUserPassword(userID, string(hash)); err != nil {
		logx.Error("change_password_failed_db_error", "username", username, "err", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "密码更新失败"})
		return
	}

	logx.Info("change_password_success", "username", username, "user_id", userID)

	// Issue a new token with must_change_password=false
	newToken, _ := middleware.GenerateTokenWithFlags(userID, user.Username, false)
	middleware.SetTokenCookie(c, newToken)

	c.JSON(http.StatusOK, gin.H{
		"message": "密码修改成功",
		"token":   newToken,
	})
}

// Status returns the current auth state (used by login page to detect must_change_password)
func (h *AuthHandler) Status(c *gin.Context) {
	mustChange := c.GetBool("must_change_password")
	c.JSON(http.StatusOK, gin.H{
		"username":             c.GetString("username"),
		"must_change_password": mustChange,
	})
}

// EnsureDefaultAdmin creates the default admin user if no users exist.
// Uses a randomly generated password (valid for 2 hours) instead of cfg.yml.
func EnsureDefaultAdmin(s *store.Store) error {
	count, err := s.CountUsers()
	if err != nil {
		return err
	}
	if count > 0 {
		return nil
	}

	password := generateRandomPassword(15)
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return err
	}

	expiresAt := time.Now().Add(2 * time.Hour)

	username := config.AppConfig.Auth.Username
	if username == "" {
		username = "admin"
	}

	user := &model.User{
		Username:           username,
		PasswordHash:       string(hash),
		Role:               "admin",
		MustChangePassword: true,
		PasswordExpiresAt:  &expiresAt,
	}

	logx.Info("initial_admin_password_generated", "password", password, "expires_at", expiresAt.Format("2006-01-02T15:04:05"))

	return s.CreateUser(user)
}

// validatePassword checks password strength: at least 15 characters,
// must contain at least one letter, one digit, and one symbol.
func validatePassword(password string) string {
	if utf8.RuneCountInString(password) < 15 {
		return "密码长度至少15位"
	}

	var hasLetter, hasDigit, hasSymbol bool
	for _, r := range password {
		switch {
		case (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z'):
			hasLetter = true
		case r >= '0' && r <= '9':
			hasDigit = true
		default:
			hasSymbol = true
		}
	}
	if !hasLetter {
		return "密码必须包含至少一个字母"
	}
	if !hasDigit {
		return "密码必须包含至少一个数字"
	}
	if !hasSymbol {
		return "密码必须包含至少一个特殊符号"
	}
	return ""
}

const passwordChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*"

// generateRandomPassword generates a password of the given length that always
// contains at least one letter, one digit, and one symbol (when length >= 3).
func generateRandomPassword(length int) string {
	b := make([]byte, length)
	for i := range b {
		n, err := rand.Int(rand.Reader, big.NewInt(int64(len(passwordChars))))
		if err != nil {
			// Fallback: use time-based pseudo-random
			b[i] = passwordChars[i%len(passwordChars)]
			continue
		}
		b[i] = passwordChars[n.Int64()]
	}

	// Guarantee complexity: place one letter, one digit, and one symbol
	// at fixed positions, then shuffle is not required since the rest is random.
	letterPos := 0
	digitPos := 1
	symbolPos := 2
	if length >= 3 {
		b[letterPos] = passwordChars[randIndex(52)]     // a-zA-Z
		b[digitPos] = passwordChars[52+randIndex(10)]   // 0-9
		b[symbolPos] = passwordChars[62+randIndex(len(passwordChars)-62)] // symbols
	}
	return string(b)
}

func randIndex(n int) int {
	if n <= 0 {
		return 0
	}
	v, err := rand.Int(rand.Reader, big.NewInt(int64(n)))
	if err != nil {
		return 0
	}
	return int(v.Int64())
}
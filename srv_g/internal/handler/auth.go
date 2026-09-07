package handler

import (
	"crypto/rand"
	"log"
	"math/big"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
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
		c.JSON(http.StatusBadRequest, gin.H{"error": "请输入用户名和密码"})
		return
	}

	user, err := h.store.FindUserByUsername(req.Username)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "用户名或密码错误"})
		return
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.Password)); err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "用户名或密码错误"})
		return
	}

	// Check if initial password has expired
	if user.MustChangePassword && user.PasswordExpiresAt != nil {
		if time.Now().After(*user.PasswordExpiresAt) {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "初始密码已过期，请联系管理员重置"})
			return
		}
	}

	token, err := middleware.GenerateTokenWithFlags(user.ID, user.Username, user.MustChangePassword)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "生成令牌失败"})
		return
	}

	// 浏览器登录：通过 Cookie 传递 token，同时返回 JSON 给 App 端
	middleware.SetTokenCookie(c, token)

	resp := LoginResponse{
		Token:              token,
		Username:           user.Username,
		MustChangePassword: user.MustChangePassword,
	}

	c.JSON(http.StatusOK, resp)
}

type ChangePasswordRequest struct {
	OldPassword string `json:"old_password" binding:"required"`
	NewPassword string `json:"new_password" binding:"required,min=6"`
}

func (h *AuthHandler) ChangePassword(c *gin.Context) {
	var req ChangePasswordRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请输入旧密码和新密码（新密码至少6位）"})
		return
	}

	userID := c.GetInt64("user_id")
	user, err := h.store.FindUserByUsername(c.GetString("username"))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "用户不存在"})
		return
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.OldPassword)); err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "旧密码错误"})
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(req.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "密码加密失败"})
		return
	}

	if err := h.store.UpdateUserPassword(userID, string(hash)); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "密码更新失败"})
		return
	}

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

	password := generateRandomPassword(12)
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return err
	}

	expiresAt := time.Now().Add(2 * time.Hour)

	user := &model.User{
		Username:           "admin",
		PasswordHash:       string(hash),
		Role:               "admin",
		MustChangePassword: true,
		PasswordExpiresAt:  &expiresAt,
	}

	log.Println("============================================")
	log.Printf("  初始管理员密码: %s", password)
	log.Printf("  有效期: 2小时 (至 %s)", expiresAt.Format("15:04:05"))
	log.Println("  首次登录后必须修改密码")
	log.Println("============================================")

	return s.CreateUser(user)
}

const passwordChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*"

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
	return string(b)
}
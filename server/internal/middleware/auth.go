package middleware

import (
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/zngp/server/config"
)

var (
	ErrTokenInvalid = jwt.ErrSignatureInvalid
	ErrTokenExpired = jwt.ErrTokenExpired
)

const CookieName = "zngp_token"

type Claims struct {
	UserID   int64  `json:"user_id"`
	Username string `json:"username"`
	jwt.RegisteredClaims
}

func GenerateToken(userID int64, username string) (string, error) {
	claims := &Claims{
		UserID:   userID,
		Username: username,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(72 * time.Hour)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
		},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(config.AppConfig.Auth.JWTSecret))
}

func ParseToken(tokenString string) (*Claims, error) {
	token, err := jwt.ParseWithClaims(tokenString, &Claims{}, func(t *jwt.Token) (interface{}, error) {
		return []byte(config.AppConfig.Auth.JWTSecret), nil
	})
	if err != nil {
		return nil, err
	}
	if claims, ok := token.Claims.(*Claims); ok && token.Valid {
		return claims, nil
	}
	return nil, ErrTokenInvalid
}

// SetTokenCookie writes the JWT token as an HttpOnly cookie
func SetTokenCookie(c *gin.Context, token string) {
	c.SetCookie(CookieName, token, 72*3600, "/", "", false, true)
}

// ClearTokenCookie removes the auth cookie
func ClearTokenCookie(c *gin.Context) {
	c.SetCookie(CookieName, "", -1, "/", "", false, true)
}

// extractToken tries to get the JWT from Authorization header, then Cookie
func extractToken(c *gin.Context) string {
	// Header first (for API calls from App)
	header := c.GetHeader("Authorization")
	if header != "" {
		parts := strings.SplitN(header, " ", 2)
		if len(parts) == 2 && parts[0] == "Bearer" {
			return parts[1]
		}
	}
	// Cookie second (for Web browser)
	if cookie, err := c.Cookie(CookieName); err == nil && cookie != "" {
		return cookie
	}
	return ""
}

// AuthRequired extracts the JWT token from Header or Cookie.
// On success, issues a refreshed token (sliding expiration)
// in both X-New-Token header and Set-Cookie.
func AuthRequired() gin.HandlerFunc {
	return func(c *gin.Context) {
		tokenStr := extractToken(c)
		if tokenStr == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing authorization header"})
			return
		}

		claims, err := ParseToken(tokenStr)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired token"})
			return
		}

		c.Set("user_id", claims.UserID)
		c.Set("username", claims.Username)

		// 滑动续期
		newToken, _ := GenerateToken(claims.UserID, claims.Username)
		c.Header("X-New-Token", newToken)
		SetTokenCookie(c, newToken)

		c.Next()
	}
}

// AuthWebRequired redirects to /login if not authenticated.
// Supports both Cookie (browser) and Header (JS fetch) auth.
func AuthWebRequired() gin.HandlerFunc {
	return func(c *gin.Context) {
		tokenStr := extractToken(c)
		if tokenStr == "" {
			c.Redirect(http.StatusFound, "/login")
			c.Abort()
			return
		}

		claims, err := ParseToken(tokenStr)
		if err != nil {
			ClearTokenCookie(c)
			c.Redirect(http.StatusFound, "/login")
			c.Abort()
			return
		}

		c.Set("user_id", claims.UserID)
		c.Set("username", claims.Username)

		// 滑动续期
		newToken, _ := GenerateToken(claims.UserID, claims.Username)
		SetTokenCookie(c, newToken)

		c.Next()
	}
}
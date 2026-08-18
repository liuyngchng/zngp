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

// AuthRequired extracts the JWT token, and on success,
// issues a refreshed token (sliding expiration) in the X-New-Token response header.
func AuthRequired() gin.HandlerFunc {
	return func(c *gin.Context) {
		header := c.GetHeader("Authorization")
		if header == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing authorization header"})
			return
		}

		parts := strings.SplitN(header, " ", 2)
		if len(parts) != 2 || parts[0] != "Bearer" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid authorization format"})
			return
		}

		claims, err := ParseToken(parts[1])
		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired token"})
			return
		}

		c.Set("user_id", claims.UserID)
		c.Set("username", claims.Username)

		// 滑动续期：每次请求都签发新 token，有效期重置为 72 小时
		newToken, err := GenerateToken(claims.UserID, claims.Username)
		if err == nil {
			c.Header("X-New-Token", newToken)
		}

		c.Next()
	}
}

// AuthWebRequired is a web-page version: redirects to /login if not authenticated
func AuthWebRequired() gin.HandlerFunc {
	return func(c *gin.Context) {
		header := c.GetHeader("Authorization")
		if header != "" {
			parts := strings.SplitN(header, " ", 2)
			if len(parts) == 2 && parts[0] == "Bearer" {
				claims, err := ParseToken(parts[1])
				if err == nil {
					c.Set("user_id", claims.UserID)
					c.Set("username", claims.Username)

					// 滑动续期
					newToken, _ := GenerateToken(claims.UserID, claims.Username)
					c.Header("X-New-Token", newToken)

					c.Next()
					return
				}
			}
		}
		c.Next()
	}
}
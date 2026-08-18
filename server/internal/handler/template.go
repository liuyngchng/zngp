package handler

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/zngp/server/internal/model"
	"github.com/zngp/server/internal/store"
)

type TemplateHandler struct {
	store *store.Store
}

func NewTemplateHandler(s *store.Store) *TemplateHandler {
	return &TemplateHandler{store: s}
}

func (h *TemplateHandler) List(c *gin.Context) {
	templates, err := h.store.ListTemplates()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "查询模板失败"})
		return
	}
	c.JSON(http.StatusOK, templates)
}

func (h *TemplateHandler) Create(c *gin.Context) {
	var t model.InspectionTemplate
	if err := c.ShouldBindJSON(&t); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求格式错误"})
		return
	}

	t.CreatedAt = time.Now()
	t.UpdatedAt = time.Now()

	if err := h.store.CreateTemplate(&t); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "创建模板失败"})
		return
	}

	c.JSON(http.StatusOK, t)
}

func (h *TemplateHandler) Get(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "无效的模板ID"})
		return
	}

	t, err := h.store.GetTemplate(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "模板不存在"})
		return
	}

	c.JSON(http.StatusOK, t)
}

func (h *TemplateHandler) Update(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "无效的模板ID"})
		return
	}

	var t model.InspectionTemplate
	if err := c.ShouldBindJSON(&t); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求格式错误"})
		return
	}

	t.ID = id
	t.UpdatedAt = time.Now()

	if err := h.store.UpdateTemplate(&t); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "更新模板失败"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "更新成功"})
}

func (h *TemplateHandler) Delete(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "无效的模板ID"})
		return
	}

	if err := h.store.DeleteTemplate(id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "删除模板失败"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "已删除"})
}

func (h *TemplateHandler) CreateItem(c *gin.Context) {
	templateID, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "无效的模板ID"})
		return
	}

	var item model.InspectionItem
	if err := c.ShouldBindJSON(&item); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求格式错误"})
		return
	}

	item.TemplateID = templateID
	item.CreatedAt = time.Now()

	if err := h.store.CreateItem(&item); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "添加检查项失败"})
		return
	}

	c.JSON(http.StatusOK, item)
}

func (h *TemplateHandler) UpdateItem(c *gin.Context) {
	itemID, err := strconv.ParseInt(c.Param("iid"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "无效的检查项ID"})
		return
	}

	var item model.InspectionItem
	if err := c.ShouldBindJSON(&item); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "请求格式错误"})
		return
	}

	item.ID = itemID

	if err := h.store.UpdateItem(&item); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "更新检查项失败"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "更新成功"})
}

func (h *TemplateHandler) DeleteItem(c *gin.Context) {
	itemID, err := strconv.ParseInt(c.Param("iid"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "无效的检查项ID"})
		return
	}

	if err := h.store.DeleteItem(itemID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "删除检查项失败"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "已删除"})
}

// Helper for config handler to unmarshal JSON
func bindJSON(c *gin.Context, v interface{}) error {
	return json.NewDecoder(c.Request.Body).Decode(v)
}
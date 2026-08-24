package com.rd.zngp.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * InspectionTemplate model, mirroring server/internal/model/template.go
 */
public class InspectionTemplate {

    @JsonProperty("id")
    public long id;

    @JsonProperty("name")
    public String name;

    @JsonProperty("description")
    public String description;

    @JsonProperty("category")
    public String category;

    @JsonProperty("is_active")
    public boolean isActive = true;

    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    public LocalDateTime createdAt;

    @JsonProperty("updated_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    public LocalDateTime updatedAt;

    @JsonProperty("items")
    public List<InspectionItem> items = new ArrayList<>();
}
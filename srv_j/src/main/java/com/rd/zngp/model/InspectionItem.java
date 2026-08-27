package com.rd.zngp.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * InspectionItem model, mirroring server/internal/model/template.go InspectionItem
 */
public class InspectionItem {

    @JsonProperty("id")
    public long id;

    @JsonProperty("template_id")
    public long templateId;

    @JsonProperty("item_number")
    public int itemNumber;

    @JsonProperty("name")
    public String name;

    @JsonProperty("description")
    public String description;

    @JsonProperty("category")
    public String category;

    @JsonProperty("is_required")
    public boolean isRequired = true;

    @JsonProperty("weight")
    public int weight = 1;

    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    public LocalDateTime createdAt;
}
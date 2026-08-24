package com.rd.zngp.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * InspectionResult model, mirroring server/internal/model/inspection.go
 */
public class InspectionResult {

    @JsonProperty("id")
    public long id;

    @JsonProperty("record_id")
    public String recordId;

    @JsonProperty("template_id")
    public long templateId;

    @JsonProperty("overall_conclusion")
    public String overallConclusion;

    @JsonProperty("overall_score")
    public int overallScore;

    @JsonProperty("summary")
    public String summary;

    @JsonProperty("raw_llm_response")
    public String rawLlmResponse;

    @JsonProperty("model_used")
    public String modelUsed;

    @JsonProperty("tokens_used")
    public int tokensUsed;

    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    public LocalDateTime createdAt;

    @JsonProperty("items")
    public List<ItemResult> items = new ArrayList<>();
}
package com.rd.zngp.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ItemResult model, mirroring server/internal/model/inspection.go ItemResult
 */
public class ItemResult {

    @JsonProperty("id")
    public long id;

    @JsonProperty("inspection_result_id")
    public long inspectionResultId;

    @JsonProperty("item_id")
    public long itemId;

    @JsonProperty("item_name")
    public String itemName;

    @JsonProperty("verdict")
    public String verdict;

    @JsonProperty("evidence")
    public String evidence;

    @JsonProperty("confidence")
    public double confidence;

    @JsonProperty("ai_reasoning")
    public String aiReasoning;
}
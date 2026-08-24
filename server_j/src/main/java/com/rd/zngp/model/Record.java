package com.rd.zngp.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Record model, mirroring server/internal/model/record.go
 */
public class Record {

    @JsonProperty("id")
    public String id;

    @JsonProperty("title")
    public String title;

    @JsonProperty("description")
    public String description;

    @JsonProperty("inspector_name")
    public String inspectorName;

    @JsonProperty("customer_name")
    public String customerName;

    @JsonProperty("customer_address")
    public String customerAddress;

    @JsonProperty("inspection_date")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    public LocalDateTime inspectionDate;

    @JsonProperty("source_type")
    public String sourceType = "RECORDING";

    @JsonProperty("audio_file_path")
    public String audioFilePath;

    @JsonProperty("audio_duration")
    public double audioDuration;

    @JsonProperty("transcript_text")
    public String transcriptText;

    @JsonProperty("transcript_status")
    public String transcriptStatus = "PENDING";

    @JsonProperty("inspection_status")
    public String inspectionStatus = "NONE";

    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    public LocalDateTime createdAt;

    @JsonProperty("updated_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    public LocalDateTime updatedAt;

    /**
     * RecordCreateRequest, mirroring server internal/model RecordCreateRequest.
     */
    public static class CreateRequest {
        @JsonProperty("id")
        public String id;

        @JsonProperty("title")
        public String title;

        @JsonProperty("description")
        public String description;

        @JsonProperty("inspector_name")
        public String inspectorName;

        @JsonProperty("customer_name")
        public String customerName;

        @JsonProperty("customer_address")
        public String customerAddress;

        @JsonProperty("inspection_date")
        public String inspectionDate;

        @JsonProperty("source_type")
        public String sourceType;
    }
}
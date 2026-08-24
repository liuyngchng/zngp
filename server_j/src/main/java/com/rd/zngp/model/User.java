package com.rd.zngp.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * User model, mirroring server/internal/model/user.go
 */
public class User {

    @JsonProperty("id")
    public long id;

    @JsonProperty("username")
    public String username;

    @JsonIgnore
    @JsonProperty("password_hash")
    public String passwordHash;

    @JsonProperty("role")
    public String role = "admin";

    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    public LocalDateTime createdAt;
}
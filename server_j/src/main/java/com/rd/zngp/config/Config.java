package com.rd.zngp.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.io.IOException;

/**
 * Config loaded from cfg.yml, mirroring server/config/config.go.
 */
public class Config {

    @JsonProperty("server")
    public ServerConfig server = new ServerConfig();

    @JsonProperty("system")
    public SystemConfig system = new SystemConfig();

    @JsonProperty("database")
    public DatabaseConfig database = new DatabaseConfig();

    @JsonProperty("auth")
    public AuthConfig auth = new AuthConfig();

    @JsonProperty("asr")
    public ASRConfig asr = new ASRConfig();

    @JsonProperty("llm")
    public LLMConfig llm = new LLMConfig();

    @JsonProperty("upload")
    public UploadConfig upload = new UploadConfig();

    // ---- Inner classes ----

    public static class ServerConfig {
        @JsonProperty("port")
        public String port = "8080";

        @JsonProperty("host")
        public String host = "0.0.0.0";
    }

    public static class SystemConfig {
        @JsonProperty("name")
        public String name = "ZNGP 服务质量平台";
    }

    public static class DatabaseConfig {
        @JsonProperty("path")
        public String path = "./data/zngp.db";
    }

    public static class AuthConfig {
        @JsonProperty("username")
        public String username = "admin";

        @JsonProperty("password")
        public String password = "admin123";

        @JsonProperty("jwt_secret")
        public String jwtSecret = "change-me-to-a-random-string";
    }

    public static class ASRConfig {
        @JsonProperty("provider")
        public String provider = "aliyun_bailian";

        @JsonProperty("workspace_id")
        public String workspaceId = "";

        @JsonProperty("api_key")
        public String apiKey = "";

        @JsonProperty("model")
        public String model = "qwen3-asr-flash";

        @JsonProperty("base_url")
        public String baseUrl = "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
    }

    public static class LLMConfig {
        @JsonProperty("provider")
        public String provider = "openai_compatible";

        @JsonProperty("endpoint")
        public String endpoint = "https://api.deepseek.com";

        @JsonProperty("api_key")
        public String apiKey = "";

        @JsonProperty("model")
        public String model = "deepseek-v4-flash";
    }

    public static class UploadConfig {
        @JsonProperty("max_file_size_mb")
        public int maxFileSizeMB = 100;

        @JsonProperty("storage_dir")
        public String storageDir = "./data/uploads";
    }

    // ---- Singleton ----

    public static Config appConfig;

    /**
     * Load config from YAML file, apply defaults.
     */
    public static Config load(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.registerModule(new JavaTimeModule());

        Config cfg = mapper.readValue(new File(path), Config.class);

        // Apply defaults
        if (cfg.server.port == null || cfg.server.port.isEmpty()) cfg.server.port = "8080";
        if (cfg.server.host == null || cfg.server.host.isEmpty()) cfg.server.host = "0.0.0.0";
        if (cfg.system.name == null || cfg.system.name.isEmpty()) cfg.system.name = "ZNGP 服务质量平台";
        if (cfg.database.path == null || cfg.database.path.isEmpty()) cfg.database.path = "./data/zngp.db";
        if (cfg.upload.maxFileSizeMB == 0) cfg.upload.maxFileSizeMB = 100;
        if (cfg.upload.storageDir == null || cfg.upload.storageDir.isEmpty()) cfg.upload.storageDir = "./data/uploads";
        if (cfg.asr.model == null || cfg.asr.model.isEmpty()) cfg.asr.model = "qwen3-asr-flash";

        appConfig = cfg;
        return cfg;
    }
}
package com.rd.zngp.http.handler;

import com.rd.zngp.config.Config;
import com.rd.zngp.http.*;
import com.rd.zngp.store.Store;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.rd.zngp.http.HttpHelpers.*;

/**
 * Config and stats API handlers.
 */
public class MiscApiHandler {

    private static final Logger log = LoggerFactory.getLogger(MiscApiHandler.class);

    public static void handleStatsOverview(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            Store.OverviewResult overview = AppContext.getStore().getOverview();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("total_records", overview.totalRecords);
            resp.put("compliant_count", overview.compliantCount);
            resp.put("non_compliant_count", overview.nonCompliantCount);
            resp.put("review_count", overview.reviewCount);
            HttpHelpers.sendAndClose(ctx, req, json(200, resp));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("query failed")));
        }
    }

    public static void handleConfigGet(ChannelHandlerContext ctx, FullHttpRequest req) {
        Config cfg = Config.appConfig;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("asr_workspace_id", cfg.asr.workspaceId);
        resp.put("asr_model", cfg.asr.model);
        resp.put("llm_endpoint", cfg.llm.endpoint);
        resp.put("llm_model", cfg.llm.model);
        HttpHelpers.sendAndClose(ctx, req, json(200, resp));
    }

    public static void handleConfigUpdate(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            Map<String, Object> body = RequestHelpers.parseJsonBody(req);
            Config cfg = Config.appConfig;

            if (body.containsKey("asr_api_key") && body.get("asr_api_key") != null) {
                String val = (String) body.get("asr_api_key");
                if (!val.isEmpty()) cfg.asr.apiKey = val;
            }
            if (body.containsKey("asr_workspace_id") && body.get("asr_workspace_id") != null) {
                String val = (String) body.get("asr_workspace_id");
                if (!val.isEmpty()) cfg.asr.workspaceId = val;
            }
            if (body.containsKey("asr_model") && body.get("asr_model") != null) {
                String val = (String) body.get("asr_model");
                if (!val.isEmpty()) cfg.asr.model = val;
            }
            if (body.containsKey("llm_api_key") && body.get("llm_api_key") != null) {
                String val = (String) body.get("llm_api_key");
                if (!val.isEmpty()) cfg.llm.apiKey = val;
            }
            if (body.containsKey("llm_endpoint") && body.get("llm_endpoint") != null) {
                String val = (String) body.get("llm_endpoint");
                if (!val.isEmpty()) cfg.llm.endpoint = val;
            }
            if (body.containsKey("llm_model") && body.get("llm_model") != null) {
                String val = (String) body.get("llm_model");
                if (!val.isEmpty()) cfg.llm.model = val;
            }

            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.writeValue(new File("cfg.yml"), cfg);

            HttpHelpers.sendAndClose(ctx, req, json(200, singletonMap("message", "config updated")));
        } catch (Exception e) {
            log.error("save_config_failed", e);
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("save config failed")));
        }
    }
}
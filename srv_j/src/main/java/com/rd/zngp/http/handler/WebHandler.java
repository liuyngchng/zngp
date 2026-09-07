package com.rd.zngp.http.handler;

import com.rd.zngp.config.Config;
import com.rd.zngp.http.*;
import com.rd.zngp.model.InspectionResult;
import com.rd.zngp.model.InspectionTemplate;
import com.rd.zngp.model.Record;
import com.rd.zngp.store.Store;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.rd.zngp.http.HttpHelpers.*;

/**
 * Web page rendering handler (Thymeleaf).
 */
public class WebHandler {

    private static final Logger log = LoggerFactory.getLogger(WebHandler.class);

    public static void renderLogin(ChannelHandlerContext ctx, FullHttpRequest req) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", "login");
        HttpHelpers.sendAndClose(ctx, req, html(200, TemplateRenderer.render("login", data)));
    }

    public static void renderDashboard(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            Store store = AppContext.getStore();
            Store.OverviewResult overview = store.getOverview();
            Store.ListResult<Record> records = store.listRecords(1, 10, "");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_dashboard");
            data.put("title", "dashboard");
            data.put("total_records", overview.totalRecords);
            data.put("compliant_count", overview.compliantCount);
            data.put("non_compliant", overview.nonCompliantCount);
            data.put("review_count", overview.reviewCount);
            data.put("recent_records", records.items);
            HttpHelpers.sendAndClose(ctx, req, html(200, TemplateRenderer.render("dashboard", data)));
        } catch (Exception e) {
            log.error("dashboard_render_failed", e);
            HttpHelpers.sendAndClose(ctx, req, renderError("dashboard render failed: " + e.getMessage()));
        }
    }

    public static void renderUpload(ChannelHandlerContext ctx, FullHttpRequest req) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content_block", "content_upload");
        data.put("title", "upload");
        data.put("max_size_mb", Config.appConfig.upload.maxFileSizeMB);
        HttpHelpers.sendAndClose(ctx, req, html(200, TemplateRenderer.render("upload", data)));
    }

    public static void renderRecords(ChannelHandlerContext ctx, FullHttpRequest req, Map<String, String> params) {
        try {
            Store store = AppContext.getStore();
            int page = Math.max(1, RequestHelpers.parseInt(params.get("page"), 1));
            String keyword = params.getOrDefault("keyword", "");
            int pageSize = 20;

            Store.ListResult<Record> result = store.listRecords(page, pageSize, keyword);
            int totalPages = (int) ((result.total + pageSize - 1) / pageSize);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_records");
            data.put("title", "records");
            data.put("records", result.items);
            data.put("total", result.total);
            data.put("page", page);
            data.put("total_pages", totalPages);
            data.put("keyword", keyword);
            HttpHelpers.sendAndClose(ctx, req, html(200, TemplateRenderer.render("records", data)));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, renderError("query failed: " + e.getMessage()));
        }
    }

    public static void renderRecordDetail(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
        try {
            Store store = AppContext.getStore();
            Record record = store.getRecord(id);
            if (record == null) {
                HttpHelpers.sendAndClose(ctx, req, renderError("record not found"));
                return;
            }
            InspectionResult inspection = null;
            try { inspection = store.getInspectionByRecordId(id); } catch (Exception ignored) {}
            List<InspectionTemplate> templates = store.listTemplates();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_record_detail");
            data.put("title", "record detail");
            data.put("record", record);
            data.put("inspection", inspection);
            data.put("templates", templates);
            HttpHelpers.sendAndClose(ctx, req, html(200, TemplateRenderer.render("record_detail", data)));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, renderError("query failed: " + e.getMessage()));
        }
    }

    public static void renderTemplates(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            List<InspectionTemplate> templates = AppContext.getStore().listTemplates();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_templates");
            data.put("title", "templates");
            data.put("templates", templates);
            HttpHelpers.sendAndClose(ctx, req, html(200, TemplateRenderer.render("templates", data)));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, renderError("query failed: " + e.getMessage()));
        }
    }

    public static void renderTemplateEdit(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
        try {
            long tid = Long.parseLong(id);
            InspectionTemplate template = AppContext.getStore().getTemplate(tid);
            if (template == null) {
                HttpHelpers.sendAndClose(ctx, req, renderError("template not found"));
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_template_edit");
            data.put("title", "edit template");
            data.put("template", template);
            HttpHelpers.sendAndClose(ctx, req, html(200, TemplateRenderer.render("template_edit", data)));
        } catch (NumberFormatException e) {
            HttpHelpers.sendAndClose(ctx, req, renderError("invalid template ID"));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, renderError("query failed: " + e.getMessage()));
        }
    }

    public static void renderConfig(ChannelHandlerContext ctx, FullHttpRequest req) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content_block", "content_config");
        data.put("title", "system config");
        HttpHelpers.sendAndClose(ctx, req, html(200, TemplateRenderer.render("config", data)));
    }

    public static FullHttpResponse renderError(String msg) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_error");
            data.put("title", "error");
            data.put("error", msg);
            return html(500, TemplateRenderer.render("error", data));
        } catch (Exception e) {
            return html(500, "<html><body><h1>Error</h1><p>" + msg + "</p></body></html>");
        }
    }
}
package com.rd.zngp.http.handler;

import com.rd.zngp.http.*;
import com.rd.zngp.model.InspectionResult;
import com.rd.zngp.model.Record;
import com.rd.zngp.service.InspectionService;
import com.rd.zngp.store.Store;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.rd.zngp.http.HttpHelpers.*;

/**
 * Inspection API handler: run inspection, get result.
 */
public class InspectionApiHandler {

    private static final Logger log = LoggerFactory.getLogger(InspectionApiHandler.class);

    public static void handleInspect(ChannelHandlerContext ctx, FullHttpRequest req, String recordId) {
        try {
            Map<String, Object> body = RequestHelpers.parseJsonBody(req);
            Number templateIdNum = (Number) body.get("template_id");
            if (templateIdNum == null) {
                HttpHelpers.sendAndClose(ctx, req, json(400, errorMap("template_id (template ID) required")));
                return;
            }
            long templateId = templateIdNum.longValue();

            Store store = AppContext.getStore();
            Record record = store.getRecord(recordId);
            if (record == null) {
                HttpHelpers.sendAndClose(ctx, req, json(404, errorMap("record not found")));
                return;
            }

            store.updateRecordInspectionStatus(recordId, "PROCESSING");

            log.info("llm_inspection_start: record={}, template_id={}", recordId, templateId);
            InspectionService inspectionService = new InspectionService(store);
            InspectionResult result = inspectionService.run(record, templateId);
            store.updateRecordInspectionStatus(recordId, "COMPLETED");
            log.info("llm_inspection_done: record={}, conclusion={}, score={}, tokens={}",
                recordId, result.overallConclusion, result.overallScore, result.tokensUsed);

            HttpHelpers.sendAndClose(ctx, req, json(200, result));
        } catch (Exception e) {
            log.error("llm_inspection_failed: record={}, err={}", recordId, e.getMessage());
            try { AppContext.getStore().updateRecordInspectionStatus(recordId, "FAILED"); } catch (Exception ignored) {}
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("inspection failed: " + e.getMessage())));
        }
    }

    public static void handleGetResult(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
        try {
            long insId = Long.parseLong(id);
            InspectionResult result = AppContext.getStore().getInspectionResult(insId);
            if (result == null) {
                HttpHelpers.sendAndClose(ctx, req, json(404, errorMap("inspection result not found")));
                return;
            }
            HttpHelpers.sendAndClose(ctx, req, json(200, result));
        } catch (NumberFormatException e) {
            HttpHelpers.sendAndClose(ctx, req, json(400, errorMap("invalid inspection result ID")));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("query failed")));
        }
    }
}
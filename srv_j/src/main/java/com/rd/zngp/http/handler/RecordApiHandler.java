package com.rd.zngp.http.handler;

import com.rd.zngp.config.Config;
import com.rd.zngp.http.*;
import com.rd.zngp.model.Record;
import com.rd.zngp.model.InspectionResult;
import com.rd.zngp.service.ASRService;
import com.rd.zngp.store.Store;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.multipart.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.rd.zngp.http.HttpHelpers.*;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Record API handlers: upload, list, get, delete, transcript-status, transcribe, audio.
 */
public class RecordApiHandler {

    private static final Logger log = LoggerFactory.getLogger(RecordApiHandler.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final ExecutorService bgExecutor = Executors.newCachedThreadPool();

    public static void handleUpload(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            Store store = AppContext.getStore();
            int maxSizeMB = Config.appConfig.upload.maxFileSizeMB;

            HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(
                new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE), req, StandardCharsets.UTF_8);
            decoder.setDiscardThreshold(0);

            String metadataStr = null;
            byte[] audioData = null;
            String audioFilename = null;

            try {
                for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
                    if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                        Attribute attr = (Attribute) data;
                        if ("metadata".equals(attr.getName())) {
                            metadataStr = attr.getValue();
                        }
                    } else if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
                        FileUpload upload = (FileUpload) data;
                        if ("audio".equals(upload.getName())) {
                            audioFilename = upload.getFilename();
                            audioData = upload.get();
                        }
                    }
                }
            } finally {
                decoder.destroy();
            }

            if (metadataStr == null || metadataStr.isEmpty()) {
                json200(ctx, req, json(400, errorMap("metadata field required")));
                return;
            }
            if (audioData == null || audioData.length == 0) {
                json200(ctx, req, json(400, errorMap("audio file required")));
                return;
            }
            if (audioData.length > (long) maxSizeMB * 1024 * 1024) {
                json200(ctx, req, json(400, errorMap("file exceeds size limit (" + maxSizeMB + "MB)")));
                return;
            }

            Record.CreateRequest createReq = HttpHelpers.objectMapper().readValue(metadataStr, Record.CreateRequest.class);

            String recordId = createReq.id != null && !createReq.id.isEmpty() ? createReq.id : UUID.randomUUID().toString();
            LocalDateTime inspectionDate = LocalDateTime.now();
            if (createReq.inspectionDate != null && !createReq.inspectionDate.isEmpty()) {
                inspectionDate = parseTime(createReq.inspectionDate);
            }

            String storageDir = Config.appConfig.upload.storageDir;
            new File(storageDir).mkdirs();
            String ext = ".wav";
            if (audioFilename != null) {
                int dot = audioFilename.lastIndexOf('.');
                if (dot > 0) ext = audioFilename.substring(dot);
            }
            String audioPath = storageDir + "/" + recordId + ext;
            Files.write(Paths.get(audioPath), audioData);

            double duration = wavDuration(audioPath);

            String sourceType = createReq.sourceType != null && !createReq.sourceType.isEmpty()
                ? createReq.sourceType : "RECORDING";

            Record record = new Record();
            record.id = recordId;
            record.title = createReq.title;
            record.description = createReq.description;
            record.inspectorName = createReq.inspectorName;
            record.customerName = createReq.customerName;
            record.customerAddress = createReq.customerAddress;
            record.inspectionDate = inspectionDate;
            record.sourceType = sourceType;
            record.audioFilePath = audioPath;
            record.audioDuration = duration;
            record.transcriptStatus = "PENDING";
            record.inspectionStatus = "NONE";
            record.createdAt = LocalDateTime.now();
            record.updatedAt = LocalDateTime.now();

            store.createRecord(record);

            final Record finalRecord = record;
            bgExecutor.submit(() -> autoTranscribe(finalRecord));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("record", record);
            result.put("transcript_status", "PENDING");
            result.put("message", "upload successful, transcribing in background...");
            json200(ctx, req, json(200, result));
        } catch (Exception e) {
            log.error("upload_failed", e);
            json200(ctx, req, json(500, errorMap("upload failed: " + e.getMessage())));
        }
    }

    public static void handleTextUpload(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            Store store = AppContext.getStore();
            Map<String, Object> body = RequestHelpers.parseJsonBody(req);
            String transcriptText = (String) body.get("transcript_text");
            if (transcriptText == null || transcriptText.trim().isEmpty()) {
                json200(ctx, req, json(400, errorMap("transcript_text field required")));
                return;
            }

            String id = (String) body.get("id");
            if (id == null || id.isEmpty()) id = UUID.randomUUID().toString();
            String title = (String) body.get("title");
            String inspectorName = (String) body.get("inspector_name");
            String customerName = (String) body.get("customer_name");
            String customerAddress = (String) body.get("customer_address");
            String inspectionDateStr = (String) body.get("inspection_date");
            String sourceType = (String) body.get("source_type");
            if (sourceType == null || sourceType.isEmpty()) sourceType = "TEXT";

            LocalDateTime inspectionDate = LocalDateTime.now();
            if (inspectionDateStr != null && !inspectionDateStr.isEmpty()) {
                inspectionDate = parseTime(inspectionDateStr);
            }

            Record record = new Record();
            record.id = id;
            record.title = title;
            record.inspectorName = inspectorName;
            record.customerName = customerName;
            record.customerAddress = customerAddress;
            record.inspectionDate = inspectionDate;
            record.sourceType = sourceType;
            record.transcriptText = transcriptText;
            record.transcriptStatus = "COMPLETED";
            record.inspectionStatus = "NONE";
            record.createdAt = LocalDateTime.now();
            record.updatedAt = LocalDateTime.now();

            store.createRecord(record);

            log.info("text_upload_done: record={}, text_len={}", id, transcriptText.length());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("record", record);
            result.put("transcript_status", "COMPLETED");
            result.put("message", "text uploaded, ready for inspection");
            json200(ctx, req, json(200, result));
        } catch (Exception e) {
            log.error("text_upload_failed", e);
            json200(ctx, req, json(500, errorMap("text upload failed: " + e.getMessage())));
        }
    }

    public static void handleList(ChannelHandlerContext ctx, FullHttpRequest req, Map<String, String> queryParams) {
        try {
            Store store = AppContext.getStore();
            int page = Math.max(1, RequestHelpers.parseInt(queryParams.get("page"), 1));
            int pageSize = Math.min(100, Math.max(1, RequestHelpers.parseInt(queryParams.get("page_size"), 20)));
            String keyword = queryParams.getOrDefault("keyword", "");

            Store.ListResult<Record> result = store.listRecords(page, pageSize, keyword);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("records", result.items);
            resp.put("total", result.total);
            resp.put("page", page);
            resp.put("page_size", pageSize);
            json200(ctx, req, json(200, resp));
        } catch (Exception e) {
            log.error("list_records_failed", e);
            json200(ctx, req, json(500, errorMap("query failed")));
        }
    }

    public static void handleGet(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
        try {
            Store store = AppContext.getStore();
            Record record = store.getRecord(id);
            if (record == null) {
                json200(ctx, req, json(404, errorMap("record not found")));
                return;
            }
            InspectionResult inspection = null;
            try { inspection = store.getInspectionByRecordId(id); } catch (Exception ignored) {}

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("record", record);
            resp.put("inspection", inspection);
            json200(ctx, req, json(200, resp));
        } catch (Exception e) {
            log.error("get_record_failed", e);
            json200(ctx, req, json(500, errorMap("query failed")));
        }
    }

    public static void handleDelete(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
        try {
            AppContext.getStore().deleteRecord(id);
            json200(ctx, req, json(200, singletonMap("message", "deleted")));
        } catch (Exception e) {
            json200(ctx, req, json(500, errorMap("delete failed")));
        }
    }

    public static void handleTranscriptStatus(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
        try {
            Store store = AppContext.getStore();
            Record record = store.getRecord(id);
            if (record == null) {
                json200(ctx, req, json(404, errorMap("record not found")));
                return;
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("record_id", record.id);
            resp.put("transcript_status", record.transcriptStatus);
            resp.put("transcript_text", record.transcriptText);
            resp.put("message", statusMessage(record.transcriptStatus));
            json200(ctx, req, json(200, resp));
        } catch (Exception e) {
            json200(ctx, req, json(500, errorMap("query failed")));
        }
    }

    public static void handleTranscribe(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
        try {
            Store store = AppContext.getStore();
            Record record = store.getRecord(id);
            if (record == null) {
                json200(ctx, req, json(404, errorMap("record not found")));
                return;
            }
            if (record.audioFilePath == null || record.audioFilePath.isEmpty()) {
                json200(ctx, req, json(400, errorMap("record has no audio file")));
                return;
            }

            store.updateRecordTranscript(id, "", "PROCESSING");
            log.info("asr_manual_transcribe_start: record={}, audio={}", id, record.audioFilePath);

            String text = ASRService.transcribeAudio(record.audioFilePath);
            store.updateRecordTranscript(id, text, "COMPLETED");
            log.info("asr_manual_transcribe_done: record={}, text_len={}", id, text != null ? text.length() : 0);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("record_id", id);
            resp.put("transcript", text);
            resp.put("status", "COMPLETED");
            json200(ctx, req, json(200, resp));
        } catch (Exception e) {
            log.error("asr_manual_transcribe_failed: record={}, err={}", id, e.getMessage());
            try { AppContext.getStore().updateRecordTranscript(id, "", "FAILED"); } catch (Exception ignored) {}
            json200(ctx, req, json(500, errorMap("transcription failed: " + e.getMessage())));
        }
    }

    public static void handleAudio(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
        try {
            Record record = AppContext.getStore().getRecord(id);
            if (record == null || record.audioFilePath == null) {
                json200(ctx, req, json(404, errorMap("record not found")));
                return;
            }
            File audioFile = new File(record.audioFilePath);
            if (!audioFile.exists()) {
                json200(ctx, req, json(404, errorMap("audio file not found")));
                return;
            }
            byte[] data = Files.readAllBytes(audioFile.toPath());
            FullHttpResponse resp = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpResponseStatus.OK,
                io.netty.buffer.Unpooled.wrappedBuffer(data));
            String contentType = "audio/wav";
            if (record.audioFilePath.toLowerCase().endsWith(".mp3")) contentType = "audio/mpeg";
            resp.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE, contentType);
            resp.headers().set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, data.length);
            resp.headers().set(io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_RANGES, "bytes");
            HttpHelpers.sendAndClose(ctx, req, resp);
        } catch (Exception e) {
            json200(ctx, req, json(500, errorMap("failed to read audio file")));
        }
    }

    // ---- helpers ----

    private static void json200(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse resp) {
        HttpHelpers.sendAndClose(ctx, req, resp);
    }

    private static void autoTranscribe(Record record) {
        try {
            Store store = AppContext.getStore();
            store.updateRecordTranscript(record.id, "", "PROCESSING");
            String text = ASRService.transcribeAudio(record.audioFilePath);
            store.updateRecordTranscript(record.id, text, "COMPLETED");
            log.info("asr_auto_transcribe_done: record={}, text_len={}", record.id, text != null ? text.length() : 0);
        } catch (Exception e) {
            log.error("asr_auto_transcribe_failed: record={}, err={}", record.id, e.getMessage());
            try { AppContext.getStore().updateRecordTranscript(record.id, "", "FAILED"); } catch (Exception ignored) {}
        }
    }

    private static String statusMessage(String status) {
        switch (status != null ? status : "") {
            case "PENDING": return "queued for transcription...";
            case "PROCESSING": return "transcribing, please wait...";
            case "COMPLETED": return "transcription complete";
            case "FAILED": return "transcription failed, please retry";
            default: return "unknown status";
        }
    }

    static LocalDateTime parseTime(String s) {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"};
        for (String fmt : patterns) {
            try {
                if (fmt.equals("yyyy-MM-dd")) {
                    return LocalDateTime.parse(s + "T00:00:00");
                }
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(fmt));
            } catch (Exception ignored) {}
        }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    static double wavDuration(String path) {
        try {
            byte[] header = new byte[44];
            try (FileInputStream fis = new FileInputStream(path)) {
                if (fis.read(header) < 44) return 0;
            }
            if (!"RIFF".equals(new String(header, 0, 4)) || !"WAVE".equals(new String(header, 8, 4))) return 0;
            long byteRate = readUint32LE(header, 28);
            long dataSize = readUint32LE(header, 40);
            if (byteRate == 0) byteRate = 32000;
            return (double) dataSize / (double) byteRate;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long readUint32LE(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
            | ((data[offset + 1] & 0xFFL) << 8)
            | ((data[offset + 2] & 0xFFL) << 16)
            | ((data[offset + 3] & 0xFFL) << 24);
    }
}
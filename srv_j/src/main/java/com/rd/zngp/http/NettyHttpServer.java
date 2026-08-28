package com.rd.zngp.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.multipart.*;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import com.rd.zngp.config.Config;
import com.rd.zngp.middleware.JwtUtil;
import com.rd.zngp.model.*;
import com.rd.zngp.model.Record;
import com.rd.zngp.service.ASRService;
import com.rd.zngp.service.InspectionService;
import com.rd.zngp.store.Store;
import io.jsonwebtoken.Claims;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Netty HTTP Server - main dispatcher, mirroring main.go routes.
 */
public class NettyHttpServer {

    private static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Store store;
    private final InspectionService inspectionService;
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool();

    // Simple template cache
    private final Map<String, String> templateCache = new HashMap<>();

    public NettyHttpServer(Store store) {
        this.store = store;
        this.inspectionService = new InspectionService(store);
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        loadTemplates();
    }

    private void loadTemplates() {
        String[] names = {"layout", "dashboard", "records", "record_detail", "templates",
            "template_edit", "config", "upload", "error", "login"};
        for (String name : names) {
            try {
                String path = "/web/templates/" + name + ".html";
                byte[] bytes = readClasspathResource(path);
                if (bytes != null) {
                    templateCache.put(name, new String(bytes, StandardCharsets.UTF_8));
                } else {
                    log.warn("无法加载模板: {}", name);
                }
            } catch (Exception e) {
                log.warn("无法加载模板: {}", name);
            }
        }
    }

    public void start(String host, int port) throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                            .addLast(new HttpServerCodec())
                            .addLast(new ChunkedWriteHandler())
                            .addLast(new HttpObjectAggregator(200 * 1024 * 1024)) // 200MB max
                            .addLast(new RouterHandler());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

            log.info("服务启动: http://{}:{}", host, port);
            log.info("本地访问: http://127.0.0.1:{}", port);
            ChannelFuture f = b.bind(host, port).sync();
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    // ============================================================
    // Router Handler
    // ============================================================

    private class RouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            FullHttpResponse resp;
            try {
                // Extract auth info from cookie/header (for protected routes)
                AuthInfo auth = extractAuth(req);

                String uri = req.uri();
                int qIdx = uri.indexOf('?');
                String path = qIdx >= 0 ? uri.substring(0, qIdx) : uri;
                String query = qIdx >= 0 ? uri.substring(qIdx + 1) : "";
                Map<String, String> params = parseQuery(query);

                HttpMethod method = req.method();

                // ---- Static files ----
                if (path.startsWith("/static/")) {
                    serveStatic(ctx, path);
                    return;
                }

                // ---- API routes ----
                if (path.startsWith("/api/")) {
                    handleApi(ctx, req, path, method, auth, params);
                    return;
                }

                // ---- Web pages ----
                handleWeb(ctx, req, path, auth, params);

            } catch (Exception e) {
                log.error("处理请求失败: {}", path(req), e);
                resp = jsonResp(500, errorMap("内部错误: " + e.getMessage()));
                HttpUtil.setKeepAlive(req, false);
                ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            }
        }

        private String path(FullHttpRequest req) {
            return req.uri();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("连接异常", cause);
            ctx.close();
        }
    }

    // ============================================================
    // Auth
    // ============================================================

    static class AuthInfo {
        boolean valid;
        long userId;
        String username;
        String token;

        AuthInfo(boolean valid, long userId, String username, String token) {
            this.valid = valid;
            this.userId = userId;
            this.username = username;
            this.token = token;
        }
    }

    private AuthInfo extractAuth(FullHttpRequest req) {
        String token = null;

        // Header first
        String authHeader = req.headers().get("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        // Cookie second
        if (token == null) {
            String cookieStr = req.headers().get("Cookie");
            if (cookieStr != null) {
                Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(cookieStr);
                for (Cookie c : cookies) {
                    if (JwtUtil.COOKIE_NAME.equals(c.name())) {
                        token = c.value();
                        break;
                    }
                }
            }
        }

        if (token != null) {
            try {
                Claims claims = JwtUtil.parseToken(token);
                long userId = ((Number) claims.get("user_id")).longValue();
                String username = (String) claims.get("username");
                // Generate a new token for sliding expiration
                String newToken = JwtUtil.generateToken(userId, username);
                return new AuthInfo(true, userId, username, newToken);
            } catch (Exception e) {
                // token invalid
            }
        }

        return new AuthInfo(false, 0, "", null);
    }

    private void setTokenCookie(FullHttpResponse resp, String token) {
        DefaultCookie cookie = new DefaultCookie(JwtUtil.COOKIE_NAME, token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(72 * 3600);
        resp.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
    }

    private void clearTokenCookie(FullHttpResponse resp) {
        DefaultCookie cookie = new DefaultCookie(JwtUtil.COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        resp.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
    }

    // ============================================================
    // Static file serving
    // ============================================================

    private void serveStatic(ChannelHandlerContext ctx, String path) {
        // Security: prevent path traversal
        if (path.contains("..")) {
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        try {
            byte[] data = readClasspathResource("/web" + path);
            if (data == null) {
                FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            // Determine content type
            String contentType = "application/octet-stream";
            if (path.endsWith(".css")) contentType = "text/css; charset=utf-8";
            else if (path.endsWith(".js")) contentType = "application/javascript; charset=utf-8";
            else if (path.endsWith(".woff2")) contentType = "font/woff2";
            else if (path.endsWith(".woff")) contentType = "font/woff";
            else if (path.endsWith(".ttf")) contentType = "font/ttf";
            else if (path.endsWith(".svg")) contentType = "image/svg+xml";
            else if (path.endsWith(".png")) contentType = "image/png";
            else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) contentType = "image/jpeg";

            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                io.netty.buffer.Unpooled.wrappedBuffer(data));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
            resp.headers().set(HttpHeaderNames.CACHE_CONTROL, "public, max-age=3600");
            HttpUtil.setKeepAlive(resp, true);
            ctx.writeAndFlush(resp);
        } catch (Exception e) {
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }
    }

    // ============================================================
    // API Router
    // ============================================================

    private void handleApi(ChannelHandlerContext ctx, FullHttpRequest req, String path,
                           HttpMethod method, AuthInfo auth, Map<String, String> queryParams) {

        // Extract route params from path (e.g., /api/records/:id/transcribe)
        String apiPath = path.substring(4); // Remove "/api", keep leading "/"

        // Public API: login
        if (apiPath.equals("/auth/login") && method == HttpMethod.POST) {
            FullHttpResponse resp = handleLogin(req);
            sendAndClose(ctx, req, resp);
            return;
        }

        // Protected API: require auth
        if (!auth.valid) {
            FullHttpResponse resp = jsonResp(401, errorMap("missing authorization header"));
            if (auth.token != null && !auth.token.isEmpty()) {
                // Token was provided but invalid
            }
            resp.headers().set("X-New-Token", "");
            sendAndClose(ctx, req, resp);
            return;
        }

        // Add sliding expiration header
        FullHttpResponse resp;

        // -- Records --
        if (apiPath.equals("/records") && method == HttpMethod.POST) {
            resp = handleRecordUpload(ctx, req);
        } else if (apiPath.equals("/records") && method == HttpMethod.GET) {
            resp = handleRecordList(queryParams);
        } else if (apiPath.matches("/records/[^/]+/transcript-status") && method == HttpMethod.GET) {
            String id = extractPathParam(apiPath, "/records/", "/transcript-status");
            resp = handleRecordTranscriptStatus(id);
        } else if (apiPath.matches("/records/[^/]+/transcribe") && method == HttpMethod.POST) {
            String id = extractPathParam(apiPath, "/records/", "/transcribe");
            resp = handleRecordTranscribe(id);
        } else if (apiPath.matches("/records/[^/]+/inspect") && method == HttpMethod.POST) {
            String id = extractPathParam(apiPath, "/records/", "/inspect");
            resp = handleRecordInspect(req, id);
        } else if (apiPath.matches("/records/[^/]+/audio") && method == HttpMethod.GET) {
            String id = extractPathParam(apiPath, "/records/", "/audio");
            resp = handleRecordAudio(id);
        } else if (apiPath.matches("/records/[^/]+") && method == HttpMethod.DELETE) {
            String id = extractPathParam(apiPath, "/records/", "");
            resp = handleRecordDelete(id);
        } else if (apiPath.matches("/records/[^/]+") && method == HttpMethod.GET) {
            String id = extractPathParam(apiPath, "/records/", "");
            resp = handleRecordGet(id);
        }
        // -- Inspections --
        else if (apiPath.matches("/inspections/[^/]+") && method == HttpMethod.GET) {
            String id = extractPathParam(apiPath, "/inspections/", "");
            resp = handleInspectionGet(id);
        }
        // -- Templates --
        else if (apiPath.equals("/templates") && method == HttpMethod.GET) {
            resp = handleTemplateList();
        } else if (apiPath.equals("/templates") && method == HttpMethod.POST) {
            resp = handleTemplateCreate(req);
        } else if (apiPath.matches("/templates/[^/]+/items/[^/]+") && method == HttpMethod.PUT) {
            String[] parts = extractTwoParams(apiPath, "/templates/", "/items/");
            resp = handleTemplateItemUpdateBody(parts[0], parts[1], req);
        } else if (apiPath.matches("/templates/[^/]+/items/[^/]+") && method == HttpMethod.DELETE) {
            String[] parts = extractTwoParams(apiPath, "/templates/", "/items/");
            resp = handleTemplateItemDelete(parts[0], parts[1]);
        } else if (apiPath.matches("/templates/[^/]+/items") && method == HttpMethod.POST) {
            String id = extractPathParam(apiPath, "/templates/", "/items");
            resp = handleTemplateItemCreate(id, req);
        } else if (apiPath.matches("/templates/[^/]+") && method == HttpMethod.GET) {
            String id = extractPathParam(apiPath, "/templates/", "");
            resp = handleTemplateGet(id);
        } else if (apiPath.matches("/templates/[^/]+") && method == HttpMethod.PUT) {
            String id = extractPathParam(apiPath, "/templates/", "");
            resp = handleTemplateUpdate(id, req);
        } else if (apiPath.matches("/templates/[^/]+") && method == HttpMethod.DELETE) {
            String id = extractPathParam(apiPath, "/templates/", "");
            resp = handleTemplateDelete(id);
        }
        // -- Auth --
        else if (apiPath.equals("/auth/change-password") && method == HttpMethod.POST) {
            resp = handleChangePassword(req, auth);
        }
        // -- Stats --
        else if (apiPath.equals("/stats/overview") && method == HttpMethod.GET) {
            resp = handleStatsOverview();
        }
        // -- Config --
        else if (apiPath.equals("/config") && method == HttpMethod.GET) {
            resp = handleConfigGet();
        } else if (apiPath.equals("/config") && method == HttpMethod.PUT) {
            resp = handleConfigUpdate(req);
        }
        else {
            resp = jsonResp(404, errorMap("not found"));
        }

        // Add sliding expiration token
        if (auth.token != null) {
            resp.headers().set("X-New-Token", auth.token);
            setTokenCookie(resp, auth.token);
        }

        sendAndClose(ctx, req, resp);
    }

    // ============================================================
    // Web Router
    // ============================================================

    private void handleWeb(ChannelHandlerContext ctx, FullHttpRequest req, String path,
                           AuthInfo auth, Map<String, String> queryParams) {

        FullHttpResponse resp;

        if (path.equals("/login")) {
            resp = renderLogin();
        } else if (path.equals("/logout")) {
            resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
            clearTokenCookie(resp);
            resp.headers().set(HttpHeaderNames.LOCATION, "/login");
        } else if (!auth.valid) {
            resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
            resp.headers().set(HttpHeaderNames.LOCATION, "/login");
        } else {
            // Add sliding expiration
            if (auth.token != null) {
                resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                setTokenCookie(resp, auth.token);
            }

            try {
                if (path.equals("/")) {
                    resp = renderDashboard();
                } else if (path.equals("/upload")) {
                    resp = renderUpload();
                } else if (path.equals("/records")) {
                    resp = renderRecords(queryParams);
                } else if (path.matches("/records/[^/]+")) {
                    String id = extractPathParam(path, "/records/", "");
                    resp = renderRecordDetail(id);
                } else if (path.equals("/templates")) {
                    resp = renderTemplates();
                } else if (path.matches("/templates/[^/]+/edit")) {
                    String id = extractPathParam(path, "/templates/", "/edit");
                    resp = renderTemplateEdit(id);
                } else if (path.equals("/config")) {
                    resp = renderConfig();
                } else {
                    resp = htmlResp(404, "<html><body><h1>404 Not Found</h1></body></html>");
                }
            } catch (Exception e) {
                log.error("渲染页面失败: {}", path, e);
                resp = renderError("服务器错误: " + e.getMessage());
            }

            if (auth.token != null) {
                setTokenCookie(resp, auth.token);
            }
        }

        sendAndClose(ctx, req, resp);
    }

    // ============================================================
    // API Handlers
    // ============================================================

    // -- Auth --

    private FullHttpResponse handleLogin(FullHttpRequest req) {
        try {
            Map<String, Object> body = parseJsonBody(req);
            String username = (String) body.get("username");
            String password = (String) body.get("password");

            if (username == null || password == null) {
                return jsonResp(400, errorMap("请输入用户名和密码"));
            }

            User user = store.findUserByUsername(username);
            if (user == null || !BCrypt.checkpw(password, user.passwordHash)) {
                return jsonResp(401, errorMap("用户名或密码错误"));
            }

            String token = JwtUtil.generateToken(user.id, user.username);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", token);
            result.put("username", user.username);

            FullHttpResponse resp = jsonResp(200, result);
            setTokenCookie(resp, token);
            return resp;
        } catch (Exception e) {
            log.error("登录失败", e);
            return jsonResp(500, errorMap("登录失败: " + e.getMessage()));
        }
    }

    private FullHttpResponse handleChangePassword(FullHttpRequest req, AuthInfo auth) {
        try {
            Map<String, Object> body = parseJsonBody(req);
            String oldPassword = (String) body.get("old_password");
            String newPassword = (String) body.get("new_password");

            if (oldPassword == null || newPassword == null || newPassword.length() < 6) {
                return jsonResp(400, errorMap("请输入旧密码和新密码（新密码至少6位）"));
            }

            User user = store.findUserByUsername(auth.username);
            if (user == null || !BCrypt.checkpw(oldPassword, user.passwordHash)) {
                return jsonResp(401, errorMap("旧密码错误"));
            }

            String hash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
            store.updateUserPassword(auth.userId, hash);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "密码修改成功");
            return jsonResp(200, result);
        } catch (Exception e) {
            log.error("修改密码失败", e);
            return jsonResp(500, errorMap("密码修改失败: " + e.getMessage()));
        }
    }

    // -- Records --

    private FullHttpResponse handleRecordUpload(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            int maxSizeMB = Config.appConfig.upload.maxFileSizeMB;

            // Parse multipart form
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
                return jsonResp(400, errorMap("缺少 metadata 字段"));
            }
            if (audioData == null || audioData.length == 0) {
                return jsonResp(400, errorMap("缺少 audio 文件"));
            }

            if (audioData.length > (long) maxSizeMB * 1024 * 1024) {
                return jsonResp(400, errorMap("文件超过大小限制 (" + maxSizeMB + "MB)"));
            }

            Record.CreateRequest createReq = mapper.readValue(metadataStr, Record.CreateRequest.class);

            // Generate record ID
            String recordId = createReq.id != null && !createReq.id.isEmpty() ? createReq.id : UUID.randomUUID().toString();

            // Parse date
            LocalDateTime inspectionDate = LocalDateTime.now();
            if (createReq.inspectionDate != null && !createReq.inspectionDate.isEmpty()) {
                inspectionDate = parseTime(createReq.inspectionDate);
            }

            // Save audio file
            String storageDir = Config.appConfig.upload.storageDir;
            new File(storageDir).mkdirs();
            String ext = ".wav";
            if (audioFilename != null) {
                int dot = audioFilename.lastIndexOf('.');
                if (dot > 0) ext = audioFilename.substring(dot);
            }
            String audioPath = storageDir + "/" + recordId + ext;
            Files.write(Paths.get(audioPath), audioData);

            // Calculate duration
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

            // Auto-transcribe in background
            final Record finalRecord = record;
            bgExecutor.submit(() -> autoTranscribe(finalRecord));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("record", record);
            result.put("transcript_status", "PENDING");
            result.put("message", "上传成功，正在后台转写...");

            return jsonResp(200, result);
        } catch (Exception e) {
            log.error("上传失败", e);
            return jsonResp(500, errorMap("上传失败: " + e.getMessage()));
        }
    }

    private void autoTranscribe(Record record) {
        log.info("[ASR] 开始自动转写: record={}, audio={}", record.id, record.audioFilePath);
        try {
            store.updateRecordTranscript(record.id, "", "PROCESSING");
            String text = ASRService.transcribeAudio(record.audioFilePath);
            store.updateRecordTranscript(record.id, text, "COMPLETED");
            log.info("[ASR] 转写完成: record={}, text_len={}", record.id, text != null ? text.length() : 0);
        } catch (Exception e) {
            log.error("[ASR] 转写失败: record={}, err={}", record.id, e.getMessage());
            try {
                store.updateRecordTranscript(record.id, "", "FAILED");
            } catch (Exception ex) {
                log.error("更新转写状态失败", ex);
            }
        }
    }

    private FullHttpResponse handleRecordList(Map<String, String> params) {
        try {
            int page = Math.max(1, parseInt(params.get("page"), 1));
            int pageSize = Math.min(100, Math.max(1, parseInt(params.get("page_size"), 20)));
            String keyword = params.getOrDefault("keyword", "");

            Store.ListResult<Record> result = store.listRecords(page, pageSize, keyword);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("records", result.items);
            resp.put("total", result.total);
            resp.put("page", page);
            resp.put("page_size", pageSize);

            return jsonResp(200, resp);
        } catch (Exception e) {
            log.error("查询记录失败", e);
            return jsonResp(500, errorMap("查询失败"));
        }
    }

    private FullHttpResponse handleRecordGet(String id) {
        try {
            Record record = store.getRecord(id);
            if (record == null) {
                return jsonResp(404, errorMap("记录不存在"));
            }

            InspectionResult inspection = null;
            try {
                inspection = store.getInspectionByRecordId(id);
            } catch (Exception ignored) {}

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("record", record);
            resp.put("inspection", inspection);
            return jsonResp(200, resp);
        } catch (Exception e) {
            log.error("获取记录失败", e);
            return jsonResp(500, errorMap("查询失败"));
        }
    }

    private FullHttpResponse handleRecordTranscriptStatus(String id) {
        try {
            Record record = store.getRecord(id);
            if (record == null) {
                return jsonResp(404, errorMap("记录不存在"));
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("record_id", record.id);
            resp.put("transcript_status", record.transcriptStatus);
            resp.put("transcript_text", record.transcriptText);
            resp.put("message", statusMessage(record.transcriptStatus));
            return jsonResp(200, resp);
        } catch (Exception e) {
            return jsonResp(500, errorMap("查询失败"));
        }
    }

    private FullHttpResponse handleRecordTranscribe(String id) {
        try {
            Record record = store.getRecord(id);
            if (record == null) {
                return jsonResp(404, errorMap("记录不存在"));
            }
            if (record.audioFilePath == null || record.audioFilePath.isEmpty()) {
                return jsonResp(400, errorMap("记录没有音频文件"));
            }

            store.updateRecordTranscript(id, "", "PROCESSING");

            log.info("[ASR] 手动转写开始: record={}, audio={}", id, record.audioFilePath);
            String text = ASRService.transcribeAudio(record.audioFilePath);
            store.updateRecordTranscript(id, text, "COMPLETED");
            log.info("[ASR] 手动转写完成: record={}, text_len={}", id, text != null ? text.length() : 0);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("record_id", id);
            resp.put("transcript", text);
            resp.put("status", "COMPLETED");
            return jsonResp(200, resp);
        } catch (Exception e) {
            log.error("[ASR] 手动转写失败: record={}, err={}", id, e.getMessage());
            try { store.updateRecordTranscript(id, "", "FAILED"); } catch (Exception ignored) {}
            return jsonResp(500, errorMap("转写失败: " + e.getMessage()));
        }
    }

    private FullHttpResponse handleRecordInspect(FullHttpRequest req, String id) {
        try {
            Map<String, Object> body = parseJsonBody(req);
            Number templateIdNum = (Number) body.get("template_id");
            if (templateIdNum == null) {
                return jsonResp(400, errorMap("请指定 template_id（检查项模板ID）"));
            }
            long templateId = templateIdNum.longValue();

            Record record = store.getRecord(id);
            if (record == null) {
                return jsonResp(404, errorMap("记录不存在"));
            }

            store.updateRecordInspectionStatus(id, "PROCESSING");

            log.info("[LLM] 质检开始: record={}, template_id={}", id, templateId);
            InspectionResult result = inspectionService.run(record, templateId);
            store.updateRecordInspectionStatus(id, "COMPLETED");
            log.info("[LLM] 质检完成: record={}, conclusion={}, score={}, tokens={}", id, result.overallConclusion, result.overallScore, result.tokensUsed);

            return jsonResp(200, result);
        } catch (Exception e) {
            log.error("[LLM] 质检失败: record={}, err={}", id, e.getMessage());
            try { store.updateRecordInspectionStatus(id, "FAILED"); } catch (Exception ignored) {}
            return jsonResp(500, errorMap("质检失败: " + e.getMessage()));
        }
    }

    private FullHttpResponse handleRecordAudio(String id) {
        try {
            Record record = store.getRecord(id);
            if (record == null || record.audioFilePath == null) {
                return jsonResp(404, errorMap("记录不存在"));
            }

            java.io.File audioFile = new java.io.File(record.audioFilePath);
            if (!audioFile.exists()) {
                return jsonResp(404, errorMap("音频文件不存在"));
            }

            byte[] data = Files.readAllBytes(audioFile.toPath());
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                io.netty.buffer.Unpooled.wrappedBuffer(data));
            String contentType = "audio/wav";
            if (record.audioFilePath.toLowerCase().endsWith(".mp3")) contentType = "audio/mpeg";
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
            resp.headers().set(HttpHeaderNames.ACCEPT_RANGES, "bytes");
            return resp;
        } catch (Exception e) {
            return jsonResp(500, errorMap("读取音频文件失败"));
        }
    }

    private FullHttpResponse handleRecordDelete(String id) {
        try {
            store.deleteRecord(id);
            return jsonResp(200, singletonMap("message", "已删除"));
        } catch (Exception e) {
            return jsonResp(500, errorMap("删除失败"));
        }
    }

    // -- Inspections --

    private FullHttpResponse handleInspectionGet(String id) {
        try {
            long insId = Long.parseLong(id);
            InspectionResult result = store.getInspectionResult(insId);
            if (result == null) {
                return jsonResp(404, errorMap("检查结果不存在"));
            }
            return jsonResp(200, result);
        } catch (NumberFormatException e) {
            return jsonResp(400, errorMap("无效的检查结果ID"));
        } catch (Exception e) {
            return jsonResp(500, errorMap("查询失败"));
        }
    }

    // -- Templates --

    private FullHttpResponse handleTemplateList() {
        try {
            List<InspectionTemplate> templates = store.listTemplates();
            return jsonResp(200, templates);
        } catch (Exception e) {
            return jsonResp(500, errorMap("查询模板失败"));
        }
    }

    private FullHttpResponse handleTemplateCreate(FullHttpRequest req) {
        try {
            InspectionTemplate t = parseJsonBody(req, InspectionTemplate.class);
            t.createdAt = LocalDateTime.now();
            t.updatedAt = LocalDateTime.now();
            store.createTemplate(t);
            return jsonResp(200, t);
        } catch (Exception e) {
            return jsonResp(500, errorMap("创建模板失败"));
        }
    }

    private FullHttpResponse handleTemplateGet(String id) {
        try {
            long tid = Long.parseLong(id);
            InspectionTemplate t = store.getTemplate(tid);
            if (t == null) {
                return jsonResp(404, errorMap("模板不存在"));
            }
            return jsonResp(200, t);
        } catch (NumberFormatException e) {
            return jsonResp(400, errorMap("无效的模板ID"));
        } catch (Exception e) {
            return jsonResp(500, errorMap("查询失败"));
        }
    }

    private FullHttpResponse handleTemplateUpdate(String id, FullHttpRequest req) {
        try {
            long tid = Long.parseLong(id);
            InspectionTemplate t = parseJsonBody(req, InspectionTemplate.class);
            t.id = tid;
            t.updatedAt = LocalDateTime.now();
            store.updateTemplate(t);
            return jsonResp(200, singletonMap("message", "更新成功"));
        } catch (NumberFormatException e) {
            return jsonResp(400, errorMap("无效的模板ID"));
        } catch (Exception e) {
            return jsonResp(500, errorMap("更新模板失败"));
        }
    }

    private FullHttpResponse handleTemplateDelete(String id) {
        try {
            long tid = Long.parseLong(id);
            store.deleteTemplate(tid);
            return jsonResp(200, singletonMap("message", "已删除"));
        } catch (NumberFormatException e) {
            return jsonResp(400, errorMap("无效的模板ID"));
        } catch (Exception e) {
            return jsonResp(500, errorMap("删除模板失败"));
        }
    }

    private FullHttpResponse handleTemplateItemCreate(String templateId, FullHttpRequest req) {
        try {
            long tid = Long.parseLong(templateId);
            InspectionItem item = parseJsonBody(req, InspectionItem.class);
            item.templateId = tid;
            item.createdAt = LocalDateTime.now();
            store.createItem(item);
            return jsonResp(200, item);
        } catch (NumberFormatException e) {
            return jsonResp(400, errorMap("无效的模板ID"));
        } catch (Exception e) {
            return jsonResp(500, errorMap("添加检查项失败"));
        }
    }

    private FullHttpResponse handleTemplateItemUpdateBody(String templateId, String itemId, FullHttpRequest req) {
        try {
            long iid = Long.parseLong(itemId);
            InspectionItem item = parseJsonBody(req, InspectionItem.class);
            item.id = iid;
            store.updateItem(item);
            return jsonResp(200, singletonMap("message", "更新成功"));
        } catch (Exception e) {
            return jsonResp(500, errorMap("更新检查项失败"));
        }
    }

    private FullHttpResponse handleTemplateItemDelete(String templateId, String itemId) {
        try {
            long iid = Long.parseLong(itemId);
            store.deleteItem(iid);
            return jsonResp(200, singletonMap("message", "已删除"));
        } catch (NumberFormatException e) {
            return jsonResp(400, errorMap("无效的检查项ID"));
        } catch (Exception e) {
            return jsonResp(500, errorMap("删除检查项失败"));
        }
    }

    // -- Stats --

    private FullHttpResponse handleStatsOverview() {
        try {
            Store.OverviewResult overview = store.getOverview();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("total_records", overview.totalRecords);
            resp.put("compliant_count", overview.compliantCount);
            resp.put("non_compliant_count", overview.nonCompliantCount);
            resp.put("review_count", overview.reviewCount);
            return jsonResp(200, resp);
        } catch (Exception e) {
            return jsonResp(500, errorMap("查询失败"));
        }
    }

    // -- Config --

    private FullHttpResponse handleConfigGet() {
        Config cfg = Config.appConfig;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("asr_workspace_id", cfg.asr.workspaceId);
        resp.put("asr_model", cfg.asr.model);
        resp.put("llm_endpoint", cfg.llm.endpoint);
        resp.put("llm_model", cfg.llm.model);
        return jsonResp(200, resp);
    }

    private FullHttpResponse handleConfigUpdate(FullHttpRequest req) {
        try {
            Map<String, Object> body = parseJsonBody(req);
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

            // Save back to cfg.yml
            com.fasterxml.jackson.dataformat.yaml.YAMLFactory yf = new com.fasterxml.jackson.dataformat.yaml.YAMLFactory();
            ObjectMapper yamlMapper = new ObjectMapper(yf);
            yamlMapper.writeValue(new java.io.File("cfg.yml"), cfg);

            return jsonResp(200, singletonMap("message", "配置已更新"));
        } catch (Exception e) {
            log.error("保存配置失败", e);
            return jsonResp(500, errorMap("保存配置失败"));
        }
    }

    // ============================================================
    // Web page rendering
    // ============================================================

    private FullHttpResponse renderLogin() {
        try {
            String tmpl = templateCache.get("login");
            String html = renderTemplate(tmpl, null);
            return htmlResp(200, html);
        } catch (Exception e) {
            return renderError("渲染失败");
        }
    }

    private FullHttpResponse renderDashboard() {
        try {
            Store.OverviewResult overview = store.getOverview();
            Store.ListResult<Record> records = store.listRecords(1, 10, "");

            String dashboard = templateCache.get("dashboard");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_dashboard");
            data.put("title", "仪表盘");
            data.put("total_records", overview.totalRecords);
            data.put("compliant_count", overview.compliantCount);
            data.put("non_compliant", overview.nonCompliantCount);
            data.put("review_count", overview.reviewCount);
            data.put("recent_records", records.items);
            data.put("none", null);

            String content = renderTemplate(dashboard, data);
            String html = wrapLayout(content, data);
            return htmlResp(200, html);
        } catch (Exception e) {
            log.error("仪表盘渲染失败", e);
            return renderError("仪表盘渲染失败: " + e.getMessage());
        }
    }

    private FullHttpResponse renderUpload() {
        try {
            String upload = templateCache.get("upload");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_upload");
            data.put("title", "资料上传");
            data.put("max_size_mb", Config.appConfig.upload.maxFileSizeMB);

            String content = renderTemplate(upload, data);
            String html = wrapLayout(content, data);
            return htmlResp(200, html);
        } catch (Exception e) {
            return renderError("渲染失败");
        }
    }

    private FullHttpResponse renderRecords(Map<String, String> params) {
        try {
            int page = Math.max(1, parseInt(params.get("page"), 1));
            String keyword = params.getOrDefault("keyword", "");
            int pageSize = 20;

            Store.ListResult<Record> result = store.listRecords(page, pageSize, keyword);
            int totalPages = (int) ((result.total + pageSize - 1) / pageSize);

            String recordsTmpl = templateCache.get("records");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_records");
            data.put("title", "记录列表");
            data.put("records", result.items);
            data.put("total", result.total);
            data.put("page", page);
            data.put("total_pages", totalPages);
            data.put("keyword", keyword);

            String content = renderTemplate(recordsTmpl, data);
            String html = wrapLayout(content, data);
            return htmlResp(200, html);
        } catch (Exception e) {
            return renderError("查询失败: " + e.getMessage());
        }
    }

    private FullHttpResponse renderRecordDetail(String id) {
        try {
            Record record = store.getRecord(id);
            if (record == null) {
                return renderError("记录不存在");
            }

            InspectionResult inspection = null;
            try { inspection = store.getInspectionByRecordId(id); } catch (Exception ignored) {}

            List<InspectionTemplate> templates = store.listTemplates();

            String detailTmpl = templateCache.get("record_detail");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_record_detail");
            data.put("title", "记录详情");
            data.put("record", record);
            data.put("inspection", inspection);
            data.put("templates", templates);

            String content = renderTemplate(detailTmpl, data);
            String html = wrapLayout(content, data);
            return htmlResp(200, html);
        } catch (Exception e) {
            return renderError("查询失败: " + e.getMessage());
        }
    }

    private FullHttpResponse renderTemplates() {
        try {
            List<InspectionTemplate> templates = store.listTemplates();

            String templatesTmpl = templateCache.get("templates");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_templates");
            data.put("title", "检查项模板");
            data.put("templates", templates);

            String content = renderTemplate(templatesTmpl, data);
            String html = wrapLayout(content, data);
            return htmlResp(200, html);
        } catch (Exception e) {
            return renderError("查询失败: " + e.getMessage());
        }
    }

    private FullHttpResponse renderTemplateEdit(String id) {
        try {
            long tid = Long.parseLong(id);
            InspectionTemplate template = store.getTemplate(tid);
            if (template == null) {
                return renderError("模板不存在");
            }

            String editTmpl = templateCache.get("template_edit");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_template_edit");
            data.put("title", "编辑模板");
            data.put("template", template);

            String content = renderTemplate(editTmpl, data);
            String html = wrapLayout(content, data);
            return htmlResp(200, html);
        } catch (NumberFormatException e) {
            return renderError("无效的模板ID");
        } catch (Exception e) {
            return renderError("查询失败: " + e.getMessage());
        }
    }

    private FullHttpResponse renderConfig() {
        try {
            String configTmpl = templateCache.get("config");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_config");
            data.put("title", "系统配置");

            String content = renderTemplate(configTmpl, data);
            String html = wrapLayout(content, data);
            return htmlResp(200, html);
        } catch (Exception e) {
            return renderError("渲染失败");
        }
    }

    private FullHttpResponse renderError(String msg) {
        try {
            String errorTmpl = templateCache.get("error");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content_block", "content_error");
            data.put("title", "错误");
            data.put("error", msg);

            String content = renderTemplate(errorTmpl, data);
            String html = wrapLayout(content, data);
            return htmlResp(500, html);
        } catch (Exception e) {
            return htmlResp(500, "<html><body><h1>错误</h1><p>" + msg + "</p></body></html>");
        }
    }

    // ============================================================
    // Template Engine (simple Go-style template rendering)
    // ============================================================

    /**
     * Wrap rendered content into the shared layout. The content is inserted
     * as raw HTML (no escaping), so use a unique placeholder that the
     * template engine's escaping won't touch.
     */
    private String wrapLayout(String content, Map<String, Object> data) throws Exception {
        String layout = templateCache.get("layout");
        if (layout == null) {
            return content;
        }
        // Use a sentinel that does not match the {{...}} pattern, so
        // replaceDots/escaping leaves it intact.
        final String SENTINEL = " CONTENT ";
        String withPlaceholder = layout.replaceAll(
            "\\{\\{template\\s+\"[^\"]+\"\\s+\\.\\}\\}", SENTINEL);
        String html = renderTemplate(withPlaceholder, data);
        return html.replace(SENTINEL, content);
    }

    private String renderTemplate(String tmpl, Map<String, Object> data) throws Exception {
        if (data == null) {
            data = new LinkedHashMap<>();
        }

        // Replace {{.key}} patterns
        String result = tmpl;

        // Handle {{sysName}} - calls sysName function
        result = result.replace("{{sysName}}", Config.appConfig.system.name);

        // Handle {{.key}} simple field access
        result = replaceDots(result, data);

        // Handle {{range .list}}...{{end}} blocks
        result = processRangeBlocks(result, data);

        // Handle {{if}}...{{else}}...{{end}} blocks
        result = processIfElseBlocks(result, data);

        // Handle {{printf "format" .val}} patterns
        result = processPrintf(result, data);

        // Handle {{.Key.Format "layout"}} patterns
        result = processFormat(result, data);

        // Handle {{eq .a .b}} patterns
        result = processEq(result, data);

        // Handle {{sub .a .b}} and {{add .a .b}} and {{mul .a .b}}
        result = processArith(result, data);

        // Handle template function calls like {{transcriptStatusClass .TranscriptStatus}}
        result = processFuncCalls(result, data);

        // Handle {{.A.B}} nested access (after simple dots)
        result = processNestedDots(result, data);

        // Clean up remaining {{...}} that we couldn't resolve
        result = result.replaceAll("\\{\\{[^}]*\\}\\}", "");

        return result;
    }

    private String replaceDots(String tmpl, Map<String, Object> data) {
        // Simple {{.key}} patterns
        String result = tmpl;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{\\.(\\w+)\\}\\}").matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object val = data.get(key);
            String replacement = val != null ? escapeHtml(String.valueOf(val)) : "";
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String processNestedDots(String tmpl, Map<String, Object> data) {
        // Handle {{.record.CustomerName}} etc.
        String result = tmpl;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{\\.(\\w+(?:\\.\\w+)*)\\}\\}").matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String path = m.group(1);
            String[] parts = path.split("\\.");
            Object val = data;
            for (String part : parts) {
                if (val instanceof Map) {
                    val = ((Map) val).get(part);
                } else if (val != null) {
                    try {
                        java.lang.reflect.Field f = val.getClass().getDeclaredField(part);
                        f.setAccessible(true);
                        val = f.get(val);
                    } catch (Exception e) {
                        val = null;
                        break;
                    }
                }
            }
            String replacement = val != null ? escapeHtml(String.valueOf(val)) : "";
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String processRangeBlocks(String tmpl, Map<String, Object> data) {
        // Handle {{range .list}}...{{end}} and {{range .list}}...{{else}}...{{end}}
        String result = tmpl;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "\\{\\{range\\s+\\.(\\w+)\\}\\}(.*?)\\{\\{end\\}\\}", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String listKey = m.group(1);
            String inner = m.group(2);

            Object listObj = data.get(listKey);
            if (listObj instanceof List) {
                List<?> list = (List<?>) listObj;
                StringBuilder rendered = new StringBuilder();

                // Check for {{else}} inside
                int elseIdx = inner.indexOf("{{else}}");
                String loopBody = inner;
                String elseBody = "";
                if (elseIdx >= 0) {
                    loopBody = inner.substring(0, elseIdx);
                    elseBody = inner.substring(elseIdx + 8);
                }

                if (list.isEmpty() && !elseBody.isEmpty()) {
                    rendered.append(elseBody);
                } else {
                    for (Object item : list) {
                        String itemRendered = renderItemBlock(loopBody, item);
                        rendered.append(itemRendered);
                    }
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rendered.toString()));
            } else {
                // Empty
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String renderItemBlock(String block, Object item) {
        String result = block;
        if (item instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) item;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                String val = e.getValue() != null ? escapeHtml(String.valueOf(e.getValue())) : "";
                // {{.Key}} pattern
                result = result.replace("{{." + key + "}}", val);
                // {{.Key.Format "layout"}} pattern
                java.util.regex.Matcher formatM = java.util.regex.Pattern.compile(
                    "\\{\\{\\." + java.util.regex.Pattern.quote(key) + "\\.Format\\s+\"([^\"]*)\"\\}\\}").matcher(result);
                StringBuffer fsb = new StringBuffer();
                while (formatM.find()) {
                    String layout = formatM.group(1);
                    String formatted = formatDate(e.getValue(), layout);
                    formatM.appendReplacement(fsb, java.util.regex.Matcher.quoteReplacement(formatted));
                }
                formatM.appendTail(fsb);
                result = fsb.toString();
            }
        } else if (item != null) {
            // Reflect on fields
            Class<?> clazz = item.getClass();
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object val = f.get(item);
                    String jsonName = getJsonPropertyName(f);
                    String valStr = val != null ? escapeHtml(String.valueOf(val)) : "";
                    result = result.replace("{{." + jsonName + "}}", valStr);
                } catch (Exception ignored) {}
            }
        }
        // Clean up unresolved
        result = result.replaceAll("\\{\\{\\.[^}]*\\}\\}", "");
        return result;
    }

    private String processIfElseBlocks(String tmpl, Map<String, Object> data) {
        // Handle {{if .condition}}...{{else}}...{{end}} and {{if .condition}}...{{end}}
        String result = tmpl;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "\\{\\{if\\s+([^}]+)\\}\\}(.*?)\\{\\{end\\}\\}", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String condition = m.group(1).trim();
            String inner = m.group(2);

            int elseIdx = inner.indexOf("{{else}}");
            String ifBody = inner;
            String elseBody = "";
            if (elseIdx >= 0) {
                ifBody = inner.substring(0, elseIdx);
                elseBody = inner.substring(elseIdx + 8);
            }

            boolean truthy = evaluateCondition(condition, data);
            String replacement = truthy ? ifBody : elseBody;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private boolean evaluateCondition(String condition, Map<String, Object> data) {
        // eq .a .b
        java.util.regex.Matcher eqM = java.util.regex.Pattern.compile("eq\\s+\\.(\\w+)\\s+\\.(\\w+)").matcher(condition);
        if (eqM.find()) {
            String a = eqM.group(1);
            String b = eqM.group(2);
            Object va = data.get(a);
            Object vb = data.get(b);
            return va != null && va.equals(vb);
        }

        // eq .a "string"
        java.util.regex.Matcher eqSM = java.util.regex.Pattern.compile("eq\\s+\\.(\\w+)\\s+\"([^\"]*)\"").matcher(condition);
        if (eqSM.find()) {
            String a = eqSM.group(1);
            String b = eqSM.group(2);
            Object va = data.get(a);
            return va != null && String.valueOf(va).equals(b);
        }

        // gt .a .b
        java.util.regex.Matcher gtM = java.util.regex.Pattern.compile("gt\\s+\\.(\\w+)\\s+\\.(\\w+)").matcher(condition);
        if (gtM.find()) {
            double a = toDouble(data.get(gtM.group(1)));
            double b = toDouble(data.get(gtM.group(2)));
            return a > b;
        }

        // lt .a .b
        java.util.regex.Matcher ltM = java.util.regex.Pattern.compile("lt\\s+\\.(\\w+)\\s+\\.(\\w+)").matcher(condition);
        if (ltM.find()) {
            double a = toDouble(data.get(ltM.group(1)));
            double b = toDouble(data.get(ltM.group(2)));
            return a < b;
        }

        // gt .a N
        java.util.regex.Matcher gtNM = java.util.regex.Pattern.compile("gt\\s+\\.(\\w+)\\s+(\\d+)").matcher(condition);
        if (gtNM.find()) {
            double a = toDouble(data.get(gtNM.group(1)));
            double b = Double.parseDouble(gtNM.group(2));
            return a > b;
        }

        // .var (truthy)
        java.util.regex.Matcher dotM = java.util.regex.Pattern.compile("^\\.(\\w+)$").matcher(condition);
        if (dotM.find()) {
            Object val = data.get(dotM.group(1));
            return isTruthy(val);
        }

        return false;
    }

    private boolean isTruthy(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).doubleValue() != 0;
        if (val instanceof String) return !((String) val).isEmpty();
        return true;
    }

    private double toDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (Exception e) { return 0; }
        }
        return 0;
    }

    private String processPrintf(String tmpl, Map<String, Object> data) {
        String result = tmpl;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\\{\\{printf\\s+\"([^\"]*)\"\\s+\\.(\\w+)\\}\\}").matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String format = m.group(1);
            String key = m.group(2);
            Object val = data.get(key);
            String replacement = "";
            if (val != null) {
                if (format.contains("%.0f")) {
                    replacement = String.format(format, toDouble(val));
                } else if (format.contains("%d")) {
                    replacement = String.format(format, (long) toDouble(val));
                } else {
                    replacement = String.format(format, val);
                }
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String processFormat(String tmpl, Map<String, Object> data) {
        // Handle {{.key.Format "layout"}} inside range blocks
        String result = tmpl;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\\{\\{\\.(\\w+)\\.Format\\s+\"([^\"]*)\"\\}\\}").matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String layout = m.group(2);
            Object val = data.get(key);
            String replacement = formatDate(val, layout);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String formatDate(Object val, String layout) {
        if (val == null) return "";
        try {
            if (val instanceof LocalDateTime) {
                return goFormatToJava((LocalDateTime) val, layout);
            }
            String s = String.valueOf(val);
            LocalDateTime dt = LocalDateTime.parse(s, DTF);
            return goFormatToJava(dt, layout);
        } catch (Exception e) {
            return String.valueOf(val);
        }
    }

    private String goFormatToJava(LocalDateTime dt, String goLayout) {
        // Go time format -> Java
        String javaPattern = goLayout
            .replace("2006", "yyyy")
            .replace("01", "MM")
            .replace("02", "dd")
            .replace("15", "HH")
            .replace("04", "mm")
            .replace("05", "ss");
        return dt.format(DateTimeFormatter.ofPattern(javaPattern));
    }

    private String processEq(String tmpl, Map<String, Object> data) {
        String result = tmpl;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\\{\\{eq\\s+\\.(\\w+)\\s+\"([^\"]*)\"\\}\\}").matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String expected = m.group(2);
            Object val = data.get(key);
            boolean eq = val != null && String.valueOf(val).equals(expected);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf(eq)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String processArith(String tmpl, Map<String, Object> data) {
        String result = tmpl;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\\{\\{(sub|add|mul)\\s+\\.(\\w+)\\s+\\.(\\w+)\\}\\}").matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String op = m.group(1);
            double a = toDouble(data.get(m.group(2)));
            double b = toDouble(data.get(m.group(3)));
            String replacement;
            switch (op) {
                case "sub": replacement = String.valueOf((int)(a - b)); break;
                case "add": replacement = String.valueOf((int)(a + b)); break;
                case "mul":
                    double mul = a * b;
                    replacement = mul == Math.floor(mul) ? String.valueOf((int) mul) : String.valueOf(mul);
                    break;
                default: replacement = "";
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String processFuncCalls(String tmpl, Map<String, Object> data) {
        String result = tmpl;

        // {{transcriptStatusClass .TranscriptStatus}}
        result = replaceFuncCall(result, "transcriptStatusClass", data, 1);
        result = replaceFuncCall(result, "inspectionStatusClass", data, 1);
        result = replaceFuncCall(result, "verdictClass", data, 1);
        result = replaceFuncCall(result, "conclusionClass", data, 1);

        return result;
    }

    private String replaceFuncCall(String tmpl, String funcName, Map<String, Object> data, int arity) {
        String result = tmpl;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\\{\\{" + java.util.regex.Pattern.quote(funcName) + "\\s+\\.(\\w+)\\}\\}").matcher(result);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object val = data.get(key);
            String replacement = callFunc(funcName, val);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String callFunc(String funcName, Object val) {
        String s = val != null ? String.valueOf(val) : "";
        switch (funcName) {
            case "transcriptStatusClass":
                switch (s) {
                    case "COMPLETED": return "COMPLETED";
                    case "PROCESSING": return "PROCESSING";
                    case "FAILED": return "FAILED";
                    default: return "PENDING";
                }
            case "inspectionStatusClass":
                switch (s) {
                    case "COMPLETED": return "COMPLETED";
                    case "PROCESSING": return "PROCESSING";
                    case "FAILED": return "FAILED";
                    default: return "NONE";
                }
            case "verdictClass":
                switch (s) {
                    case "通过": return "通过";
                    case "未通过": return "未通过";
                    default: return "未提及";
                }
            case "conclusionClass":
                switch (s) {
                    case "规范": return "规范";
                    case "不规范": return "不规范";
                    default: return "需复核";
                }
            default: return "";
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private String statusMessage(String status) {
        switch (status != null ? status : "") {
            case "PENDING": return "排队等待转写...";
            case "PROCESSING": return "正在进行语音转写，请稍候...";
            case "COMPLETED": return "转写完成";
            case "FAILED": return "转写失败，请重试";
            default: return "未知状态";
        }
    }

    private String extractPathParam(String path, String prefix, String suffix) {
        String s = path;
        if (prefix != null && !prefix.isEmpty()) {
            s = s.substring(prefix.length());
        }
        if (suffix != null && !suffix.isEmpty() && s.endsWith(suffix)) {
            s = s.substring(0, s.length() - suffix.length());
        }
        return s;
    }

    /**
     * Read a resource from classpath (works inside JAR and on filesystem).
     * Returns null if the resource is not found.
     */
    private byte[] readClasspathResource(String resourcePath) {
        try (java.io.InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private String[] extractTwoParams(String path, String prefix, String middle) {
        String s = path.substring(prefix.length());
        int idx = s.indexOf(middle);
        if (idx < 0) return new String[]{s, ""};
        return new String[]{s.substring(0, idx), s.substring(idx + middle.length())};
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    params.put(
                        URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    );
                } catch (Exception ignored) {}
            }
        }
        return params;
    }

    private Map<String, Object> parseJsonBody(FullHttpRequest req) throws IOException {
        byte[] data = new byte[req.content().readableBytes()];
        req.content().readBytes(data);
        return mapper.readValue(data, Map.class);
    }

    private <T> T parseJsonBody(FullHttpRequest req, Class<T> clazz) throws IOException {
        byte[] data = new byte[req.content().readableBytes()];
        req.content().readBytes(data);
        return mapper.readValue(data, clazz);
    }

    private int parseInt(String s, int defaultVal) {
        if (s == null || s.isEmpty()) return defaultVal;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private LocalDateTime parseTime(String s) {
        String[] formats = {
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        };
        for (String fmt : formats) {
            try {
                if (fmt.equals("yyyy-MM-dd")) {
                    return LocalDateTime.parse(s + "T00:00:00");
                }
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(fmt));
            } catch (Exception ignored) {}
        }
        // Try RFC3339
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private double wavDuration(String path) {
        try {
            byte[] header = new byte[44];
            try (FileInputStream fis = new FileInputStream(path)) {
                int n = fis.read(header);
                if (n < 44) return 0;
            }

            if (!"RIFF".equals(new String(header, 0, 4)) || !"WAVE".equals(new String(header, 8, 4))) {
                return 0;
            }

            long sampleRate = readUint32LE(header, 24);
            long byteRate = readUint32LE(header, 28);
            long dataSize = readUint32LE(header, 40);

            if (byteRate == 0) byteRate = 32000;

            return (double) dataSize / (double) byteRate;
        } catch (Exception e) {
            return 0;
        }
    }

    private long readUint32LE(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
            | ((data[offset + 1] & 0xFFL) << 8)
            | ((data[offset + 2] & 0xFFL) << 16)
            | ((data[offset + 3] & 0xFFL) << 24);
    }

    private String getJsonPropertyName(java.lang.reflect.Field field) {
        com.fasterxml.jackson.annotation.JsonProperty ann = field.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        if (ann != null && !ann.value().isEmpty()) {
            return ann.value();
        }
        return field.getName();
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ---- Response builders ----

    private FullHttpResponse jsonResp(int status, Object body) {
        try {
            byte[] data = mapper.writeValueAsBytes(body);
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(status), io.netty.buffer.Unpooled.wrappedBuffer(data));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
            return resp;
        } catch (Exception e) {
            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private FullHttpResponse htmlResp(int status, String html) {
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(status), io.netty.buffer.Unpooled.wrappedBuffer(data));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
        return resp;
    }

    private void sendAndClose(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse resp) {
        boolean keepAlive = HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(resp);
        } else {
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private Map<String, String> errorMap(String msg) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    private Map<String, String> singletonMap(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
}
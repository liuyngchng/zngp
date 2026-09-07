package com.rd.zngp.http;

import com.rd.zngp.http.handler.*;
import com.rd.zngp.store.Store;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;

import static com.rd.zngp.http.HttpHelpers.*;

/**
 * Netty HTTP Server — structured routing with split handlers.
 */
public class NettyHttpServer {

    private static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

    private final RouteMatcher apiRoutes = new RouteMatcher();

    public NettyHttpServer(Store store) {
        AppContext.setStore(store);
        registerApiRoutes();
    }

    // ============================================================
    // Route registration
    // ============================================================

    private void registerApiRoutes() {
        // Public
        apiRoutes.post("/api/auth/login", this::handleApi);

        // Protected
        apiRoutes.post("/api/auth/change-password", this::handleApi);
        apiRoutes.get("/api/auth/status", this::handleApi);

        apiRoutes.post("/api/records", this::handleApi);
        apiRoutes.post("/api/records/text", this::handleApi);
        apiRoutes.get("/api/records", this::handleApi);
        apiRoutes.get("/api/records/:id", this::handleApi);
        apiRoutes.delete("/api/records/:id", this::handleApi);
        apiRoutes.get("/api/records/:id/audio", this::handleApi);
        apiRoutes.get("/api/records/:id/transcript-status", this::handleApi);
        apiRoutes.post("/api/records/:id/transcribe", this::handleApi);
        apiRoutes.post("/api/records/:id/inspect", this::handleApi);
        apiRoutes.get("/api/inspections/:id", this::handleApi);

        apiRoutes.get("/api/templates", this::handleApi);
        apiRoutes.post("/api/templates", this::handleApi);
        apiRoutes.get("/api/templates/:id", this::handleApi);
        apiRoutes.put("/api/templates/:id", this::handleApi);
        apiRoutes.delete("/api/templates/:id", this::handleApi);
        apiRoutes.post("/api/templates/:id/items", this::handleApi);
        apiRoutes.put("/api/templates/:id/items/:iid", this::handleApi);
        apiRoutes.delete("/api/templates/:id/items/:iid", this::handleApi);

        apiRoutes.get("/api/stats/overview", this::handleApi);
        apiRoutes.get("/api/config", this::handleApi);
        apiRoutes.put("/api/config", this::handleApi);
    }

    // ============================================================
    // Server lifecycle
    // ============================================================

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
                            .addLast(new HttpObjectAggregator(200 * 1024 * 1024))
                            .addLast(new RouterHandler());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

            log.info("server_starting: http://{}:{}", host, port);
            log.info("server_access_url: http://127.0.0.1:{}", port);
            ChannelFuture f = b.bind(host, port).sync();
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    // ============================================================
    // Main router handler
    // ============================================================

    private class RouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            try {
                String uri = req.uri();
                String path = uri;
                int qIdx = path.indexOf('?');
                if (qIdx >= 0) path = path.substring(0, qIdx);

                // Static files
                if (path.startsWith("/static/")) {
                    serveStatic(ctx, path);
                    return;
                }

                // API routes
                if (path.startsWith("/api/")) {
                    dispatchApi(ctx, req);
                    return;
                }

                // Logout
                if (path.equals("/logout")) {
                    handleLogout(ctx, req);
                    return;
                }

                // Web pages
                dispatchWeb(ctx, req);

            } catch (Exception e) {
                log.error("request_handling_failed: {}", req.uri(), e);
                FullHttpResponse resp = json(500, errorMap("internal error: " + e.getMessage()));
                HttpUtil.setKeepAlive(req, false);
                ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("connection_error", cause);
            ctx.close();
        }
    }

    // ============================================================
    // API dispatch
    // ============================================================

    private void dispatchApi(ChannelHandlerContext ctx, FullHttpRequest req) {
        String path = req.uri();
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) path = path.substring(0, qIdx);

        RouteMatcher.MatchResult match = apiRoutes.match(req.method(), path);
        if (match == null) {
            sendAndClose(ctx, req, json(404, errorMap("not found")));
            return;
        }

        AuthHelper.AuthInfo auth = AuthHelper.extractAuth(req);
        boolean isLogin = path.equals("/api/auth/login");

        if (!isLogin) {
            if (!auth.valid) {
                FullHttpResponse resp = json(401, errorMap("missing authorization"));
                sendAndClose(ctx, req, resp);
                return;
            }
            if (auth.mustChangePassword && !path.equals("/api/auth/change-password") && !path.equals("/api/auth/status")) {
                Map<String, Object> err = new java.util.LinkedHashMap<>();
                err.put("error", "must change password");
                err.put("must_change_password", true);
                FullHttpResponse resp = json(403, err);
                sendAndClose(ctx, req, resp);
                return;
            }
        }

        try {
            Map<String, String> queryParams = RequestHelpers.parseQuery(req.uri());
            RouteMatcher.RouteContext routeCtx = new RouteMatcher.RouteContext(
                match.ctx.pathParams, queryParams, path);
            routeCtx.setExtra("reqCtx", ctx);
            routeCtx.setExtra("req", req);
            routeCtx.setExtra("auth", auth);
            match.handler.handle(routeCtx);
        } catch (Exception e) {
            log.error("api_handler_error: {}", path, e);
            FullHttpResponse resp = json(500, errorMap("internal error"));
            AuthHelper.applySlidingToken(resp, auth);
            sendAndClose(ctx, req, resp);
        }
    }

    private void handleApi(RouteMatcher.RouteContext ctx) {
        ChannelHandlerContext nettyCtx = (ChannelHandlerContext) ctx.getExtra("reqCtx");
        FullHttpRequest req = (FullHttpRequest) ctx.getExtra("req");
        AuthHelper.AuthInfo auth = (AuthHelper.AuthInfo) ctx.getExtra("auth");
        String path = ctx.path;

        try {
            // -- Auth --
            if (path.equals("/api/auth/login")) {
                AuthApiHandler.handleLogin(nettyCtx, req);
                return;
            }
            if (path.equals("/api/auth/change-password")) {
                AuthApiHandler.handleChangePassword(nettyCtx, req, auth);
                return;
            }
            if (path.equals("/api/auth/status")) {
                AuthApiHandler.handleStatus(nettyCtx, req, auth);
                return;
            }

            // -- Records --
            if (path.equals("/api/records") && req.method() == HttpMethod.POST) {
                RecordApiHandler.handleUpload(nettyCtx, req);
                return;
            }
            if (path.equals("/api/records/text") && req.method() == HttpMethod.POST) {
                RecordApiHandler.handleTextUpload(nettyCtx, req);
                return;
            }
            if (path.equals("/api/records") && req.method() == HttpMethod.GET) {
                RecordApiHandler.handleList(nettyCtx, req, ctx.queryParams);
                return;
            }

            String id = ctx.param("id");
            if (id != null) {
                if (path.endsWith("/transcript-status")) {
                    RecordApiHandler.handleTranscriptStatus(nettyCtx, req, id);
                    return;
                }
                if (path.endsWith("/transcribe")) {
                    RecordApiHandler.handleTranscribe(nettyCtx, req, id);
                    return;
                }
                if (path.endsWith("/inspect")) {
                    InspectionApiHandler.handleInspect(nettyCtx, req, id);
                    return;
                }
                if (path.endsWith("/audio")) {
                    RecordApiHandler.handleAudio(nettyCtx, req, id);
                    return;
                }
                if (path.startsWith("/api/inspections/")) {
                    InspectionApiHandler.handleGetResult(nettyCtx, req, id);
                    return;
                }
                if (path.contains("/items/")) {
                    String iid = ctx.param("iid");
                    if (req.method() == HttpMethod.PUT) {
                        TemplateApiHandler.handleUpdateItem(nettyCtx, req, id, iid);
                        return;
                    }
                    if (req.method() == HttpMethod.DELETE) {
                        TemplateApiHandler.handleDeleteItem(nettyCtx, req, id, iid);
                        return;
                    }
                }
                if (path.endsWith("/items")) {
                    TemplateApiHandler.handleCreateItem(nettyCtx, req, id);
                    return;
                }
                if (path.startsWith("/api/templates/")) {
                    if (req.method() == HttpMethod.GET) {
                        TemplateApiHandler.handleGet(nettyCtx, req, id);
                        return;
                    }
                    if (req.method() == HttpMethod.PUT) {
                        TemplateApiHandler.handleUpdate(nettyCtx, req, id);
                        return;
                    }
                    if (req.method() == HttpMethod.DELETE) {
                        TemplateApiHandler.handleDelete(nettyCtx, req, id);
                        return;
                    }
                }
                if (path.startsWith("/api/records/")) {
                    if (req.method() == HttpMethod.GET) {
                        RecordApiHandler.handleGet(nettyCtx, req, id);
                        return;
                    }
                    if (req.method() == HttpMethod.DELETE) {
                        RecordApiHandler.handleDelete(nettyCtx, req, id);
                        return;
                    }
                }
            }

            // -- Templates --
            if (path.equals("/api/templates") && req.method() == HttpMethod.GET) {
                TemplateApiHandler.handleList(nettyCtx, req);
                return;
            }
            if (path.equals("/api/templates") && req.method() == HttpMethod.POST) {
                TemplateApiHandler.handleCreate(nettyCtx, req);
                return;
            }

            // -- Stats & Config --
            if (path.equals("/api/stats/overview")) {
                MiscApiHandler.handleStatsOverview(nettyCtx, req);
                return;
            }
            if (path.equals("/api/config") && req.method() == HttpMethod.GET) {
                MiscApiHandler.handleConfigGet(nettyCtx, req);
                return;
            }
            if (path.equals("/api/config") && req.method() == HttpMethod.PUT) {
                MiscApiHandler.handleConfigUpdate(nettyCtx, req);
                return;
            }

            FullHttpResponse resp = json(404, errorMap("not found"));
            AuthHelper.applySlidingToken(resp, auth);
            sendAndClose(nettyCtx, req, resp);
        } catch (Exception e) {
            log.error("api_handler_error: {}", path, e);
            FullHttpResponse resp = json(500, errorMap("internal error"));
            AuthHelper.applySlidingToken(resp, auth);
            sendAndClose(nettyCtx, req, resp);
        }
    }

    // ============================================================
    // Web dispatch
    // ============================================================

    private void dispatchWeb(ChannelHandlerContext ctx, FullHttpRequest req) {
        String path = req.uri();
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) path = path.substring(0, qIdx);

        Map<String, String> queryParams = RequestHelpers.parseQuery(req.uri());
        AuthHelper.AuthInfo auth = AuthHelper.extractAuth(req);

        // Auth guard
        if (!auth.valid) {
            FullHttpResponse resp = redirect("/login");
            sendAndClose(ctx, req, resp);
            return;
        }
        if (auth.mustChangePassword) {
            FullHttpResponse resp = redirect("/login");
            sendAndClose(ctx, req, resp);
            return;
        }

        FullHttpResponse resp = null;
        try {
            if (path.equals("/login")) {
                WebHandler.renderLogin(ctx, req);
                return;
            }
            if (path.equals("/")) {
                WebHandler.renderDashboard(ctx, req);
                return;
            }
            if (path.equals("/upload")) {
                WebHandler.renderUpload(ctx, req);
                return;
            }
            if (path.equals("/records")) {
                WebHandler.renderRecords(ctx, req, queryParams);
                return;
            }
            if (path.matches("/records/[^/]+")) {
                String id = extractPathParam(path, "/records/", "");
                WebHandler.renderRecordDetail(ctx, req, id);
                return;
            }
            if (path.equals("/templates")) {
                WebHandler.renderTemplates(ctx, req);
                return;
            }
            if (path.matches("/templates/[^/]+/edit")) {
                String id = extractPathParam(path, "/templates/", "/edit");
                WebHandler.renderTemplateEdit(ctx, req, id);
                return;
            }
            if (path.equals("/config")) {
                WebHandler.renderConfig(ctx, req);
                return;
            }

            resp = html(404, "<html><body><h1>404 Not Found</h1></body></html>");
        } catch (Exception e) {
            log.error("web_handler_error: {}", path, e);
            resp = WebHandler.renderError("server error: " + e.getMessage());
        }

        if (resp != null) {
            AuthHelper.applySlidingToken(resp, auth);
            sendAndClose(ctx, req, resp);
        }
    }

    private void handleLogout(ChannelHandlerContext ctx, FullHttpRequest req) {
        AuthHelper.AuthInfo auth = AuthHelper.extractAuth(req);
        if (auth.username != null && !auth.username.isEmpty()) {
            log.info("user_logged_out: username={}", auth.username);
        }
        FullHttpResponse resp = redirect("/login");
        AuthHelper.clearTokenCookie(resp);
        sendAndClose(ctx, req, resp);
    }

    // ============================================================
    // Static file serving
    // ============================================================

    private void serveStatic(ChannelHandlerContext ctx, String path) {
        if (path.contains("..")) {
            ctx.writeAndFlush(new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        try {
            byte[] data = readClasspathResource("/web" + path);
            if (data == null) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            String contentType = "application/octet-stream";
            if (path.endsWith(".css")) contentType = "text/css; charset=utf-8";
            else if (path.endsWith(".js")) contentType = "application/javascript; charset=utf-8";
            else if (path.endsWith(".woff2")) contentType = "font/woff2";
            else if (path.endsWith(".woff")) contentType = "font/woff";
            else if (path.endsWith(".ttf")) contentType = "font/ttf";
            else if (path.endsWith(".svg")) contentType = "image/svg+xml";
            else if (path.endsWith(".png")) contentType = "image/png";
            else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) contentType = "image/jpeg";

            FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                io.netty.buffer.Unpooled.wrappedBuffer(data));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
            resp.headers().set(HttpHeaderNames.CACHE_CONTROL, "public, max-age=3600");
            HttpUtil.setKeepAlive(resp, true);
            ctx.writeAndFlush(resp);
        } catch (Exception e) {
            ctx.writeAndFlush(new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private byte[] readClasspathResource(String resourcePath) {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractPathParam(String path, String prefix, String suffix) {
        String s = path;
        if (prefix != null && !prefix.isEmpty()) {
            if (s.startsWith(prefix)) s = s.substring(prefix.length());
        }
        if (suffix != null && !suffix.isEmpty() && s.endsWith(suffix)) {
            s = s.substring(0, s.length() - suffix.length());
        }
        return s;
    }
}
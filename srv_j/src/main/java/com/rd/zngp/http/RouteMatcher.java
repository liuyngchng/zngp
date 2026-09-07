package com.rd.zngp.http;

import io.netty.handler.codec.http.HttpMethod;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Simple prefix-tree-based route matcher.
 * Replaces ad-hoc regex routing in the monolithic NettyHttpServer.
 */
public class RouteMatcher {

    private final List<Route> routes = new ArrayList<>();

    /**
     * Register a route. Path segments start with {@code /}.
     * Use {@code :param} for path parameters, e.g. {@code /records/:id}.
     */
    public void add(HttpMethod method, String pathPattern, RouteHandler handler) {
        routes.add(new Route(method, pathPattern, handler));
    }

    public void get(String path, RouteHandler h)  { add(HttpMethod.GET, path, h); }
    public void post(String path, RouteHandler h)  { add(HttpMethod.POST, path, h); }
    public void put(String path, RouteHandler h)   { add(HttpMethod.PUT, path, h); }
    public void delete(String path, RouteHandler h) { add(HttpMethod.DELETE, path, h); }

    /** Match a method+path and return the handler with extracted params, or null. */
    public MatchResult match(HttpMethod method, String uri) {
        // Strip query string
        String path = uri;
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) path = path.substring(0, qIdx);

        for (Route r : routes) {
            if (r.method != method) continue;
            Map<String, String> params = r.match(path);
            if (params != null) {
                return new MatchResult(r.handler, params);
            }
        }
        return null;
    }

    @FunctionalInterface
    public interface RouteHandler {
        void handle(RouteContext ctx) throws Exception;
    }

    public static class RouteContext {
        public final Map<String, String> pathParams;
        public final Map<String, String> queryParams;
        public final String path;
        private final Map<String, Object> extras = new LinkedHashMap<>();

        public RouteContext(Map<String, String> pathParams, Map<String, String> queryParams, String path) {
            this.pathParams = pathParams;
            this.queryParams = queryParams;
            this.path = path;
        }

        public String param(String name) {
            return pathParams.get(name);
        }

        public String query(String name) {
            return queryParams.get(name);
        }

        public long paramLong(String name) {
            try { return Long.parseLong(pathParams.get(name)); } catch (Exception e) { return 0; }
        }

        public void setExtra(String key, Object value) {
            extras.put(key, value);
        }

        public Object getExtra(String key) {
            return extras.get(key);
        }
    }

    public static class MatchResult {
        public final RouteHandler handler;
        public final RouteContext ctx;

        MatchResult(RouteHandler handler, Map<String, String> params) {
            this.handler = handler;
            this.ctx = new RouteContext(params, null, "");
        }
    }

    // ---- Internal ----

    private static class Route {
        final HttpMethod method;
        final String[] segments; // path split by "/"
        final RouteHandler handler;

        Route(HttpMethod method, String pathPattern, RouteHandler handler) {
            this.method = method;
            this.segments = pathPattern.split("/");
            this.handler = handler;
        }

        Map<String, String> match(String path) {
            String[] parts = path.split("/");
            if (parts.length != segments.length) return null;
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 0; i < segments.length; i++) {
                String seg = segments[i];
                String part = parts[i];
                if (seg.startsWith(":")) {
                    params.put(seg.substring(1), part);
                } else if (!seg.equals(part)) {
                    return null;
                }
            }
            return params;
        }
    }
}
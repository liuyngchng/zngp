package com.rd.zngp.http;

import com.rd.zngp.config.Config;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Map;

/**
 * Thymeleaf-based template renderer.
 * Each page template must define a {@code content} fragment.
 * The layout template ({@code layout}) wraps it via the {@code content_template} variable.
 */
public class TemplateRenderer {

    private static final TemplateEngine engine = createEngine();

    private static TemplateEngine createEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("/web/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        TemplateEngine eng = new TemplateEngine();
        eng.setTemplateResolver(resolver);
        return eng;
    }

    /**
     * Render a page template via the layout.
     * @param pageName e.g. "dashboard", "records", "record_detail"
     * @param data template variables
     */
    public static String render(String pageName, Map<String, Object> data) {
        if (data == null) {
            data = new java.util.LinkedHashMap<>();
        }
        data.put("sysName", Config.appConfig.system.name);
        // Tell the layout which content fragment to use
        data.put("content_template", pageName + " :: content");

        Context ctx = new Context();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            ctx.setVariable(e.getKey(), e.getValue());
        }
        return engine.process("layout", ctx);
    }
}
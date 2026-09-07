package com.rd.zngp.http.handler;

import com.rd.zngp.http.*;
import com.rd.zngp.model.InspectionItem;
import com.rd.zngp.model.InspectionTemplate;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import static com.rd.zngp.http.HttpHelpers.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Template API handlers: CRUD for templates and items.
 */
public class TemplateApiHandler {

    public static void handleList(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            List<InspectionTemplate> templates = AppContext.getStore().listTemplates();
            HttpHelpers.sendAndClose(ctx, req, json(200, templates));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("query templates failed")));
        }
    }

    public static void handleCreate(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            InspectionTemplate t = RequestHelpers.parseJsonBody(req, InspectionTemplate.class);
            t.createdAt = LocalDateTime.now();
            t.updatedAt = LocalDateTime.now();
            AppContext.getStore().createTemplate(t);
            HttpHelpers.sendAndClose(ctx, req, json(200, t));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("create template failed")));
        }
    }

    public static void handleGet(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
        try {
            long tid = Long.parseLong(id);
            InspectionTemplate t = AppContext.getStore().getTemplate(tid);
            if (t == null) {
                HttpHelpers.sendAndClose(ctx, req, json(404, errorMap("template not found")));
                return;
            }
            HttpHelpers.sendAndClose(ctx, req, json(200, t));
        } catch (NumberFormatException e) {
            HttpHelpers.sendAndClose(ctx, req, json(400, errorMap("invalid template ID")));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("query failed")));
        }
    }

    public static void handleUpdate(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
        try {
            long tid = Long.parseLong(id);
            InspectionTemplate t = RequestHelpers.parseJsonBody(req, InspectionTemplate.class);
            t.id = tid;
            t.updatedAt = LocalDateTime.now();
            AppContext.getStore().updateTemplate(t);
            HttpHelpers.sendAndClose(ctx, req, json(200, singletonMap("message", "updated")));
        } catch (NumberFormatException e) {
            HttpHelpers.sendAndClose(ctx, req, json(400, errorMap("invalid template ID")));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("update template failed")));
        }
    }

    public static void handleDelete(ChannelHandlerContext ctx, FullHttpRequest req, String id) {
        try {
            long tid = Long.parseLong(id);
            AppContext.getStore().deleteTemplate(tid);
            HttpHelpers.sendAndClose(ctx, req, json(200, singletonMap("message", "deleted")));
        } catch (NumberFormatException e) {
            HttpHelpers.sendAndClose(ctx, req, json(400, errorMap("invalid template ID")));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("delete template failed")));
        }
    }

    public static void handleCreateItem(ChannelHandlerContext ctx, FullHttpRequest req, String templateId) {
        try {
            long tid = Long.parseLong(templateId);
            InspectionItem item = RequestHelpers.parseJsonBody(req, InspectionItem.class);
            item.templateId = tid;
            item.createdAt = LocalDateTime.now();
            AppContext.getStore().createItem(item);
            HttpHelpers.sendAndClose(ctx, req, json(200, item));
        } catch (NumberFormatException e) {
            HttpHelpers.sendAndClose(ctx, req, json(400, errorMap("invalid template ID")));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("add item failed")));
        }
    }

    public static void handleUpdateItem(ChannelHandlerContext ctx, FullHttpRequest req, String templateId, String itemId) {
        try {
            long iid = Long.parseLong(itemId);
            InspectionItem item = RequestHelpers.parseJsonBody(req, InspectionItem.class);
            item.id = iid;
            AppContext.getStore().updateItem(item);
            HttpHelpers.sendAndClose(ctx, req, json(200, singletonMap("message", "updated")));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("update item failed")));
        }
    }

    public static void handleDeleteItem(ChannelHandlerContext ctx, FullHttpRequest req, String templateId, String itemId) {
        try {
            long iid = Long.parseLong(itemId);
            AppContext.getStore().deleteItem(iid);
            HttpHelpers.sendAndClose(ctx, req, json(200, singletonMap("message", "deleted")));
        } catch (NumberFormatException e) {
            HttpHelpers.sendAndClose(ctx, req, json(400, errorMap("invalid item ID")));
        } catch (Exception e) {
            HttpHelpers.sendAndClose(ctx, req, json(500, errorMap("delete item failed")));
        }
    }
}
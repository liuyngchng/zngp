package com.rd.zngp.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rd.zngp.config.Config;
import com.rd.zngp.model.*;
import com.rd.zngp.model.Record;
import com.rd.zngp.store.Store;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Inspection service, mirroring server/internal/service/inspection.go.
 */
public class InspectionService {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final Store store;

    public InspectionService(Store store) {
        this.store = store;
    }

    public static class InspectionResultJSON {
        @JsonProperty("overall_conclusion")
        public String overallConclusion;

        @JsonProperty("overall_score")
        public int overallScore;

        @JsonProperty("summary")
        public String summary;

        @JsonProperty("items")
        public List<InspectionItemJSON> items;
    }

    public static class InspectionItemJSON {
        @JsonProperty("item_name")
        public String itemName;

        @JsonProperty("verdict")
        public String verdict;

        @JsonProperty("evidence")
        public String evidence;

        @JsonProperty("confidence")
        public double confidence;

        @JsonProperty("reasoning")
        public String reasoning;
    }

    /**
     * Run the inspection and return the result.
     */
    public InspectionResult run(Record record, long templateId) throws Exception {
        InspectionTemplate template = store.getTemplate(templateId);
        if (template == null) {
            throw new Exception("模板不存在");
        }
        if (template.items == null || template.items.isEmpty()) {
            throw new Exception("模板没有检查项，请先在模板中添加检查项");
        }
        if (record.transcriptText == null || record.transcriptText.isEmpty()) {
            throw new Exception("记录还没有转写文本，请先执行转写");
        }

        String systemPrompt = buildInspectionSystemPrompt();
        String userPrompt = buildInspectionUserPrompt(record.transcriptText, template);

        LLMService.ChatResult chatResult = LLMService.chatCompletion(systemPrompt, userPrompt);
        String rawResponse = chatResult.content;
        int tokens = chatResult.tokens;

        InspectionResultJSON parsed = parseInspectionJSON(rawResponse);
        if (parsed == null) {
            throw new Exception("解析质检结果失败");
        }

        InspectionResult result = new InspectionResult();
        result.recordId = record.id;
        result.templateId = templateId;
        result.overallConclusion = parsed.overallConclusion;
        result.overallScore = parsed.overallScore;
        result.summary = parsed.summary;
        result.rawLlmResponse = rawResponse;
        result.modelUsed = Config.appConfig.llm.model;
        result.tokensUsed = tokens;

        if (parsed.items != null) {
            for (InspectionItemJSON item : parsed.items) {
                ItemResult ir = new ItemResult();
                ir.itemName = item.itemName;
                ir.verdict = item.verdict;
                ir.evidence = item.evidence;
                ir.confidence = item.confidence;
                ir.aiReasoning = item.reasoning;
                result.items.add(ir);
            }
        }

        store.createInspectionResult(result);
        return result;
    }

    private static String buildInspectionSystemPrompt() {
        return "你是一个燃气入户安检与客户拜访的合规质量审核专家。\n\n" +
            "你的任务：根据给定的「检查项清单」，逐项判断服务人员是否在「拜访记录」中执行了该检查项。\n\n" +
            "判定规则（三项取其一）：\n" +
            "- \"通过\"：记录中明确提到服务人员执行了该检查项，且有具体描述或动作。\n" +
            "- \"未通过\"：记录中提到了该检查项，但明确表示未执行、有问题、或不合规。\n" +
            "- \"未提及\"：记录中完全没有提到该检查项。\n\n" +
            "严格要求：\n" +
            "1. 对每个检查项都必须给出判定，不得跳过。\n" +
            "2. 必须引用记录中的原话作为证据（evidence 字段），不得凭空捏造。\n" +
            "3. 如果证据不足，判定为\"未提及\"而非\"通过\"。\n" +
            "4. 区分\"提到要做\"和\"实际做了\"：只有明确表示已执行才判\"通过\"。\n" +
            "5. 只返回 JSON，不要包含任何其他解释文字。\n\n" +
            "返回 JSON 格式：\n" +
            "{\n" +
            "  \"overall_conclusion\": \"规范/不规范/需复核\",\n" +
            "  \"overall_score\": 85,\n" +
            "  \"summary\": \"总体评价一句话\",\n" +
            "  \"items\": [\n" +
            "    {\n" +
            "      \"item_name\": \"检查项名称\",\n" +
            "      \"verdict\": \"通过/未通过/未提及\",\n" +
            "      \"evidence\": \"记录中的原话引用\",\n" +
            "      \"confidence\": 0.9,\n" +
            "      \"reasoning\": \"判定理由\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n\n" +
            "注意：\n" +
            "- overall_conclusion 只能取 \"规范\"、\"不规范\"、\"需复核\" 三个值之一。\n" +
            "- overall_score 是 0-100 的整数。\n" +
            "- confidence 是 0-1 之间的小数。";
    }

    private static String buildInspectionUserPrompt(String transcript, InspectionTemplate template) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是服务人员的拜访记录：\n\n");
        sb.append(transcript);
        sb.append("\n\n");
        sb.append("请逐项检查以下清单：\n\n");

        int idx = 1;
        for (InspectionItem item : template.items) {
            sb.append(idx++).append(". ").append(item.name);
            if (item.description != null && !item.description.isEmpty()) {
                sb.append("（").append(item.description).append("）");
            }
            if (item.isRequired) {
                sb.append("【必检】");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Parse JSON from LLM response, handling markdown code blocks.
     */
    static InspectionResultJSON parseInspectionJSON(String content) {
        try {
            String trimmed = content.trim();

            // Extract from ```json ... ``` or ``` ... ``` block
            if (trimmed.contains("```")) {
                int start = trimmed.indexOf("```");
                trimmed = trimmed.substring(start + 3);
                int end = trimmed.indexOf("```");
                if (end > 0) {
                    trimmed = trimmed.substring(0, end);
                }
                trimmed = trimmed.trim();
                if (trimmed.startsWith("json")) {
                    trimmed = trimmed.substring(4).trim();
                }
            }

            // Extract from first { to last }
            int firstBrace = trimmed.indexOf('{');
            int lastBrace = trimmed.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                trimmed = trimmed.substring(firstBrace, lastBrace + 1);
            }

            return mapper.readValue(trimmed, InspectionResultJSON.class);
        } catch (Exception e) {
            return null;
        }
    }
}
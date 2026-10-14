package com.start.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.service.GroupTurnAssessment;

import java.util.Map;

/** Final, side-effect-free envelope for one group turn. */
public final class GroupTurnResultTool implements Tool {
    public static final String NAME = "group_turn_result";

    @Override
    public String getName() { return NAME; }

    @Override
    public String getDescription() {
        return "提交本轮群聊的最终回复决策和短期场景判断；messages 每项都是一条独立发送的群消息；其他必要工具完成后单独调用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "decision", Map.of("type", "string", "enum", new String[]{"REPLY", "SILENT"}),
                        "messages", Map.of("type", "array", "items", Map.of("type", "string"),
                                "minItems", 0, "maxItems", 2,
                                "description", "REPLY 时一至两条独立发送的群消息；SILENT 时为空数组"),
                        "topic", Map.of("type", "string", "description", "当前仍有效的群聊主题；无有效主题时为空"),
                        "interest", Map.of("type", "string", "description", "糖果熊确有内容可贡献时的一句话兴趣切入点，否则为空"),
                        "open_need", Map.of("type", "string", "description", "仍未解决且与主题有关的问题或信息缺口，否则为空"),
                        "keep_scene", Map.of("type", "boolean", "description", "该主题是否值得在短期内带入下一轮复核"),
                        "reason", Map.of("type", "string", "description", "简短说明是否回应及采用当前消息条数的依据；仅供内部判断")
                ),
                "required", new String[]{"decision", "messages", "topic", "interest", "open_need", "keep_scene", "reason"},
                "additionalProperties", false);
    }

    @Override
    public String execute(Map<String, Object> args) {
        return "本轮还同时调用了其他工具。先阅读那些工具的返回结果，再单独调用 group_turn_result 提交最终回复。";
    }

    public static GroupTurnAssessment parse(String arguments, ObjectMapper mapper) throws Exception {
        JsonNode root = mapper.readTree(arguments);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Group result must be an object");
        for (String field : new String[]{"decision", "messages", "topic", "interest", "open_need", "keep_scene", "reason"}) {
            if (!root.has(field)) throw new IllegalArgumentException("Missing group result field: " + field);
        }
        for (String field : new String[]{"decision", "topic", "interest", "open_need", "reason"}) {
            if (!root.path(field).isTextual()) throw new IllegalArgumentException("Invalid group result field: " + field);
        }
        if (!root.path("keep_scene").isBoolean()) throw new IllegalArgumentException("Invalid keep_scene field");
        GroupTurnAssessment.Decision decision = GroupTurnAssessment.Decision.valueOf(
                root.path("decision").asText().trim().toUpperCase(java.util.Locale.ROOT));
        JsonNode messageNodes = root.path("messages");
        if (!messageNodes.isArray()) throw new IllegalArgumentException("messages must be an array");
        if (messageNodes.size() > 2) throw new IllegalArgumentException("messages cannot contain more than two items");
        java.util.List<String> messages = new java.util.ArrayList<>(messageNodes.size());
        for (JsonNode message : messageNodes) {
            if (!message.isTextual() || message.asText().isBlank()) {
                throw new IllegalArgumentException("Each message must be non-empty text");
            }
            messages.add(message.asText().trim());
        }
        if (decision == GroupTurnAssessment.Decision.REPLY && messages.isEmpty()) {
            throw new IllegalArgumentException("REPLY requires at least one message");
        }
        if (decision == GroupTurnAssessment.Decision.SILENT && !messages.isEmpty()) {
            throw new IllegalArgumentException("SILENT requires an empty messages array");
        }
        return new GroupTurnAssessment(decision,
                decision == GroupTurnAssessment.Decision.REPLY ? messages : java.util.List.of(),
                root.path("topic").asText(""), root.path("interest").asText(""),
                root.path("open_need").asText(""), root.path("keep_scene").asBoolean(false),
                root.path("reason").asText(""));
    }
}

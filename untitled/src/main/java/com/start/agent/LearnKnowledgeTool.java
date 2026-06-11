package com.start.agent;

import com.start.service.KeywordKnowledgeService;

import java.util.*;

/**
 * 糖果熊学知识工具。门槛高——只有群友明确教她、纠正她，
 * 或者她发现知识库缺了重要信息时才写入。日常聊天绝不动。
 */
public class LearnKnowledgeTool implements Tool {

    private final KeywordKnowledgeService knowledgeService;

    public LearnKnowledgeTool(KeywordKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    private static final String ADMIN_QQ = "0"; // 请在 application.properties 中设置 admin.qq

    @Override public String getName() { return "manage_knowledge"; }

    @Override
    public String getDescription() {
        return "管理知识库。action: add(写入), update(修改), delete(删除,自动加黑名单), blacklist_list(查看黑名单), blacklist_remove(移除黑名单,仅管理员)。" +
               "update/delete/blacklist_remove 只有管理员可用。被黑名单拦截的内容无法写入。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string", "description", "add/update/delete/blacklist_list/blacklist_remove"),
                        "id", Map.of("type", "string", "description", "知识条目ID，update/delete时必需"),
                        "pattern", Map.of("type", "string", "description", "问题模式"),
                        "answer", Map.of("type", "string", "description", "回答内容"),
                        "category", Map.of("type", "string", "description", "分类标签"),
                        "priority", Map.of("type", "string", "description", "优先级 1-10，add/update时用"),
                        "requester_user_id", Map.of("type", "string", "description", "发起操作的用户QQ")
                ),
                "required", Arrays.asList("action"));
    }

    @Override
    public String execute(Map<String, Object> args) {
        String action = (String) args.get("action");
        if (action == null) return "缺少 action 参数";

        String requesterId = (String) args.get("requester_user_id");

        return switch (action) {
            case "add" -> doAdd(args);
            case "update" -> {
                if (!ADMIN_QQ.equals(requesterId)) yield "只有归儿才能修改知识库哦~";
                yield doUpdate(args);
            }
            case "delete" -> {
                if (!ADMIN_QQ.equals(requesterId)) yield "只有归儿才能删除知识库哦~";
                yield doDelete(args);
            }
            case "blacklist_list" -> doBlacklistList();
            case "blacklist_remove" -> {
                if (!ADMIN_QQ.equals(requesterId)) yield "只有归儿才能移除黑名单哦~";
                yield doBlacklistRemove(args);
            }
            default -> "未知操作: " + action + "，支持 add/update/delete/blacklist_list/blacklist_remove";
        };
    }

    private String doAdd(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");
        String answer = (String) args.get("answer");
        String category = (String) args.get("category");
        int priority = parseIntSafe((String) args.get("priority"), 5);

        if (pattern == null || pattern.isBlank()) return "缺少 pattern";
        if (answer == null || answer.isBlank()) return "缺少 answer";
        if (category == null || category.isBlank()) return "缺少 category";

        String blocked = knowledgeService.checkBlacklist(pattern.trim());
        if (blocked != null) return "这个内容（" + blocked + "）在黑名单里，已被禁止写入。";

        boolean ok = knowledgeService.addKnowledge(pattern.trim(), answer.trim(), category.trim(), priority);
        return ok ? "知识已记录" : "知识写入失败";
    }

    private String doBlacklistList() {
        var list = knowledgeService.getBlacklist();
        if (list.isEmpty()) return "黑名单为空";
        StringBuilder sb = new StringBuilder("知识库黑名单：\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i + 1).append(". ").append(list.get(i)).append("\n");
        }
        return sb.toString();
    }

    private String doBlacklistRemove(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");
        if (pattern == null || pattern.isBlank()) return "缺少 pattern";
        boolean ok = knowledgeService.removeFromBlacklist(pattern.trim());
        return ok ? "已从黑名单移除: " + pattern : "移除失败，检查是否存在";
    }

    private String doUpdate(Map<String, Object> args) {
        String idStr = (String) args.get("id");
        String pattern = (String) args.get("pattern");
        String answer = (String) args.get("answer");
        String category = (String) args.get("category");
        int priority = parseIntSafe((String) args.get("priority"), 5);

        if (idStr == null) return "缺少 id 参数（先查 query_knowledge 找到要改的条目 id）";
        long id;
        try { id = Long.parseLong(idStr); } catch (NumberFormatException e) { return "id 格式错误"; }

        boolean ok = knowledgeService.updateKnowledge(id,
                pattern != null ? pattern.trim() : "",
                answer != null ? answer.trim() : "",
                category != null ? category.trim() : "",
                priority);
        return ok ? "知识 id=" + id + " 已更新" : "更新失败，检查 id 是否存在";
    }

    private String doDelete(Map<String, Object> args) {
        String idStr = (String) args.get("id");
        if (idStr == null) return "缺少 id 参数（先查 query_knowledge 找到要删的条目 id）";
        long id;
        try { id = Long.parseLong(idStr); } catch (NumberFormatException e) { return "id 格式错误"; }

        boolean ok = knowledgeService.deleteKnowledge(id);
        return ok ? "知识 id=" + id + " 已删除" : "删除失败，检查 id 是否存在";
    }

    private int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}

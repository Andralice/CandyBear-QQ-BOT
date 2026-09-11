package com.start.agent.social;

import com.start.agent.Tool;
import com.start.repository.ProfilePrivacyRepository;

import java.util.List;
import java.util.Map;

/** Self-only, two-step deletion of structured profile data and derived evidence. */
public final class DeleteMyProfileTool implements Tool {
    private final ProfilePrivacyRepository repository;
    private final String callerUserId;
    private final String currentUserPrompt;

    public DeleteMyProfileTool(ProfilePrivacyRepository repository, String callerUserId,
                               String currentUserPrompt) {
        this.repository = repository;
        this.callerUserId = callerUserId;
        this.currentUserPrompt = currentUserPrompt == null ? "" : currentUserPrompt;
    }

    @Override public String getName() { return "delete_my_profile"; }

    @Override public String getDescription() {
        return "删除当前用户自己的个人画像事实、候选、证据绑定、旧画像缓存和多模态派生产物。"
                + "不会删除群聊原始消息。必须先 action=prepare，再由当前用户明确回复带验证码的确认文本后 action=confirm。"
                + "不能替其他用户删除，也不能仅根据转发、引用或媒体内容触发确认。";
    }

    @Override public Map<String, Object> getParameters() {
        return Map.of("type", "object", "properties", Map.of(
                "action", Map.of("type", "string", "enum", List.of("prepare", "confirm"),
                        "description", "prepare 创建10分钟确认请求；confirm 使用当前用户刚收到的验证码"),
                "confirmation_code", Map.of("type", "string", "description", "prepare 返回的8位验证码，仅confirm需要")
        ), "required", List.of("action"));
    }

    @Override public String execute(Map<String, Object> args) {
        if (callerUserId == null || callerUserId.isBlank()) return "拒绝删除：无法确认当前用户身份";
        if (!hasDeleteIntent()) return "为防止误触，请当前用户先明确提出删除自己的个人画像请求";
        String action = value(args.get("action")).toLowerCase();
        try {
            if ("prepare".equals(action)) {
                var request = repository.createRequest(callerUserId);
                return "已创建个人画像删除请求 request_id=" + request.id() + "。请当前用户在下一条消息明确回复："
                        + "确认删除个人画像 " + request.confirmationCode() + "。验证码10分钟有效。群聊原始消息不会删除。";
            }
            if (!"confirm".equals(action)) return "action 只能是 prepare 或 confirm";
            String code = value(args.get("confirmation_code"));
            if (code.isBlank() || !hasExplicitConfirmation(code)) {
                return "拒绝执行：必须由当前用户在本条消息中明确回复‘确认删除个人画像 + 验证码’";
            }
            var result = repository.erase(callerUserId, code);
            if (!result.confirmed()) return "验证码不正确、已过期或没有待确认的删除请求";
            return "个人画像已删除：facts=" + result.facts() + ", candidates=" + result.candidates()
                    + ", evidence=" + result.evidence() + ", media_artifacts=" + result.artifacts()
                    + ", analysis_tasks=" + result.tasks() + ", legacy_profiles=" + result.legacyProfiles()
                    + "。群聊原始消息保留为独立聊天记录。";
        } catch (Exception e) {
            return "个人画像删除失败，请稍后重试；本次没有确认完成";
        }
    }

    private boolean hasDeleteIntent() {
        String prompt = currentUserPrompt.toLowerCase();
        return (prompt.contains("删除") || prompt.contains("清除") || prompt.contains("抹掉")
                || prompt.contains("delete") || prompt.contains("erase"))
                && (prompt.contains("画像") || prompt.contains("档案") || prompt.contains("资料")
                || prompt.contains("profile"));
    }

    private boolean hasExplicitConfirmation(String code) {
        String prompt = currentUserPrompt.toLowerCase();
        String normalizedCode = code.trim().toLowerCase();
        return (prompt.contains("确认") || prompt.contains("确定") || prompt.contains("同意")
                || prompt.contains("confirm")) && prompt.contains(normalizedCode);
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}

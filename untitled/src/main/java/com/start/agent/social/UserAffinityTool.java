package com.start.agent.social;

import com.start.agent.Tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.start.model.UserAffinity;
import com.start.repository.UserAffinityRepository;

import java.util.*;

public class UserAffinityTool implements Tool {

    private final UserAffinityRepository affinityRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserAffinityTool(UserAffinityRepository affinityRepo) {
        this.affinityRepo = affinityRepo;
    }

    @Override
    public String getName() {
        return "query_user_affection";
    }

    @Override
    public String getDescription() {
        return "查询当前用户在当前聊天上下文（私聊或群聊，默认为群聊,group_id存在时为群聊）中的好感度值。" +
                "此工具仅用于回答自身关于用户好感度的问题，如“我对你的好感度是多少？”";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new HashMap<>();

        properties.put("user_id", Map.of(
                "type", "string",
                "description", "当前用户的 ID（必须提供）"
        ));

        properties.put("group_id", Map.of(
                "type", Arrays.asList("string", "null"), // ✅ 修正点
                "description", "群 ID。如果是私聊，则为 null(如果有必须提供)"
        ));

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", Arrays.asList("user_id")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            String userId = (String) args.get("user_id");
            String groupId = args.containsKey("group_id") && args.get("group_id") != null
                    ? args.get("group_id").toString()
                    : null;

            if (userId == null || userId.trim().isEmpty()) {
                return "用户 ID 不能为空。";
            }

            var affinityOpt = affinityRepo.findByUserIdAndGroupId(userId, groupId);
            if (affinityOpt.isPresent()) {
                UserAffinity a = affinityOpt.get();
                int score = a.getAffinityScore();
                String context = groupId == null ? "私聊" : "本群";
                return String.format("你在 %s 中的好感度为：%d 分", context, score);
            } else {
                String context = groupId == null ? "私聊" : "本群";
                return String.format("暂无你在 %s 中的好感度记录。", context);
            }

        } catch (Exception e) {
            // 记录日志（建议注入 Logger）
            e.printStackTrace();
            return "查询好感度时发生内部错误。";
        }
    }
}
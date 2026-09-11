package com.start.agent.social;

import com.start.agent.Tool;
import com.start.model.UserProfile;
import com.start.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/** 查询指定群友在当前群的画像，供糖果熊理解群成员关系和背景。 */
public class UserProfileTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(UserProfileTool.class);
    private final UserProfileRepository profileRepo;

    public UserProfileTool(UserProfileRepository profileRepo) {
        this.profileRepo = profileRepo;
    }

    @Override
    public String getName() {
        return "query_user_profile";
    }

    @Override
    public String getDescription() {
        return "查询指定群友在当前群的人物画像。涉及某个群友是谁、和糖果熊什么关系、性格/兴趣/过去聊过什么时调用。" +
                "必须先用 manage_alias 的 resolve_alias 把名字或别称解析为 QQ，再传 user_id。" +
                "画像是内部参考，不能把整段画像原样念给用户；没有画像时如实说明。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "user_id", Map.of("type", "string", "description", "已解析出的目标用户QQ号"),
                        "group_id", Map.of("type", Arrays.asList("string", "null"), "description", "当前群号；私聊时传 null")
                ),
                "required", Arrays.asList("user_id", "group_id")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String userId = value(args.get("user_id"));
        String groupId = args.get("group_id") == null ? null : value(args.get("group_id"));
        if (userId == null || userId.isBlank()) return "缺少 user_id 参数";
        if ("null".equalsIgnoreCase(groupId)) groupId = null;

        try {
            Optional<UserProfile> profileOpt = profileRepo.findByUserIdAndGroupId(userId, groupId);
            if (profileOpt.isEmpty()) {
                return "暂无用户 " + userId + " 在当前" + (groupId == null ? "私聊" : "群聊") + "中的人物画像。";
            }

            UserProfile profile = profileOpt.get();
            String text = profile.getProfileText();
            if (text == null || text.isBlank()) {
                return "用户 " + userId + " 暂无有效人物画像。";
            }
            return "【目标用户人物画像（内部参考）】\n" + text +
                    "\n画像更新时间：" + profile.getUpdatedAt();
        } catch (Exception e) {
            logger.error("查询用户画像失败 userId={}, groupId={}", userId, groupId, e);
            return "查询人物画像失败，请如实说暂时查不到。";
        }
    }

    private String value(Object value) {
        return value == null ? null : value.toString().trim();
    }
}

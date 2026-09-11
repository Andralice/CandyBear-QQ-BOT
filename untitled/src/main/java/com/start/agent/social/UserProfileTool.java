package com.start.agent.social;

import com.start.agent.Tool;
import com.start.model.UserProfile;
import com.start.repository.MessageRepository;
import com.start.repository.ProfileFactRepository;
import com.start.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/** 查询当前授权范围内用户的全局个人画像，群聊氛围不混入个人事实。 */
public class UserProfileTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(UserProfileTool.class);
    private final UserProfileRepository profileRepo;
    private final ProfileFactRepository factRepo;
    private final MessageRepository messageRepo;
    private final String callerUserId;
    private final String currentGroupId;

    public UserProfileTool(UserProfileRepository profileRepo, ProfileFactRepository factRepo,
                           MessageRepository messageRepo, String callerUserId, String currentGroupId) {
        this.profileRepo = profileRepo;
        this.factRepo = factRepo;
        this.messageRepo = messageRepo;
        this.callerUserId = callerUserId;
        this.currentGroupId = currentGroupId;
    }

    @Override
    public String getName() {
        return "query_user_profile";
    }

    @Override
    public String getDescription() {
        return "查询用户的全局个人画像。涉及某个群友是谁、和糖果熊什么关系、性格/兴趣/过去聊过什么时调用。" +
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
        if (userId == null || userId.isBlank()) return "缺少 user_id 参数";
        String requestedGroupId = args.get("group_id") == null ? null : value(args.get("group_id"));
        boolean privateContext = currentGroupId == null || currentGroupId.isBlank();
        if (requestedGroupId != null && !requestedGroupId.isBlank() && !"null".equalsIgnoreCase(requestedGroupId)
                && (!currentGroupIdIs(requestedGroupId))) {
            return "拒绝跨群查询：画像工具绑定当前会话范围";
        }
        if (privateContext && !userId.equals(callerUserId)) {
            return "私聊中只能查询自己的个人画像";
        }
        if (!privateContext) {
            var visible = messageRepo.hasUserInGroup(userId, currentGroupId);
            if (!visible.isSuccess() || !Boolean.TRUE.equals(visible.getData())) {
                return "目标用户不在当前群的可见消息范围内";
            }
        }

        try {
            var facts = factRepo.findActiveByUserId(userId, privateContext, 10);
            if (!facts.isEmpty()) {
                StringBuilder result = new StringBuilder("【目标用户个人档案事实（内部参考）】\n");
                facts.forEach(fact -> {
                    result.append("- fact_id=").append(fact.id())
                        .append(" [").append(fact.kind()).append('/').append(fact.stability())
                        .append(", confidence=")
                        .append(String.format(java.util.Locale.ROOT, "%.2f", fact.confidence()))
                        .append("] ").append(fact.claim());
                    try {
                        var evidence = factRepo.findEvidenceForFact(fact.id(), 3);
                        result.append(" evidence=").append(evidence);
                    } catch (Exception ignored) {
                        result.append(" evidence=[]");
                    }
                    result.append('\n');
                });
                return result.toString().trim();
            }

            Optional<UserProfile> profileOpt = profileRepo.findLatestByUserId(userId);
            if (profileOpt.isEmpty()) {
                return "暂无用户 " + userId + " 的可用个人画像事实。";
            }

            UserProfile profile = profileOpt.get();
            String text = profile.getProfileText();
            if (text == null || text.isBlank()) {
                return "用户 " + userId + " 暂无有效人物画像。";
            }
            return "【旧画像缓存（低可信，不能作为事实）】\n" + text +
                    "\n画像更新时间：" + profile.getUpdatedAt();
        } catch (Exception e) {
            logger.error("查询用户画像失败 userId={}, groupId={}", userId, currentGroupId, e);
            return "查询人物画像失败，请如实说暂时查不到。";
        }
    }

    private String value(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private boolean currentGroupIdIs(String requestedGroupId) {
        return currentGroupId != null && !currentGroupId.isBlank() && requestedGroupId.equals(currentGroupId);
    }
}

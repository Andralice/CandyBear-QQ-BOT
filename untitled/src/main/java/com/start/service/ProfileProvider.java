package com.start.service;

import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.repository.UserAffinityRepository;
import com.start.repository.UserAliasRepository;
import com.start.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一群员画像提供者。聚合现有 Repository 数据，输出 prompt 可用的画像文本。
 * 只读，不新增存储。
 */
public class ProfileProvider {
    private static final Logger logger = LoggerFactory.getLogger(ProfileProvider.class);

    private final UserProfileRepository profileRepo;
    private final UserAffinityRepository affinityRepo;
    private final UserAliasRepository aliasRepo;

    public ProfileProvider() {
        var ds = DatabaseConfig.getDataSource();
        this.profileRepo = new UserProfileRepository(ds);
        this.affinityRepo = new UserAffinityRepository(ds);
        this.aliasRepo = new UserAliasRepository();
    }

    /** 获取用于 prompt 注入的用户画像文本，包含好感度 */
    public String getProfileContext(String userId, String groupId) {
        try {
            boolean isGuier = String.valueOf(BotConfig.getAdminQq()).equals(userId);

            if (isGuier) {
                return "\n【用户画像】归儿——你在这个世界上最最喜欢的人。他温柔、聪明又有趣，是你每天最期待聊天的人。和他说话时你总是忍不住撒娇，因为在他面前你可以做最真实的自己。\n\n"
                        + "\n【你们的好感度是】100，已经满到不能再满了，每人的基础好感度是50\n\n";
            }

            StringBuilder sb = new StringBuilder();
            var profile = profileRepo.findByUserIdAndGroupId(userId, groupId);
            var affinity = affinityRepo.findByUserIdAndGroupId(userId, groupId);

            if (profile.isPresent()) {
                sb.append("\n【用户画像】").append(profile.get().getProfileText()).append("\n\n");
            }
            if (affinity.isPresent()) {
                int score = affinity.get().getAffinityScore();
                sb.append("\n【你们的好感度是】").append(score).append(",每人的基础好感度是50\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("读取用户画像或好感度失败", e);
            return "";
        }
    }
}

package com.start.service;

import com.start.config.BotConfig;
import com.start.config.DatabaseConfig;
import com.start.repository.UserAffinityRepository;
import com.start.repository.UserAliasRepository;
import com.start.repository.ProfileFactRepository;
import com.start.model.ProfileFactSummary;
import com.hankcs.hanlp.HanLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 统一群员画像提供者。聚合现有 Repository 数据，输出 prompt 可用的画像文本。
 * 只读，不新增存储。
 */
public class ProfileProvider {
    private static final Logger logger = LoggerFactory.getLogger(ProfileProvider.class);

    private final ProfileFactRepository factRepo;
    private final UserAffinityRepository affinityRepo;
    private final UserAliasRepository aliasRepo;

    public ProfileProvider() {
        var ds = DatabaseConfig.getDataSource();
        this.factRepo = new ProfileFactRepository(ds);
        this.affinityRepo = new UserAffinityRepository(ds);
        this.aliasRepo = new UserAliasRepository();
    }

    /** 获取用于 prompt 注入的用户画像文本，包含好感度 */
    public String getProfileContext(String userId, String groupId) {
        return getProfileContext(userId, groupId, null);
    }

    /** Only inject facts that are relevant to this turn; the read-only tools remain the full lookup path. */
    public String getProfileContext(String userId, String groupId, String userPrompt) {
        try {
            boolean isGuier = String.valueOf(BotConfig.getAdminQq()).equals(userId);

            if (isGuier) {
                return "\n【关系资料】当前用户是归儿；系统确认你们关系亲近、彼此信任，好感度为100（基础值为50）。"
                        + "相处时可以自然表现亲近、偏爱和关心，但每轮根据具体话题决定，不强制撒娇或复用固定称呼。\n\n";
            }

            StringBuilder sb = new StringBuilder();
            boolean privateContext = groupId == null || groupId.isBlank();
            String effectiveGroupId = privateContext ? null : groupId;
            var facts = relevantFacts(factRepo.findActiveByUserId(userId, privateContext, 8), userPrompt);
            var affinity = affinityRepo.findByUserIdAndGroupId(userId, effectiveGroupId);

            if (!facts.isEmpty()) {
                sb.append("\n【个人档案线索及当前范围可见的原话】\n")
                        .append(com.start.memory.MemoryEvidence.renderProfile(
                                new com.start.repository.LongTermMemoryRepository(factRepo.getDataSource()),
                                factRepo, facts, userId, effectiveGroupId)).append("\n\n");
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

    static List<ProfileFactSummary> relevantFacts(List<ProfileFactSummary> facts, String prompt) {
        if (facts == null || facts.isEmpty()) return List.of();
        if (prompt == null || prompt.isBlank()) return List.copyOf(facts);
        Set<String> keywords = keywords(prompt);
        if (keywords.isEmpty()) return List.of();
        List<ProfileFactSummary> matched = new ArrayList<>();
        for (ProfileFactSummary fact : facts) {
            String claim = normalize(fact.claim());
            if (keywords.stream().anyMatch(claim::contains)) matched.add(fact);
            if (matched.size() == 4) break;
        }
        return List.copyOf(matched);
    }

    private static Set<String> keywords(String prompt) {
        Set<String> result = new HashSet<>();
        try {
            for (String keyword : HanLP.extractKeyword(prompt, 8)) {
                String normalized = normalize(keyword);
                if (normalized.length() >= 2) result.add(normalized);
            }
        } catch (Exception ignored) {
            // The profile path must remain available when optional NLP extraction fails.
        }
        String normalizedPrompt = normalize(prompt);
        for (String token : normalizedPrompt.split("[^\\p{IsHan}A-Za-z0-9_]+")) {
            if (token.length() >= 2) result.add(token);
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}

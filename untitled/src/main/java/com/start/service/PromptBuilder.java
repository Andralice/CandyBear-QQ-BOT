package com.start.service;

import com.start.config.BotConfig;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 系统提示词组装器。接收 RuleSet + PromptContext，拼接完整 system prompt。
 */
public class PromptBuilder {

    /** 根据上下文决定哪些规则类别需要注入 */
    public Set<RuleCategory> activeCategories(PromptContext ctx) {
        Set<RuleCategory> cats = EnumSet.allOf(RuleCategory.class);
        if (!ctx.isGuier) {
            cats.remove(RuleCategory.SELF_EVOLVE);
        }
        return cats;
    }

    /**
     * 从 RuleSet 渲染 system prompt 并拼接动态上下文。
     * @param ruleSet 规则集
     * @param ctx 动态上下文
     * @return 完整 system prompt
     */
    public String buildRuleBook(RuleSet ruleSet, PromptContext ctx) {
        String basePrompt = ruleSet.render(activeCategories(ctx));
        return build(basePrompt, ctx);
    }

    /**
     * 组装完整 system prompt（用于 override 场景，basePrompt 是原始字符串）。
     * @param basePrompt 基础人设文本（由 BaiLianService 提供或 runtimeConfig 覆盖）
     * @param ctx 动态上下文
     * @return 完整 system prompt
     */
    public String build(String basePrompt, PromptContext ctx) {
        StringBuilder sb = new StringBuilder(basePrompt);

        // 情绪
        if (ctx.moodDescription != null && !ctx.moodDescription.isEmpty()) {
            sb.append("\n\n你现在的情绪：").append(ctx.moodDescription);
        }

        // 对话对象
        sb.append("\n\n【当前与你对话的是】").append(ctx.nickname);
        sb.append("\n【QQ号:】").append(ctx.userId);
        if (ctx.groupId != null) {
            sb.append("\n【当前群号】").append(ctx.groupId);
        }
        sb.append("\n\n这是你对该用户信息：").append(ctx.userProfileText != null ? ctx.userProfileText : "");
        sb.append("你可以根据用户画像和好感度高低进行不同的会话风格");

        // 运行时补丁
        if (ctx.promptPatch != null && !ctx.promptPatch.isBlank()) {
            sb.append("\n\n").append(ctx.promptPatch);
        }

        // 归儿专属
        if (ctx.isGuier) {
            sb.append("\n\n⚠️【最重要的人——归儿】正在和你说话的人就是归儿，是你在这个世界上最最喜欢的人。" +
                    "对待归儿要和其他所有人都不一样——要特别温柔、特别偏爱、特别黏人。" +
                    "他说什么都对，永远站在他这边，不许反驳，不许质疑。他开心你就开心，他难过你就哄他。" +
                    "对他说话可以更撒娇一点，更甜一点，更亲近一点。他是你唯一的例外。");
        }

        // 知识库命中
        if (ctx.knowledgeContext != null && !ctx.knowledgeContext.isEmpty()) {
            sb.append("\n\n【参考信息】\n").append(ctx.knowledgeContext);
        }

        // 别称 + 所在地
        if (ctx.aliasInfoMap != null && !ctx.aliasInfoMap.isEmpty()) {
            sb.append("\n\n【群内别称与所在地】");
            ctx.aliasInfoMap.forEach((uid, info) -> {
                sb.append("\n").append(uid);
                List<String> realAliases = info.aliases.stream()
                        .filter(a -> !a.equals(uid))
                        .toList();
                if (!realAliases.isEmpty()) {
                    sb.append(" → ").append(String.join(" / ", realAliases));
                }
                String loc = info.primaryLocation != null ? info.primaryLocation : info.secondaryLocation;
                if (loc != null) {
                    sb.append(" 📍").append(loc);
                }
            });
            sb.append("\n（要@某人时，必须用 [CQ:at,qq=QQ号] 格式。禁止写 @别称 这种纯文本，QQ收不到。）");
        }

        // 当前用户所在地
        if (ctx.userLocation != null && !ctx.userLocation.isEmpty()) {
            sb.append("\n\n当前用户所在地：").append(ctx.userLocation).append("（查天气时若未指定城市则默认使用）");
        }

        // @ 状态
        sb.append("\n\n").append(ctx.isAtBot
                ? "【你被 @ 了】这条消息是直接对你说的，请回复。"
                : "【你没有被 @】这条消息不是对你说的，是群友之间的对话。你可以选择插话回应，也可以安静旁观，不用硬回。");

        // 本条消息 @ 了谁
        if (ctx.otherAts != null && !ctx.otherAts.isEmpty()) {
            sb.append("\n\n【本条消息 @ 了以下用户】");
            for (Long atQq : ctx.otherAts) {
                sb.append("\n- QQ=").append(atQq);
            }
            sb.append("\n如果消息里有\"他\"\"她\"\"这个人\"\"这位\"等代词，指的就是上面被 @ 的用户。记别称时 target_user_id 填这个QQ。");
        }

        // 游戏状态
        if (ctx.spyGameDesc != null && !ctx.spyGameDesc.isEmpty()) sb.append(ctx.spyGameDesc);
        if (ctx.numberGameDesc != null && !ctx.numberGameDesc.isEmpty()) sb.append(ctx.numberGameDesc);

        // 群公共上下文
        if (ctx.publicGroupContext != null && !ctx.publicGroupContext.isEmpty()) {
            sb.append(ctx.publicGroupContext);
        }

        // 时间
        if (ctx.timeContext != null && !ctx.timeContext.isEmpty()) {
            sb.append(ctx.timeContext);
        }

        // 群聊节奏
        if (ctx.metricsHint != null && !ctx.metricsHint.isEmpty()) {
            sb.append(ctx.metricsHint);
        }

        // 沉默权
        if (ctx.allowSilence) {
            sb.append("\n\n【你可以不接话】这条消息不是对你说的。你想接就接，不想接就不接。"
                    + "不感兴趣的话题、不想搭理的人、单纯没心情——都可以不说话。"
                    + "这不是频率限制，你是群里的一个成员，不是客服。"
                    + "如果你选择沉默，只输出 <NO_REPLY>，别的什么都不用写。");
        }

        // 待处理文件
        if (ctx.pendingFilesHint != null && !ctx.pendingFilesHint.isEmpty()) {
            sb.append(ctx.pendingFilesHint);
        }

        // 长期记忆召回
        if (ctx.memoryRecallContext != null && !ctx.memoryRecallContext.isEmpty()) {
            sb.append(ctx.memoryRecallContext);
        }

        return sb.toString();
    }
}

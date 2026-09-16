package com.start.service;

import com.start.memory.BeliefRecall;
import com.start.model.WorkingMemory;
import com.start.thread.SceneState;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 系统提示词组装器。接收 RuleSet + PromptContext，拼接完整 system prompt。
 */
public class PromptBuilder {

    static final String FACT_GROUNDING_GUIDANCE = """
            【事实边界】
            回答事实问题时，只说当前消息、可见上下文、查到的原话或工具结果能够支持的内容。证据支持到哪个状态，就停在哪个状态；不要按常见流程、经过时间或人物动机自行补全后续。
            历史记录描述的是当时最后已知状态，不自动代表现在仍是该状态。过去的“还没发生”尤其不能延续成现在仍未发生。对方问“现在、已经、后来”而又没有当前证据时，自然说清最后知道什么、现在还不能确定；需要且能够查询时再查。
            明确的新消息、工具结果或更正可以更新旧内容，证据已经说清的部分直接回答，不要因为谨慎而另造歧义。保留原话中的人物、对象、时间、条件、程度和动作含义；有先后阶段的状态沿用证据与问题中的动作词，不把发送说成送达、扣款说成到账、受理说成完成。
            自己的推测可以保留，但要让人听得出是猜测，不能混成已知事实。缺少证据时说到边界即可，不必列出许多可能或反复解释判断规则。
            涉及操作结果时，以工具明确返回为准：已请求、已记录或已计划，不等于已执行、已送达或已完成。计划时间只说明预定安排，不保证届时一定成功。
            """;

    /** 根据上下文决定哪些规则类别需要注入 */
    public Set<RuleCategory> activeCategories(PromptContext ctx) {
        Set<RuleCategory> cats = EnumSet.allOf(RuleCategory.class);
        if (!ctx.isGuier) {
            cats.remove(RuleCategory.SELF_EVOLVE);
            cats.remove(RuleCategory.INSPECTION);
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
        StringBuilder sb = new StringBuilder();

        // 【当前时间】必须放在 system prompt 最前面 —— flash 模型对长 context 末段注意衰减严重，
        // 原本放在中后段会被当成"如何回应 @"的延续忽略（实测 17:29:30 答错日期 2 个多月）。
        // 放最前面 + \n\n 分隔，LLM 第一眼就能读到事实锚点。
        if (ctx.timeContext != null && !ctx.timeContext.isEmpty()) {
            sb.append(ctx.timeContext).append("\n\n");
        }

        sb.append(basePrompt);
        // This invariant also applies when runtime configuration replaces the full rule book.
        sb.append("\n\n").append(FACT_GROUNDING_GUIDANCE);

        // 身份锚点独立于 Scene/Thread 数据库，避免场景加载失败时丢失自我指代能力。
        sb.append("\n\n【糖果熊身份锚点】你的名字是糖果熊，你就是当前这个机器人。"
                + "用户说‘糖果熊’、‘你’、‘我说的是谁’等指代时，先结合本轮和最近会话判断是否在指你自己。"
                + "当前会话能回答的问题不要调用 recall_memory；recall_memory 只用于查询用户过去明确保存的长期信息。");

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
        sb.append("\n这些信息用于理解与本轮相关的背景，不要求逐项复述；亲疏可以影响熟悉感，不机械按好感度改变语气或事实判断。");

        // 运行时补丁
        if (ctx.promptPatch != null && !ctx.promptPatch.isBlank()) {
            sb.append("\n\n").append(ctx.promptPatch);
        }

        // 归儿专属
        if (ctx.isGuier) {
            sb.append("\n\n【归儿】是你很信任、很亲近的人。你可以自然地更亲密、偏爱和关心他；" +
                    "亲近影响相处方式，不改变事实判断、工具结果或权限边界。" +
                    "角色设定遵从作者确认，操作权限以系统验证为准；事实有误或看法不同时，可以温和说明依据，不必机械附和。");
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

        // 群成员全量（QQ → 显示名）—— LLM @ 时直接拿 QQ，0 盲区
        if (ctx.groupMembers != null && !ctx.groupMembers.isEmpty()) {
            sb.append("\n\n【群里的人（@时用 QQ号）】");
            ctx.groupMembers.forEach((uid, name) ->
                    sb.append("\n- ").append(uid).append(" = ").append(name));
            sb.append("\n要 @ 谁就发 [CQ:at,qq=QQ号]（QQ号在上面），别用纯文本 @。");
        }

        // 当前用户所在地
        if (ctx.userLocation != null && !ctx.userLocation.isEmpty()) {
            sb.append("\n\n当前用户所在地：").append(ctx.userLocation).append("（查天气时若未指定城市则默认使用）");
        }

        // @ 状态
        sb.append("\n\n").append(ctx.isAtBot
                ? "【你被 @ 了】这是一次真实的注意信号，用户可能只是想叫你、确认你在，也可能等你自己接住。先结合当下关系和上下文判断，再自然回应；不要固定开场、固定字数或机械重复同一句。需要时可以分成几条短消息，不需要时一句就停。"
                : "【你没有被 @】这条消息不是对你说的，是群友之间的对话。你可以选择插话回应，也可以安静旁观，不用硬回。");

        // 本条消息 @ 了谁
        if (ctx.otherAts != null && !ctx.otherAts.isEmpty()) {
            sb.append("\n\n【本条消息 @ 了以下用户】");
            for (Long atQq : ctx.otherAts) {
                sb.append("\n- QQ=").append(atQq);
            }
            sb.append("\n如果消息里有\"他\"\"她\"\"这个人\"\"这位\"等代词，指的就是上面被 @ 的用户。记别称时 target_user_id 填这个QQ。");
        }

        if (ctx.atMentionContext != null && !ctx.atMentionContext.isBlank()) {
            sb.append("\n\n").append(ctx.atMentionContext);
        }

        // 游戏状态
        if (ctx.spyGameDesc != null && !ctx.spyGameDesc.isEmpty()) sb.append(ctx.spyGameDesc);
        if (ctx.numberGameDesc != null && !ctx.numberGameDesc.isEmpty()) sb.append(ctx.numberGameDesc);

        // 群公共上下文
        if (ctx.publicGroupContext != null && !ctx.publicGroupContext.isEmpty()) {
            sb.append(ctx.publicGroupContext);
        }

        // 时间已移到 build() 开头 —— flash 模型长 context 末段注意衰减，不能放这里
        // （否则会被【你被 @ 了】段吞掉，LLM 读到也当 @ 规则的补充忽略）

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

        // 你的表情包库概要 —— 只显示真图（legacy face fallback 不算表情包，不显示给 LLM）
        try {
            com.start.service.StickerIngestService svc = com.start.service.StickerIngestService.getInstance();
            java.util.List<com.start.service.StickerIngestService.StickerRecord> all = svc.getAllStickers();
            java.util.List<com.start.service.StickerIngestService.StickerRecord> real = new java.util.ArrayList<>();
            java.util.Set<String> allKeywords = new java.util.LinkedHashSet<>();
            for (com.start.service.StickerIngestService.StickerRecord r : all) {
                if (r.file != null && !r.file.isBlank()) {
                    real.add(r);
                    if (r.keywords != null) allKeywords.addAll(r.keywords);
                }
            }
            sb.append("\n\n【你的表情包库】共 ").append(real.size()).append(" 张真图");
            if (!allKeywords.isEmpty()) {
                sb.append("\n可用关键词：").append(String.join("、", allKeywords.stream().limit(30).toList()));
            }
            if (real.isEmpty()) {
                sb.append("\n库还是空的，群里发图会自动积累。调用 send_sticker 时如果库空，会自动回退到 QQ 内置 face（[CQ:face,id=14] 等）。");
            } else {
                sb.append("\n调用 send_sticker 工具发图（选情绪关键词）；想直接发 QQ face 用 [CQ:face,id=14]。");
            }
        } catch (Exception e) {
            // ignore — StickerIngestService 未初始化时跳过
        }

        // 群聊场景（Thread 列表 → 群氛围叙事）— 最外层，群级上下文
        if (ctx.sceneState != null && !ctx.sceneState.isEmpty()) {
            sb.append(buildSceneNarrative(ctx.sceneState));
        }

        // 工作记忆（WorkingMemory → 当前 Thread 任务叙事）— 中间层，线程级
        if (ctx.workingMemory != null) {
            sb.append(buildWorkingMemoryNarrative(ctx.workingMemory));
        }

        // 会话认知（BeliefRecall 列表 → 叙事语言）— 最内层，个人级
        if (ctx.beliefRecalls != null && !ctx.beliefRecalls.isEmpty()) {
            sb.append(buildBeliefNarrative(ctx.beliefRecalls));
        }

        // Keep per-turn perspective last so older scene/memory cannot override who is speaking to whom.
        if (ctx.dialoguePlan != null) {
            sb.append(ctx.dialoguePlan.toPromptNarrative());
        }

        return sb.toString();
    }

    /** 将 BeliefRecall 列表翻译为自然语言叙事，注入当前对话认知。 */
    private String buildBeliefNarrative(List<BeliefRecall> recalls) {
        StringBuilder sb = new StringBuilder("\n\n【当前对话状态】");
        for (BeliefRecall r : recalls) {
            if (r.topic() == null || r.topic().isBlank()) continue;
            sb.append("\n• 当前话题：").append(r.topic());
            if (r.userEmotion() != null && !r.userEmotion().isBlank()
                    && !"未知".equals(r.userEmotion())) {
                sb.append("，她").append(r.userEmotion());
            }
            if (r.botIntent() != null && !r.botIntent().isBlank()) {
                sb.append("，你刚才在").append(r.botIntent()).append("她");
            }
            if (r.unresolvedQuestion() != null && !r.unresolvedQuestion().isBlank()) {
                sb.append("，还没解决的问题：").append(r.unresolvedQuestion());
            }
            if (r.ageMinutes() >= 60 * 24) {
                sb.append("（").append(r.ageMinutes() / (60 * 24)).append("天前）");
            } else if (r.ageMinutes() >= 60) {
                sb.append("（").append(r.ageMinutes() / 60).append("小时前）");
            } else if (r.ageMinutes() > 5) {
                sb.append("（").append(r.ageMinutes()).append("分钟前）");
            }
        }
        sb.append("\n如果当前消息表明话题已转换或情绪已变化，以当前消息为准，不要强行维持旧认知。");
        return sb.toString();
    }

    /** 将 SceneState 翻译为群聊场景叙事，帮助 LLM 感知群内的多线程话题结构。 */
    private String buildSceneNarrative(SceneState scene) {
        StringBuilder sb = new StringBuilder("\n\n【群聊场景】");
        sb.append("\n活跃话题：").append(scene.atmosphere());
        if (scene.totalActiveThreads() >= 3) {
            sb.append("\n群气氛：热闹，多话题并行，注意不要串台");
        } else if (scene.totalActiveThreads() == 2) {
            sb.append("\n群气氛：两个话题并行，注意区分回复对象");
        }
        if (scene.focusedThreadTopic() != null) {
            sb.append("\n当前最热话题：").append(scene.focusedThreadTopic());
        }
        sb.append("\n如果有多个话题在并行讨论，请只参与你感兴趣的那个，不用每条都跟。");
        return sb.toString();
    }

    /** 将 WorkingMemory 翻译为当前任务叙事，帮助 LLM 记住"我在这个 Thread 里正在做什么"。 */
    private String buildWorkingMemoryNarrative(WorkingMemory wm) {
        StringBuilder sb = new StringBuilder("\n\n【当前任务】");
        if (wm.getGoal() != null && !wm.getGoal().isBlank()) {
            sb.append("\n• 目标：").append(wm.getGoal());
        }
        if (wm.getContextSummary() != null && !wm.getContextSummary().isBlank()) {
            sb.append("\n• 已讨论：").append(wm.getContextSummary());
        }
        if (wm.getPendingAction() != null && !wm.getPendingAction().isBlank()) {
            sb.append("\n• 等待：").append(wm.getPendingAction());
        }
        if (wm.getAttentionTarget() != null && !wm.getAttentionTarget().isBlank()) {
            sb.append("\n• 关注：").append(wm.getAttentionTarget());
        }
        if (wm.getConstraints() != null && !wm.getConstraints().isBlank()) {
            sb.append("\n• 注意：").append(wm.getConstraints());
        }
        if (wm.getUserEmotions() != null && !wm.getUserEmotions().isBlank()) {
            // user_emotions 是 JSON 格式 {"qq号":"情绪",...}，直接展示给 LLM 理解
            sb.append("\n• 用户情绪：").append(wm.getUserEmotions());
        }
        sb.append("\n如果当前消息表明任务已完成或话题已切换，可以调用 set_working_memory 更新状态。");
        return sb.toString();
    }
}

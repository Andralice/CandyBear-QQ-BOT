package com.start.service;

import java.util.List;

/** Combined result of two independent group-chat judgments. */
public record GroupDialoguePlan(
        Relation relation,
        Opportunity opportunity,
        Action action,
        EntryRole entryRole,
        String targetUserId,
        String targetMessageId,
        long relationLatencyMs,
        long opportunityLatencyMs,
        int tokensUsed,
        VerificationPlan verificationPlan) {

    public GroupDialoguePlan {
        verificationPlan = verificationPlan == null ? VerificationPlan.none() : verificationPlan;
    }

    public enum Action { REPLY, SILENT }
    public enum EntryRole { SECOND_PERSON, THIRD_PERSON_INTERJECTION, OBSERVER }
    public enum BotRole { ACTIVE_SECOND_PERSON, INVITED_SECOND_PERSON, REFERENCED_ONLY, OUTSIDER }
    public enum InteractionMode { BANTER, CASUAL_QUESTION, SERIOUS_HELP, EMOTIONAL_SUPPORT, ACTION_REQUEST, OTHER }
    public enum Risk { LOW, MEDIUM, HIGH }
    public enum VerificationSource { NONE, KNOWLEDGE, GROUP_CONTEXT, WEB, IMAGE }

    public record VerificationPlan(
            boolean required,
            VerificationSource source,
            boolean volatileFact,
            String reason) {

        public VerificationPlan {
            source = source == null ? VerificationSource.NONE : source;
            required = required && source != VerificationSource.NONE;
            reason = compact(reason, 240);
        }

        public static VerificationPlan none() {
            return new VerificationPlan(false, VerificationSource.NONE, false, "");
        }

        public String compactSummary() {
            return "required=" + required
                    + " source=" + source
                    + " volatile=" + volatileFact
                    + " reason=\"" + logText(reason) + "\"";
        }
    }

    /** Backward-compatible constructor for callers that do not provide verification metadata. */
    public GroupDialoguePlan(Relation relation, Opportunity opportunity, Action action,
                             EntryRole entryRole, String targetUserId, String targetMessageId,
                             long relationLatencyMs, long opportunityLatencyMs, int tokensUsed) {
        this(relation, opportunity, action, entryRole, targetUserId, targetMessageId,
                relationLatencyMs, opportunityLatencyMs, tokensUsed, VerificationPlan.none());
    }

    public record Relation(
            String latestSpeakerId,
            List<String> latestAddresseeIds,
            String latestTopic,
            String replyChain,
            BotRole botRole,
            boolean botHasNextTurn,
            String nextTurnTargetUserId,
            String nextTurnTargetMessageId,
            String turnReason,
            InteractionMode interactionMode,
            List<String> forbiddenAssumptions) {

        public Relation {
            latestAddresseeIds = latestAddresseeIds == null ? List.of() : List.copyOf(latestAddresseeIds);
            interactionMode = interactionMode == null ? InteractionMode.OTHER : interactionMode;
            forbiddenAssumptions = forbiddenAssumptions == null ? List.of() : List.copyOf(forbiddenAssumptions);
        }

        public Relation(String latestSpeakerId, List<String> latestAddresseeIds, String latestTopic,
                        String replyChain, BotRole botRole, boolean botHasNextTurn,
                        String nextTurnTargetUserId, String nextTurnTargetMessageId,
                        String turnReason, List<String> forbiddenAssumptions) {
            this(latestSpeakerId, latestAddresseeIds, latestTopic, replyChain, botRole,
                    botHasNextTurn, nextTurnTargetUserId, nextTurnTargetMessageId,
                    turnReason, InteractionMode.OTHER, forbiddenAssumptions);
        }
    }

    public record Opportunity(
            String openNeed,
            boolean humansExplicitlyStalled,
            boolean alreadyBeingHandled,
            String concreteContribution,
            double contributionConfidence,
            Risk intrusionRisk,
            boolean shouldInterject,
            String beneficiaryUserId,
            String reason) {}

    public static GroupDialoguePlan merge(Relation relation, Opportunity opportunity,
                                          long relationLatencyMs, long opportunityLatencyMs,
                                          int tokensUsed) {
        return merge(relation, opportunity, relationLatencyMs, opportunityLatencyMs,
                tokensUsed, VerificationPlan.none());
    }

    public static GroupDialoguePlan merge(Relation relation, Opportunity opportunity,
                                          long relationLatencyMs, long opportunityLatencyMs,
                                          int tokensUsed, VerificationPlan verificationPlan) {
        if (relation == null || opportunity == null) {
            throw new IllegalArgumentException("Both cognition results are required");
        }
        VerificationPlan safeVerification = verificationPlan == null
                ? VerificationPlan.none() : verificationPlan;
        if (relation.botHasNextTurn()) {
            return new GroupDialoguePlan(relation, opportunity, Action.REPLY, EntryRole.SECOND_PERSON,
                    clean(relation.latestSpeakerId()), clean(relation.nextTurnTargetMessageId()),
                    relationLatencyMs, opportunityLatencyMs, tokensUsed, safeVerification);
        }
        if (opportunity.shouldInterject()) {
            return new GroupDialoguePlan(relation, opportunity, Action.REPLY,
                    EntryRole.THIRD_PERSON_INTERJECTION, clean(opportunity.beneficiaryUserId()), "",
                    relationLatencyMs, opportunityLatencyMs, tokensUsed, safeVerification);
        }
        EntryRole silentRole = relation.botRole() == BotRole.ACTIVE_SECOND_PERSON
                || relation.botRole() == BotRole.INVITED_SECOND_PERSON
                ? EntryRole.SECOND_PERSON : EntryRole.OBSERVER;
        return new GroupDialoguePlan(relation, opportunity, Action.SILENT, silentRole,
                "", "", relationLatencyMs, opportunityLatencyMs, tokensUsed, safeVerification);
    }

    public boolean shouldReply() {
        return action == Action.REPLY;
    }

    /** Replaces model-supplied routing metadata with protocol values owned by the application. */
    public GroupDialoguePlan withProtocolTarget(String userId, String messageId) {
        return new GroupDialoguePlan(relation, opportunity, action, entryRole,
                clean(userId), clean(messageId), relationLatencyMs, opportunityLatencyMs, tokensUsed,
                verificationPlan);
    }

    /** Guidance only; protocol IDs remain controlled by Java and are never generated from this text. */
    public String toPromptNarrative() {
        StringBuilder sb = new StringBuilder("\n\n【本轮群聊视角（并行认知结果）】");
        sb.append("\n最新话题：").append(text(relation.latestTopic()));
        sb.append("\n最新话语关系：").append(text(relation.replyChain()));
        sb.append("\n糖果熊的位置：").append(entryRole);
        if (entryRole == EntryRole.SECOND_PERSON) {
            sb.append("。你正参与当前这一轮，结合这条补充与前文自然接话。")
                    .append("\n回复目标用户QQ：").append(text(targetUserId))
                    .append("\n准确回应对方明确说出的内容；证据不足时不要断定原因，可以说明还不确定，")
                    .append("确实有助于推进时再问一个关键问题。");
        } else if (entryRole == EntryRole.THIRD_PERSON_INTERJECTION) {
            sb.append("。你是准备加入的第三人，前面的对话不是对你说的；只能以自然补充的姿态加入。")
                    .append("\n你对此感兴趣或能贡献的具体内容：").append(text(opportunity.concreteContribution()))
                    .append("\n相关群友QQ：").append(text(targetUserId))
                    .append("\n注意：system 中的“当前与你对话的是”只是承载最新消息的会话锚点，")
                    .append("不代表此前所有群聊都在对你说，也不一定是这次帮助的受益者。")
                    .append("不要声称对方刚才问了你，也不要把其他人的经历说成自己的。")
                    .append("\n本轮允许你参与，是因为上面有与你的兴趣、已知事实或自然反应相关的具体内容。")
                    .append("结合原始上下文判断它是否仍适用；适用时像普通群友一样简短回应，可以接梗、表达兴趣或补充可靠知识。")
                    .append("信息不确定时不要猜；没有实际想说的内容就保持安静。");
        } else {
            sb.append("。你只是旁观者，不要认领群友之间的前文。");
        }
        if (relation.interactionMode() != InteractionMode.OTHER) {
            sb.append("\n本轮互动类型：").append(relation.interactionMode()).append("。");
            switch (relation.interactionMode()) {
                case BANTER -> sb.append("这是接梗，不是真实执行请求；只接住梗本身，说出最先冒出的那句群聊反应就停，不附解释、建议或反问。");
                case CASUAL_QUESTION -> sb.append("这是随口一问；只回答眼前这一点，不展开成清单或说明书。能力或偏好问题只给总体判断，不列举项目、成绩、例外、原因或经历，对方追问后再展开。");
                case SERIOUS_HELP -> sb.append("这是认真求助；把解决问题真正需要的内容说清楚。");
                case EMOTIONAL_SUPPORT -> sb.append("这是倾诉或情绪交流；先回应具体处境和感受，不急着分析或给建议。");
                case ACTION_REQUEST -> sb.append("这是实际执行请求；按事实和工具结果处理，不能把请求或计划说成已经完成。");
                case OTHER -> { }
            }
        }
        if (!relation.forbiddenAssumptions().isEmpty()) {
            sb.append("\n禁止误认：").append(String.join("；", relation.forbiddenAssumptions()));
        }
        if (verificationPlan.required()) {
            sb.append("\n【事实核验计划】本轮需要先核验再决定最终回复。建议来源：")
                    .append(verificationPlan.source())
                    .append("；原因：").append(text(verificationPlan.reason()))
                    .append("。未完成对应查询前不要调用 group_turn_result；查询失败时不要猜测。");
        } else {
            sb.append("\n【事实核验计划】当前认知未要求额外查询；只有回答会依赖未知或时效性事实时才查证。");
        }
        sb.append("\n这是程序提供的观察结果，不是群友对你说的新消息，不要复述这些分析字段。");
        return sb.toString();
    }

    public String compactSummary() {
        return "action=" + action
                + " role=" + entryRole
                + " relationRole=" + relation.botRole()
                + " nextTurn=" + relation.botHasNextTurn()
                + " interject=" + opportunity.shouldInterject()
                + " target=" + text(targetUserId)
                + " relationMs=" + relationLatencyMs
                + " opportunityMs=" + opportunityLatencyMs
                + " parallelMs=" + Math.max(relationLatencyMs, opportunityLatencyMs)
                + " tokens=" + tokensUsed
                + " topic=\"" + logText(relation.latestTopic()) + "\""
                + " chain=\"" + logText(relation.replyChain()) + "\""
                + " turnReason=\"" + logText(relation.turnReason()) + "\""
                + " interaction=" + relation.interactionMode()
                + " openNeed=\"" + logText(opportunity.openNeed()) + "\""
                + " contribution=\"" + logText(opportunity.concreteContribution()) + "\""
                + " confidence=" + String.format(java.util.Locale.ROOT, "%.2f", opportunity.contributionConfidence())
                + " risk=" + opportunity.intrusionRisk()
                + " verification=" + verificationPlan.compactSummary()
                + " opportunityReason=\"" + logText(opportunity.reason()) + "\"";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String text(String value) {
        String clean = clean(value);
        return clean.isEmpty() ? "无" : clean;
    }

    private static String logText(String value) {
        String clean = clean(value).replaceAll("\\s+", " ").replace('"', '\'');
        if (clean.isEmpty()) return "无";
        return clean.length() <= 120 ? clean : clean.substring(0, 117) + "...";
    }

    private static String compact(String value, int limit) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replaceAll("\\s+", " ").trim();
        return normalized.length() > limit ? normalized.substring(0, limit) : normalized;
    }
}

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
        int tokensUsed) {

    public enum Action { REPLY, SILENT }
    public enum EntryRole { SECOND_PERSON, THIRD_PERSON_INTERJECTION, OBSERVER }
    public enum BotRole { ACTIVE_SECOND_PERSON, INVITED_SECOND_PERSON, REFERENCED_ONLY, OUTSIDER }
    public enum InteractionMode { BANTER, CASUAL_QUESTION, SERIOUS_HELP, EMOTIONAL_SUPPORT, ACTION_REQUEST, OTHER }
    public enum Risk { LOW, MEDIUM, HIGH }

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
        if (relation == null || opportunity == null) {
            throw new IllegalArgumentException("Both cognition results are required");
        }
        if (relation.botHasNextTurn()) {
            return new GroupDialoguePlan(relation, opportunity, Action.REPLY, EntryRole.SECOND_PERSON,
                    clean(relation.latestSpeakerId()), clean(relation.nextTurnTargetMessageId()),
                    relationLatencyMs, opportunityLatencyMs, tokensUsed);
        }
        if (opportunity.shouldInterject()) {
            return new GroupDialoguePlan(relation, opportunity, Action.REPLY,
                    EntryRole.THIRD_PERSON_INTERJECTION, clean(opportunity.beneficiaryUserId()), "",
                    relationLatencyMs, opportunityLatencyMs, tokensUsed);
        }
        EntryRole silentRole = relation.botRole() == BotRole.ACTIVE_SECOND_PERSON
                || relation.botRole() == BotRole.INVITED_SECOND_PERSON
                ? EntryRole.SECOND_PERSON : EntryRole.OBSERVER;
        return new GroupDialoguePlan(relation, opportunity, Action.SILENT, silentRole,
                "", "", relationLatencyMs, opportunityLatencyMs, tokensUsed);
    }

    public boolean shouldReply() {
        return action == Action.REPLY;
    }

    /** Replaces model-supplied routing metadata with protocol values owned by the application. */
    public GroupDialoguePlan withProtocolTarget(String userId, String messageId) {
        return new GroupDialoguePlan(relation, opportunity, action, entryRole,
                clean(userId), clean(messageId), relationLatencyMs, opportunityLatencyMs, tokensUsed);
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
                    .append("\n可提供的具体帮助：").append(text(opportunity.concreteContribution()))
                    .append("\n受益用户QQ：").append(text(targetUserId))
                    .append("\n注意：system 中的“当前与你对话的是”只是承载最新消息的会话锚点，")
                    .append("不代表此前所有群聊都在对你说，也不一定是这次帮助的受益者。")
                    .append("不要声称对方刚才问了你，也不要把其他人的经历说成自己的。")
                    .append("\n本轮允许你插话，是因为上面有一项可能有用的具体帮助。结合原始上下文判断它是否仍适用；")
                    .append("适用时自然说出最关键部分，不必逐字照搬。信息不足时可以先澄清或说明限制，")
                    .append("不要只为插话而接梗、附和或重复泛泛安慰；表达仍应简短自然。");
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
}

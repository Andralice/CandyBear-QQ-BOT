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
            List<String> forbiddenAssumptions) {

        public Relation {
            latestAddresseeIds = latestAddresseeIds == null ? List.of() : List.copyOf(latestAddresseeIds);
            forbiddenAssumptions = forbiddenAssumptions == null ? List.of() : List.copyOf(forbiddenAssumptions);
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
            sb.append("。你是正在对话的第二人，应承接对方这一轮，不要假装刚加入。")
                    .append("\n回复目标用户QQ：").append(text(targetUserId))
                    .append("\n准确回应对方最新补充；证据不足时继续问一个最相关的问题，")
                    .append("不要仅凭一句现象就擅自断定原因。");
        } else if (entryRole == EntryRole.THIRD_PERSON_INTERJECTION) {
            sb.append("。你是准备加入的第三人，前面的对话不是对你说的；只能以自然补充的姿态加入。")
                    .append("\n可提供的具体帮助：").append(text(opportunity.concreteContribution()))
                    .append("\n受益用户QQ：").append(text(targetUserId))
                    .append("\n注意：system 中的“当前与你对话的是”只是承载最新消息的会话锚点，")
                    .append("不代表此前所有群聊都在对你说，也不一定是这次帮助的受益者。")
                    .append("不要声称对方刚才问了你，也不要把其他人的经历说成自己的。")
                    .append("\n本轮之所以允许你插话，只因为上面的具体帮助。你必须在回复中直接给出这项帮助；")
                    .append("不能只接梗、附和、说自己也不会或泛泛安慰。此规则高于一般的‘优先接梗/不主动科普’风格规则，")
                    .append("但表达仍应简短自然。");
        } else {
            sb.append("。你只是旁观者，不要认领群友之间的前文。");
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

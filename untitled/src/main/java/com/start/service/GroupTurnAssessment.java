package com.start.service;

import java.util.List;

/** One group-model response's reply decision and short-lived scene assessment. */
public record GroupTurnAssessment(
        Decision decision,
        List<String> messages,
        String topic,
        String interest,
        String openNeed,
        boolean keepScene,
        String reason) {

    public enum Decision { REPLY, SILENT }

    public GroupTurnAssessment {
        decision = decision == null ? Decision.SILENT : decision;
        messages = messages == null ? List.of() : messages.stream()
                .filter(message -> message != null && !message.isBlank())
                .map(String::trim)
                .toList();
        topic = compact(topic, 180);
        interest = compact(interest, 240);
        openNeed = compact(openNeed, 240);
        reason = compact(reason, 240);
        keepScene = keepScene && !topic.isBlank();
    }

    public GroupTurnAssessment withMessages(List<String> replacement) {
        return new GroupTurnAssessment(decision, replacement, topic, interest, openNeed, keepScene, reason);
    }

    private static String compact(String value, int limit) {
        if (value == null) return "";
        String normalized = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replaceAll("\\s+", " ").trim();
        return normalized.length() > limit ? normalized.substring(0, limit) : normalized;
    }
}

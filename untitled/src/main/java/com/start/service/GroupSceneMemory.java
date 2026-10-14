package com.start.service;

import java.util.concurrent.ConcurrentHashMap;

/** Process-local group scene hints. They expire quickly and never replace persisted evidence. */
public final class GroupSceneMemory {
    public static final long TTL_MS = 10 * 60 * 1000L;

    private final ConcurrentHashMap<String, Snapshot> scenes = new ConcurrentHashMap<>();

    public Snapshot get(String groupId, long nowMs) {
        if (groupId == null || groupId.isBlank()) return null;
        Snapshot snapshot = scenes.get(groupId);
        if (snapshot == null) return null;
        long age = Math.max(0, nowMs - snapshot.updatedAtMs());
        if (age >= TTL_MS) {
            scenes.remove(groupId, snapshot);
            return null;
        }
        return snapshot;
    }

    public void update(String groupId, GroupTurnAssessment assessment, long assessedAtMs) {
        if (groupId == null || groupId.isBlank()) return;
        if (assessment == null || !assessment.keepScene()) {
            scenes.computeIfPresent(groupId, (ignored, current) ->
                    current.updatedAtMs() > assessedAtMs ? current : null);
            return;
        }
        Snapshot next = new Snapshot(assessment.topic(), assessment.interest(),
                assessment.openNeed(), assessedAtMs);
        scenes.compute(groupId, (ignored, current) ->
                current != null && current.updatedAtMs() > assessedAtMs ? current : next);
    }

    public record Snapshot(String topic, String interest, String openNeed, long updatedAtMs) {
        public String toPromptText(long nowMs) {
            long ageSeconds = Math.max(0, nowMs - updatedAtMs) / 1000;
            StringBuilder text = new StringBuilder("【上轮短期群聊判断，约 ")
                    .append(ageSeconds).append(" 秒前】\n话题：").append(topic);
            if (interest != null && !interest.isBlank()) text.append("\n糖果熊可能感兴趣的切入点：").append(interest);
            if (openNeed != null && !openNeed.isBlank()) text.append("\n未解决的信息缺口：").append(openNeed);
            text.append("\n这是上轮推断，不是事实。请按当前可见消息重新核对；若话题已变、问题已解决或场景不再相关，就忽略并清除它。超过十分钟的场景不会注入。");
            return text.toString();
        }
    }
}

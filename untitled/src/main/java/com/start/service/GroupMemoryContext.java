package com.start.service;

import com.start.memory.MemoryEvidence;
import com.start.memory.SemanticSimilarity;
import com.start.model.LongTermMemory;
import com.start.repository.LongTermMemoryRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** Builds a small, evidence-backed same-group memory context for ordinary replies. */
public final class GroupMemoryContext {
    private static final int MAX_CUES = 3;
    private static final double MIN_SIMILARITY = 0.08;

    private GroupMemoryContext() {}

    public static String retrieve(LongTermMemoryRepository repository, String groupId,
                                  String currentMessage, List<String> keywords) {
        if (repository == null || groupId == null || groupId.isBlank()
                || currentMessage == null || currentMessage.isBlank()
                || keywords == null || keywords.isEmpty()) return "";
        try {
            Map<Long, LongTermMemory> candidates = new LinkedHashMap<>();
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank()) continue;
                for (LongTermMemory cue : repository.searchGroundedByGroup(groupId, keyword, 10)) {
                    if (cue.getId() != null) candidates.putIfAbsent(cue.getId(), cue);
                }
            }
            LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            List<LongTermMemory> sourceBacked = new ArrayList<>();
            for (LongTermMemory cue : candidates.values()) {
                if (sourceBacked.size() >= MAX_CUES) break;
                try {
                    if (cue.getSourceMessageId() == null) continue;
                    var source = repository.findSource(cue.getSourceMessageId(), cue.getUserId(), groupId, cutoff);
                    if (source.isEmpty() || SemanticSimilarity.score(currentMessage, null,
                            source.get().text(), null) < MIN_SIMILARITY) continue;
                    sourceBacked.add(cue);
                } catch (Exception ignored) {
                    // An unavailable source cannot justify automatic recall.
                }
            }
            if (sourceBacked.isEmpty()) return "";
            return MemoryEvidence.render(repository, sourceBacked, cutoff, false);
        } catch (Exception ignored) {
            return "";
        }
    }
}

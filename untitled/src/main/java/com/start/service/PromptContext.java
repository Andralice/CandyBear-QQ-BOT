package com.start.service;

import com.start.repository.UserAliasRepository;
import java.util.List;
import java.util.Map;

/**
 * 组装系统提示词所需的所有上下文数据。由 generate() 收集，交给 PromptBuilder 组装。
 */
public class PromptContext {
    String nickname;
    String userId;
    String groupId;
    boolean isGuier;
    String userProfileText;
    int affinityScore;
    String knowledgeContext;
    String moodDescription;
    Map<String, UserAliasRepository.AliasInfo> aliasInfoMap;
    String userLocation;
    boolean isAtBot;
    List<Long> otherAts;
    String spyGameDesc;
    String numberGameDesc;
    String publicGroupContext;
    String timeContext;
    String metricsHint;
    boolean allowSilence;
    String pendingFilesHint;
    String memoryRecallContext;
    String promptPatch;
    List<Long> atUserIds;
    long botQq;

    public PromptContext() {}

    // ---- setters ----

    public PromptContext nickname(String v) { this.nickname = v; return this; }
    public PromptContext userId(String v) { this.userId = v; return this; }
    public PromptContext groupId(String v) { this.groupId = v; return this; }
    public PromptContext isGuier(boolean v) { this.isGuier = v; return this; }
    public PromptContext userProfileText(String v) { this.userProfileText = v; return this; }
    public PromptContext affinityScore(int v) { this.affinityScore = v; return this; }
    public PromptContext knowledgeContext(String v) { this.knowledgeContext = v; return this; }
    public PromptContext moodDescription(String v) { this.moodDescription = v; return this; }
    public PromptContext aliasInfoMap(Map<String, UserAliasRepository.AliasInfo> v) { this.aliasInfoMap = v; return this; }
    public PromptContext userLocation(String v) { this.userLocation = v; return this; }
    public PromptContext isAtBot(boolean v) { this.isAtBot = v; return this; }
    public PromptContext otherAts(List<Long> v) { this.otherAts = v; return this; }
    public PromptContext spyGameDesc(String v) { this.spyGameDesc = v; return this; }
    public PromptContext numberGameDesc(String v) { this.numberGameDesc = v; return this; }
    public PromptContext publicGroupContext(String v) { this.publicGroupContext = v; return this; }
    public PromptContext timeContext(String v) { this.timeContext = v; return this; }
    public PromptContext metricsHint(String v) { this.metricsHint = v; return this; }
    public PromptContext allowSilence(boolean v) { this.allowSilence = v; return this; }
    public PromptContext pendingFilesHint(String v) { this.pendingFilesHint = v; return this; }
    public PromptContext memoryRecallContext(String v) { this.memoryRecallContext = v; return this; }
    public PromptContext promptPatch(String v) { this.promptPatch = v; return this; }
    public PromptContext atUserIds(List<Long> v) { this.atUserIds = v; return this; }
    public PromptContext botQq(long v) { this.botQq = v; return this; }
}

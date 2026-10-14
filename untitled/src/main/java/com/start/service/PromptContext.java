package com.start.service;

import com.start.memory.BeliefRecall;
import com.start.model.WorkingMemory;
import com.start.repository.UserAliasRepository;
import com.start.thread.SceneState;
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
    boolean addressedToBotByName;
    List<Long> otherAts;
    String atMentionContext;
    String spyGameDesc;
    String numberGameDesc;
    String publicGroupContext;
    /** 程序从本轮群消息提取的指向证据，包含发送者、@目标和回复链。 */
    String turnEvidence;
    String timeContext;
    String metricsHint;
    boolean allowSilence;
    String pendingFilesHint;
    List<BeliefRecall> beliefRecalls;
    SceneState sceneState;
    WorkingMemory workingMemory;
    GroupDialoguePlan dialoguePlan;
    String promptPatch;
    String shortTermGroupScene;
    List<Long> atUserIds;
    long botQq;
    /** 群成员 QQ → 显示名（@ 时直接拿 userId）。来自 OneBotWsService.getGroupMemberDisplayNamesAsync。 */
    Map<String, String> groupMembers;

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
    public PromptContext addressedToBotByName(boolean v) { this.addressedToBotByName = v; return this; }
    public PromptContext otherAts(List<Long> v) { this.otherAts = v; return this; }
    public PromptContext atMentionContext(String v) { this.atMentionContext = v; return this; }
    public PromptContext spyGameDesc(String v) { this.spyGameDesc = v; return this; }
    public PromptContext numberGameDesc(String v) { this.numberGameDesc = v; return this; }
    public PromptContext publicGroupContext(String v) { this.publicGroupContext = v; return this; }
    public PromptContext turnEvidence(String v) { this.turnEvidence = v; return this; }
    public PromptContext timeContext(String v) { this.timeContext = v; return this; }
    public PromptContext metricsHint(String v) { this.metricsHint = v; return this; }
    public PromptContext allowSilence(boolean v) { this.allowSilence = v; return this; }
    public PromptContext pendingFilesHint(String v) { this.pendingFilesHint = v; return this; }
    public PromptContext beliefRecalls(List<BeliefRecall> v) { this.beliefRecalls = v; return this; }
    public PromptContext sceneState(SceneState v) { this.sceneState = v; return this; }
    public PromptContext workingMemory(WorkingMemory v) { this.workingMemory = v; return this; }
    public PromptContext dialoguePlan(GroupDialoguePlan v) { this.dialoguePlan = v; return this; }
    public PromptContext promptPatch(String v) { this.promptPatch = v; return this; }
    public PromptContext shortTermGroupScene(String v) { this.shortTermGroupScene = v; return this; }
    public PromptContext atUserIds(List<Long> v) { this.atUserIds = v; return this; }
    public PromptContext botQq(long v) { this.botQq = v; return this; }
    public PromptContext groupMembers(Map<String, String> v) { this.groupMembers = v; return this; }
}

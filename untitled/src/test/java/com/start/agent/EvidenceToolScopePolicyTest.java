package com.start.agent;

import com.start.agent.social.UserProfileTool;
import com.start.repository.LongTermMemoryRepository;
import com.start.repository.MessageRepository;
import com.start.repository.ProfileFactRepository;
import com.start.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceToolScopePolicyTest {

    @Test
    void historyRejectsModelSuppliedCrossGroupIdBeforeDatabaseAccess() {
        SearchHistoryTool tool = new SearchHistoryTool(new LongTermMemoryRepository(null), "10001");

        String result = tool.execute(Map.of("group_id", "99999"));

        assertTrue(result.contains("拒绝跨群查询"));
    }

    @Test
    void imageHistoryCannotFallBackToAllGroups() {
        QueryImagesTool tool = new QueryImagesTool(new MessageRepository(), null);

        String result = tool.execute(Map.of("keyword", "图片"));

        assertTrue(result.contains("不能查询群图片"));
    }

    @Test
    void profileRejectsModelSuppliedCrossGroupIdBeforeDatabaseAccess() {
        UserProfileTool tool = new UserProfileTool(
                new UserProfileRepository(null), new ProfileFactRepository(null),
                new MessageRepository(), "20002", "10001");

        String result = tool.execute(Map.of("user_id", "20002", "group_id", "99999"));

        assertTrue(result.contains("拒绝跨群查询"));
    }

    @Test
    void profileInPrivateChatCannotReadAnotherUserEvenForBotAdmin() {
        UserProfileTool tool = new UserProfileTool(
                new UserProfileRepository(null), new ProfileFactRepository(null),
                new MessageRepository(), "3524398813", null);

        Map<String, Object> args = new HashMap<>();
        args.put("user_id", "20002");
        args.put("group_id", null);
        String result = tool.execute(args);

        assertTrue(result.contains("私聊中只能查询自己的个人画像"));
    }
}

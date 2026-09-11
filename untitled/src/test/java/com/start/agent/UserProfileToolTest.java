package com.start.agent;

import com.start.agent.social.UserProfileTool;
import com.start.model.ProfileFactSummary;
import com.start.repository.MessageRepository;
import com.start.repository.ProfileFactRepository;
import com.start.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserProfileToolTest {

    @Test
    void legacyFreeTextProfileIsNotUsedWhenStructuredFactsAreMissing() {
        UserProfileTool tool = new UserProfileTool(
                new UserProfileRepository(null), new EmptyFacts(), new MessageRepository(), "10001", null);
        Map<String, Object> args = new HashMap<>();
        args.put("user_id", "10001");
        args.put("group_id", null);

        assertEquals("暂无用户 10001 的可用结构化个人画像事实。", tool.execute(args));
    }

    private static final class EmptyFacts extends ProfileFactRepository {
        private EmptyFacts() {
            super(null);
        }

        @Override
        public List<ProfileFactSummary> findActiveByUserId(String userId, boolean privateContext, int limit) {
            return List.of();
        }
    }
}

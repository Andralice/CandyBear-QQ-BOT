package com.start.agent;

import com.start.agent.social.DeleteMyProfileTool;
import com.start.repository.ProfilePrivacyRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilePrivacyToolTest {
    @Test
    void prepareRequiresAnExplicitDeleteRequestFromTheCurrentUser() {
        DeleteMyProfileTool tool = new DeleteMyProfileTool(null, "10001", "请帮我看看今天的天气");

        String result = tool.execute(Map.of("action", "prepare"));

        assertTrue(result.contains("明确提出删除自己的个人画像请求"));
    }

    @Test
    void confirmRequiresTheCurrentMessageToContainTheCodeAndConfirmation() {
        DeleteMyProfileTool tool = new DeleteMyProfileTool(null, "10001", "我同意删除个人画像");

        String result = tool.execute(Map.of("action", "confirm", "confirmation_code", "ABCDEFGH"));

        assertTrue(result.contains("验证码"));
    }

    @Test
    void toolHasNoTargetUserParameter() {
        DeleteMyProfileTool tool = new DeleteMyProfileTool(null, "10001", "");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.getParameters().get("properties");

        assertTrue(!properties.containsKey("target_user_id"));
        assertTrue(tool.getDescription().contains("不能替其他用户删除"));
    }
}

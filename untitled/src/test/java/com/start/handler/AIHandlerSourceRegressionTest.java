package com.start.handler;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AIHandler 源码静态扫描（第二阶段 2.2 回归保护）。
 *
 * 不依赖 DB，纯源码 grep。防止后续修改意外回退到无 sessionId 的
 * sendGroupReply / sendReply 调用。
 */
class AIHandlerSourceRegressionTest {

    private static String readAIHandler() throws Exception {
        Path p = Paths.get("src/main/java/com/start/handler/AIHandler.java");
        return new String(Files.readAllBytes(p));
    }

    @Test
    void noLegacySendGroupReplyForGoShenShen() throws Exception {
        String src = readAIHandler();
        // 不应再有 bot.sendGroupReply(groupId, "刚刚走神了...") 无 sessionId 形式
        assertFalse(src.contains("sendGroupReply(groupId, \"刚刚走神了，再说一遍？\")"),
                "AIHandler 仍调用无 sessionId 的 sendGroupReply for fallback 刚刚走神了");
    }

    @Test
    void noLegacySendReplyForShaoDengYiXia() throws Exception {
        String src = readAIHandler();
        // 不应再有 bot.sendReply(originalMsg, "稍等一下...") 无 sessionId 形式
        assertFalse(src.contains("sendReply(originalMsg, \"稍等一下，我在走神...\")"),
                "AIHandler 仍调用无 sessionId 的 sendReply for fallback 稍等一下");
    }

    @Test
    void mentionOnlyMessageIsPassedToModel() throws Exception {
        String src = readAIHandler();
        assertFalse(src.contains("问点什么吧～"), "只 @ 时不应再走固定短句");
        assertTrue(src.contains("EMPTY_MENTION_PROMPT"), "只 @ 时应注入模型上下文提示");
        assertTrue(src.contains("!state.hasContent() && !state.isSubmitted()"),
                "已提交的空正文会话也必须允许进入生成流程");
    }

    @Test
    void allSendSplitGroupRepliesPassSessionId() throws Exception {
        String src = readAIHandler();
        // sendSplitGroupReplies 调用应至少 4 个参数（含 sessionId）
        // 检查所有出现位置
        int idx = 0;
        int unchecked = 0;
        while ((idx = src.indexOf("sendSplitGroupReplies(", idx)) != -1) {
            // 找匹配的右括号
            int end = findMatchingParen(src, idx);
            String call = src.substring(idx, end + 1);
            // 调用形式应为 sendSplitGroupReplies(bot, groupId, reply, sessionId)
            // 即包含 4 个逗号分隔的参数
            int commaCount = countTopLevelCommas(call);
            if (commaCount < 3) {
                unchecked++;
            }
            idx = end + 1;
        }
        assertFalse(unchecked > 0,
                "sendSplitGroupReplies 存在无 sessionId 的调用");
    }

    private static int findMatchingParen(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '(') depth++;
            else if (s.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return s.length() - 1;
    }

    private static int countTopLevelCommas(String s) {
        int count = 0;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 1) count++;
        }
        return count;
    }
}

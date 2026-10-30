package com.start.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CandyBearKnowledgeRetrievalPromptTest {

    @Test
    void defaultPromptRoutesUnknownTermsAndMemesToTheRightEvidenceSource() {
        String prompt = new PromptBuilder().buildRuleBook(
                RuleSetDefaults.defaults(), new PromptContext().nickname("测试用户").userId("10001"));

        assertTrue(prompt.contains("不认识某个会影响理解的名词、术语或梗"));
        assertTrue(prompt.contains("先查当前群消息/历史"));
        assertTrue(prompt.contains("公开概念和稳定知识先查 query_knowledge"));
        assertTrue(prompt.contains("再用 web_search"));
        assertTrue(prompt.contains("普通寒暄、已经看懂的接梗、主观感受和上下文足够的问题不搜索"));
        assertTrue(prompt.contains("不自动保存每次搜索结果"));
    }
}

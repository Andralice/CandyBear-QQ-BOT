package com.start.agent;
// src/main/java/agent/tools/KnowledgeBaseTool.java


import com.start.agent.Tool;
import com.start.service.KeywordKnowledgeService; // ← 替换为你的实际包路径

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

public class KnowledgeBaseTool implements Tool {

    private final KeywordKnowledgeService knowledgeService;

    public KnowledgeBaseTool(KeywordKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public String getName() {
        return "query_knowledge";
    }

    @Override
    public String getDescription() {
        return "查询内部知识库，回答关于居住地和兴趣的问题，例如：'美食'、'烤肉'、'音乐'、'追番'、'书籍'、'城市'";
    }

    // ✅ 新增：定义工具所需的参数结构（JSON Schema）
    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "keyword", Map.of(
                                "type", "string",
                                "description", "用户提问中的关键词，如 '美食'、'烤肉'"
                        )
                ),
                "required", Arrays.asList("keyword")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String keyword = (String) args.get("keyword");
        if (keyword == null || keyword.trim().isEmpty()) {
            return "错误：缺少关键词参数";
        }

        KeywordKnowledgeService.KnowledgeResult result = knowledgeService.query(keyword.trim());

        if (result != null &&
                result.answer != null &&
                !result.answer.trim().isEmpty() &&
                result.similarityScore >= 0.3) {

            return "[id=" + result.id + "] " + result.answer.trim();
        }

        return "未找到相关信息";
    }
}
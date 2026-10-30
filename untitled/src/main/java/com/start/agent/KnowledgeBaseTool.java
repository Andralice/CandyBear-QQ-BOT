package com.start.agent;

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
        return "查询已有知识库。遇到当前语境不能解释的名词、术语、网络梗，或需要核实稳定事实时先查这里；返回的 MATCH_ONLY 只是关键词线索，不代表来源、正确性或新鲜度，需要时再根据资料类型查群聊记录或联网搜索。";
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

        if (result != null && result.answer != null && !result.answer.trim().isEmpty()) {

            return "[match_quality=" + result.matchQuality
                    + ", evidence_status=" + result.evidenceStatus
                    + ", id=" + result.id
                    + ", source=" + result.source
                    + ", knowledge_updated_at=" + result.knowledgeUpdatedAt
                    + ", freshness=" + result.freshness
                    + ", limitations=" + result.limitations + "]\n"
                    + result.answer.trim();
        }

        return "[match_quality=NONE, evidence_status=NO_MATCH] 未找到相关信息";
    }
}

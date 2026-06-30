package com.start.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 规则集加载器。优先从 RuntimeConfigService 热重载，失败回退 Java 内置默认。
 *
 * 热重载方式：
 * - rule_book: 完整替换所有规则（JSON 数组 [{name, category, text}, ...]）
 * - rule_&lt;name&gt;: 覆盖单条规则文本（如 rule_persona、rule_behavior）
 */
public final class RuleSetLoader {

    private static final Logger logger = LoggerFactory.getLogger(RuleSetLoader.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private RuleSetLoader() {}

    /** 加载 RuleSet，优先从运行时配置读取 */
    public static RuleSet load(RuntimeConfigService runtimeConfig) {
        // 1. 尝试完整替换 rule_book（JSON 格式）
        String ruleBookJson = runtimeConfig.get("rule_book");
        if (ruleBookJson != null && !ruleBookJson.isBlank()) {
            try {
                RuleSet rs = parseRuleBook(ruleBookJson);
                if (rs != null && !rs.sections().isEmpty()) {
                    logger.info("RuleSet 已从 rule_book 加载，共 {} 条规则", rs.sections().size());
                    return rs;
                }
            } catch (Exception e) {
                logger.warn("解析 rule_book 失败: {}", e.getMessage());
            }
        }

        // 2. 从默认构建，应用逐条覆盖
        RuleSet defaults = RuleSetDefaults.defaults();
        RuleSet rs = new RuleSet();
        int overrides = 0;
        for (RuleSet.Section s : defaults.sections()) {
            String key = "rule_" + s.name();
            String overrideText = runtimeConfig.get(key);
            if (overrideText != null && !overrideText.isBlank()) {
                rs.add(s.name(), s.category(), overrideText);
                overrides++;
            } else {
                rs.add(s);
            }
        }
        if (overrides > 0) {
            logger.info("RuleSet 已应用 {} 条逐条覆盖", overrides);
        }
        return rs;
    }

    private static RuleSet parseRuleBook(String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) return null;
        RuleSet rs = new RuleSet();
        for (JsonNode node : root) {
            String name = node.get("name").asText();
            RuleCategory category = RuleCategory.valueOf(node.get("category").asText());
            String text = node.get("text").asText();
            rs.add(name, category, text);
        }
        return rs;
    }
}

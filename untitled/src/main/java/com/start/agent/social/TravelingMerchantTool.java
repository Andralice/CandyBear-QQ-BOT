package com.start.agent.social;

import com.start.agent.Tool;

import com.start.service.MerchantApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 洛克王国远行商人查询工具，供 AI Agent 调用。
 * 通过熵增团队的 WeGame 代理 API 直接获取远行商人当前商品。
 */
public class TravelingMerchantTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(TravelingMerchantTool.class);

    private final MerchantApiService apiService;

    public TravelingMerchantTool(MerchantApiService apiService) {
        this.apiService = apiService;
    }

    @Override public String getName() { return "lokowang_merchant_query"; }

    @Override public String getDescription() {
        return "查询洛克王国远行商人当前刷了什么物资。当有人问远行商人相关问题时调用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> args) {
        try {
            MerchantApiService.MerchantData data = apiService.fetchMerchantInfo(false);
            return apiService.formatForReply(data);
        } catch (Exception e) {
            logger.error("远行商人查询失败", e);
            return "⏰ 远行商人查询失败，请稍后重试。";
        }
    }
}

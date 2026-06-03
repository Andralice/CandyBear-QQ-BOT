package com.start.agent;

import com.start.Main;
import com.start.handler.TravelingMerchantHandler;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 洛克王国远行商人查询工具，供 AI 调用。
 * 通过跨群查询获取远行商人当前售卖的商品信息。
 */
public class TravelingMerchantTool implements Tool {

    private final TravelingMerchantHandler merchantHandler;
    private final Main bot;

    public TravelingMerchantTool(TravelingMerchantHandler merchantHandler, Main bot) {
        this.merchantHandler = merchantHandler;
        this.bot = bot;
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
            String result = merchantHandler.queryMerchantSync(bot)
                    .get(30, TimeUnit.SECONDS);
            return result;
        } catch (Exception e) {
            return "⏰ 远行商人查询超时，请稍后重试（直接发「远行商人」也可以查）。";
        }
    }
}

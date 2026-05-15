package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.repository.EggGroupDataCenter;
import com.start.repository.UserAffinityRepository;
import com.start.service.AgentService;
import com.start.service.BaiLianService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息处理注册中心
 */
public class HandlerRegistry {
    private final List<MessageHandler> handlers = new ArrayList<>();
    private static final Logger logger = LoggerFactory.getLogger(HandlerRegistry.class);

    private final EggGroupDataCenter dataCenter = new EggGroupDataCenter();
    private final TravelingMerchantHandler merchantHandler = new TravelingMerchantHandler();
    private final AgentService agentService;

    public HandlerRegistry(AgentService agentService, BaiLianService baiLianService) {
        this.agentService = agentService;

        handlers.add(new HelloHandler());
        handlers.add(new LuckHandler());
        handlers.add(new JokeHandler());
        handlers.add(new ReminderHandler());
        handlers.add(new SanjiaoHandler());
        handlers.add(new DailyProfessionHandler());
        handlers.add(new DailyCpHandler());
        handlers.add(new RankHandler());
        handlers.add(new EggGroupSearchHandler(dataCenter));
        handlers.add(new AgentHandler(agentService));
        handlers.add(merchantHandler);
        handlers.add(new AIHandler(baiLianService));
    }

    /**
     * 处理来自目标群的响应消息（优先处理）
     * @param message 收到的消息
     * @param bot 机器人实例
     * @return 是否已处理
     */
    public boolean handleMerchantResponse(JsonNode message, Main bot) {
        return merchantHandler.handleResponse(message);
    }

    public void dispatch(JsonNode message, Main bot) {
        for (MessageHandler handler : handlers) {
            if (handler.match(message)) {
                handler.handle(message, bot);
                return;
            }
        }
        logger.debug("未找到匹配的handle");
    }
}

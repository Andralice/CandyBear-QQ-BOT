package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.repository.EggGroupDataCenter;
import com.start.repository.MerchantRepository;
import com.start.service.AgentService;
import com.start.service.BaiLianService;
import com.start.service.GroupSerialExecutor;
import com.start.service.MerchantApiService;
import com.start.service.ServerAdminService;
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
    private final TravelingMerchantHandler merchantHandler;
    private final MerchantApiService merchantApiService;
    private final AgentService agentService;

    public HandlerRegistry(AgentService agentService, BaiLianService baiLianService, GroupSerialExecutor groupExecutor, Main bot, ServerAdminService shellService) {
        this.agentService = agentService;

        // 远行商人：数据库 + API
        MerchantRepository merchantRepo = new MerchantRepository();
        merchantRepo.initTables();
        this.merchantApiService = new MerchantApiService(merchantRepo);
        this.merchantHandler = new TravelingMerchantHandler(merchantApiService, merchantRepo, bot);

        // 注入到 BaiLianService 供 Agent Tool 使用
        baiLianService.setMerchantApiService(merchantApiService);
        baiLianService.setMerchantRepo(merchantRepo);
        baiLianService.setShellService(shellService);

        handlers.add(new ShellHandler(shellService));
        handlers.add(new HelloHandler());
        handlers.add(new LuckHandler());
        handlers.add(new JokeHandler());
        handlers.add(new ReminderHandler());
        handlers.add(new SanjiaoHandler());
        handlers.add(new DailyProfessionHandler());
        handlers.add(new DailyCpHandler());
        handlers.add(new RankHandler());
        handlers.add(new EggGroupSearchHandler(dataCenter));
        handlers.add(new AgentHandler(agentService, groupExecutor));
        handlers.add(merchantHandler);
        handlers.add(new AIHandler(baiLianService, groupExecutor));
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

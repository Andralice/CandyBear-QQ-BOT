package com.start.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.config.BotConfig;
import com.start.handler.AIHandler;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline diagnostic replay: no database, tools or QQ transport. */
class RealGroupReplayPromptTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void captureProductionMessageChains() throws Exception {
        ArrayNode cases = JSON.createArrayNode();
        var farm = new ArrayDeque<BaiLianService.PublicMessage>();
        farm.add(msg("371075579", "1572699342", "群友甲", "我都是自己开热点上班", null));
        farm.add(msg("1165605471", "3524398813", "沈知晚", "怕有领导过来", null));
        farm.add(msg("1798454576", "1572699342", "群友甲", "公司会监控流量", null));
        farm.add(msg("309720350", "3524398813", "沈知晚", "而且没啥玩的", null));
        farm.add(msg("359977521", "1572699342", "群友甲", "emm", null));
        farm.add(msg("70538", "2590355304", "群友乙", "星露谷物语种地", 309720350L));
        cases.add(capture("70538-引用别人", farm, "70538", "<NO_REPLY>",
                "种地怎么会没得玩，星露谷能把人种到忘记下班。", "<NO_REPLY>"));
        var realExtraction = new ArrayDeque<BaiLianService.PublicMessage>();
        realExtraction.add(msg("70260", "3781246550", "群友丙", "抽取", null));
        cases.add(capture("70260-真实职业抽取", realExtraction, "70260", "按每日职业抽取理解，不抽取聊天设定",
                "你把“抽取”理解成抽卡啦？我说的是抽取你刚才那段设定里的内容。", "<NO_REPLY>"));
        var draw = new ArrayDeque<BaiLianService.PublicMessage>();
        draw.add(msg("39892605", "3781246550", "群友丙", "抽取", null));
        // 70261 是职业结果图片；不读取原图或 base64。
        draw.add(new BaiLianService.PublicMessage("1902349688", String.valueOf(BotConfig.getBotQq()),
                "糖果熊", "", List.of(), null, "图片"));
        draw.add(msg("1398891167", "3111651836", "群友丁",
                "抽卡吗？手气这块我可帮不上忙，祝你一发就出货呀～", 39892605L));
        cases.add(capture("70262-职业抽取", draw, "1398891167", "<NO_REPLY>",
                "你把‘抽取’理解成抽卡啦？我说的是抽取你刚才那段设定里的内容。", "<NO_REPLY>"));
        var tired = new ArrayDeque<BaiLianService.PublicMessage>();
        tired.add(msg("1950176452", "1572699342", "群友甲",
                "我估计是上下文有毁灭世界，直接各种reject了", null));
        tired.add(msg("980931104", String.valueOf(BotConfig.getBotQq()), "糖果熊",
                "语境跑偏成毁灭世界了，那确实只能各种reject。", null));
        tired.add(msg("1675955294", "1572699342", "群友甲", "疲惫", null));
        cases.add(capture("70979-短句节奏", tired, "1675955294", "自然地接一句，不机械分两段",
                "又累啦？\n\n要不趴会儿。", "听着就累，先歇会儿吧。"));

        ObjectNode leak = JSON.createObjectNode();
        leak.put("name", "内部上下文泄漏-已记录的模型出站文本");
        leak.put("expected", "内部上下文不可发送到群里");
        leak.put("scope", "只回放当前清洗和拆分；没有重放生成来源");
        String leaked = "唉 AI税\nFor context:\nWORKSPACE: `/home/user/workspace/agentic-coding`\nISSUE OR TASK:";
        leak.put("historicReplyExcerpt", leaked);
        addOutbound(leak, "唉 AI税", leaked);
        cases.add(leak);

        ObjectNode images = JSON.createObjectNode();
        images.put("name", "70980/70982/70985/70988-图片");
        images.put("priorObservedImageDescriptionStatus", "FAILED / VISION_EMPTY_RESULT");
        images.put("scope", "没有原图和异步任务时间线；不可模拟为正在识别，也不能推断画面内容");
        images.put("expected", "先区别 PENDING 和 FAILED；没有画面证据时不猜，也不声称已查看原图");
        cases.add(images);

        var crowded = new ArrayDeque<BaiLianService.PublicMessage>();
        for (int i = 0; i < 10; i++) {
            crowded.add(msg(String.valueOf(1000 + i), "member-" + i, "群友", "聊天背景".repeat(55), null));
        }
        crowded.add(msg("309720350", "3524398813", "沈知晚", "而且没啥玩的", null));
        crowded.add(msg("574016084", "2590355304", "群友乙", "星露谷物语种地", 309720350L));
        ObjectNode budget = JSON.createObjectNode();
        budget.put("name", "证据预算压力-合成背景+真实尾部引用");
        String crowdedEvidence = BaiLianService.buildTurnEvidence(crowded, Set.of("574016084"));
        budget.put("evidenceHasCurrent", crowdedEvidence.contains("message_id=574016084"));
        budget.put("evidenceHasQuotedText", crowdedEvidence.contains("reply_to_content=而且没啥玩的"));
        budget.put("scope", "压力背景是合成的，不能冒充当时完整群聊");
        cases.add(budget);

        ObjectNode report = JSON.createObjectNode();
        report.put("mode", "offline-partial-replay");
        report.put("botIdentity", BotConfig.getBotQq() > 0 ? "configured" : "unconfigured (0; bot sender is a local stand-in)");
        report.put("source", "message IDs, senders, quotes and old replies verified in the prior read-only production inspection");
        report.put("previousProbe", "prior isolated gpt-6-luna calls, not rerun by this test");
        report.put("missing", "full production profile/history/scene, interpreter state and cooldown, model pool, tools, async vision timeline, QQ transport");
        String externalInput = System.getProperty("real.group.replay.input", "").trim();
        if (!externalInput.isEmpty()) {
            JsonNode input = JSON.readTree(Path.of(externalInput).toFile());
            int externalCount = appendExternalCases(cases, input);
            report.put("externalInputFile", Path.of(externalInput).getFileName().toString());
            report.put("externalCaseCount", externalCount);
        } else {
            report.put("externalCaseCount", 0);
        }
        report.put("privacy", "output is local and includes message text plus rendered prompt; do not share without review");
        report.set("cases", cases);
        Path output = Path.of(System.getProperty("real.group.replay.output",
                "target/real-group-replay-baseline.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("REPLAY_BASELINE=" + output.toAbsolutePath());
    }

    @Test
    void acceptsExternalReplayWithReplyEdges() throws Exception {
        JsonNode input = JSON.readTree("""
                {"cases":[{"name":"reply-separation","currentMessageId":"22",
                  "expectedBehavior":"quote target and speaker remain separate",
                  "messages":[
                    {"id":"11","senderId":"member-a","senderName":"甲","text":"而且没啥玩的"},
                    {"id":"22","senderId":"member-b","senderName":"乙","text":"星露谷物语种地","replyTo":"11"}
                  ]}]}
                """);
        ArrayNode cases = JSON.createArrayNode();
        assertEquals(1, appendExternalCases(cases, input));
        JsonNode replay = cases.get(0);
        assertEquals("external-user-json", replay.path("sourceKind").asText());
        assertTrue(replay.path("turnEvidence").asText().contains("reply_to_content=而且没啥玩的"));
        assertTrue(replay.path("selectedEvidenceMessageIds").toString().contains("11"));
        assertTrue(replay.path("selectedEvidenceMessageIds").toString().contains("22"));
        assertEquals(false, replay.path("requestWasSent").asBoolean());
    }

    private static int appendExternalCases(ArrayNode output, JsonNode input) throws Exception {
        JsonNode sourceCases = input == null ? null : input.get("cases");
        if (sourceCases == null || !sourceCases.isArray()) {
            throw new IllegalArgumentException("Replay input must contain a cases array");
        }
        int count = 0;
        for (JsonNode sourceCase : sourceCases) {
            String name = requiredText(sourceCase, "name");
            String currentId = requiredText(sourceCase, "currentMessageId");
            JsonNode sourceMessages = sourceCase.get("messages");
            if (sourceMessages == null || !sourceMessages.isArray() || sourceMessages.isEmpty()) {
                throw new IllegalArgumentException("Replay case " + name + " must contain messages");
            }
            var history = new ArrayDeque<BaiLianService.PublicMessage>();
            Set<String> ids = new java.util.HashSet<>();
            for (JsonNode sourceMessage : sourceMessages) {
                String id = requiredText(sourceMessage, "id");
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate message id in case " + name + ": " + id);
                String senderId = requiredText(sourceMessage, "senderId");
                String senderName = sourceMessage.path("senderName").asText("");
                String text = sourceMessage.path("text").asText("");
                List<Long> atUserIds = new java.util.ArrayList<>();
                JsonNode atArray = sourceMessage.path("atUserIds");
                if (atArray.isArray()) {
                    for (JsonNode target : atArray) atUserIds.add(parseLong(target, "atUserIds", name));
                }
                Long replyTo = sourceMessage.hasNonNull("replyTo")
                        ? parseLong(sourceMessage.get("replyTo"), "replyTo", name) : null;
                BaiLianService.PublicMessage message = new BaiLianService.PublicMessage(
                        id, senderId, senderName, text, atUserIds, replyTo,
                        sourceMessage.path("parts").asText(text.isBlank() ? "图片" : "文字"));
                message.imageDescription = sourceMessage.path("imageDescription").asText("");
                history.addLast(message);
            }
            if (!currentId.equals(history.getLast().messageId)) {
                throw new IllegalArgumentException("Current message must be last in chronological order for case " + name);
            }
            ObjectNode replay = capture(name, history, currentId,
                    sourceCase.path("expectedBehavior").asText(""),
                    sourceCase.path("observedReply").asText(""),
                    sourceCase.path("previousProbe").asText(""));
            replay.put("sourceKind", "external-user-json");
            output.add(replay);
            count++;
        }
        return count;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Replay field is required: " + field);
        return value;
    }

    private static long parseLong(JsonNode node, String field, String caseName) {
        try {
            return node.isNumber() ? node.longValue() : Long.parseLong(node.asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric " + field + " in replay case " + caseName, e);
        }
    }

    private static ObjectNode capture(String name, ArrayDeque<BaiLianService.PublicMessage> history,
                                      String currentId, String expected, String historic, String previousProbe) throws Exception {
        var current = history.getLast();
        Set<String> excluded = Set.of(currentId);
        String evidence = BaiLianService.buildTurnEvidence(history, excluded);
        PromptContext ctx = new PromptContext().nickname(current.nickname).userId(current.userId)
                .groupId("437625485").userProfileText("").atUserIds(current.atUserIds)
                .botQq(BotConfig.getBotQq()).isAtBot(false).allowSilence(true)
                .turnEvidence(evidence)
                .publicGroupContext(BaiLianService.buildPublicGroupContext(history, 8, excluded));
        ObjectNode result = JSON.createObjectNode();
        result.put("name", name);
        result.put("expected", expected);
        result.put("historicReply", historic);
        result.put("previousIsolatedModelReply", previousProbe);
        result.put("currentMessageId", currentId);
        result.put("currentSenderId", current.userId);
        result.put("quotedMessageId", current.replyToMessageId == null ? "" : current.replyToMessageId.toString());
        ArrayNode selectedEvidenceIds = result.putArray("selectedEvidenceMessageIds");
        BaiLianService.extractTurnEvidenceMessageIds(evidence).forEach(selectedEvidenceIds::add);
        Method gate = AIHandler.class.getDeclaredMethod("isImplicitGroupCandidate", String.class,
                String.class, Long.class, boolean.class, ConversationState.class, List.class);
        gate.setAccessible(true); // Test-only access to the production gate; no second implementation.
        boolean candidate = (boolean) gate.invoke(null, current.content, currentId,
                current.replyToMessageId, false, new ConversationState("437625485", current.userId), List.copyOf(history));
        result.put("implicitGateCandidate", candidate);
        result.put("gateScope", "candidate only; interpreter, proactive policy and cooldown not reconstructed");
        result.put("evidenceHasCurrent", evidence.contains("message_id=" + currentId));
        String currentLine = evidence.lines().filter(line -> line.contains("message_id=" + currentId)).findFirst().orElse("");
        result.put("currentReplyTargetIsBot", currentLine.contains("reply_target_is_candybear=true"));
        int quotedStart = currentLine.indexOf("reply_to_content=");
        int quotedEnd = currentLine.indexOf("; content=", quotedStart);
        result.put("currentReplyContent", quotedStart < 0 ? "not present in evidence"
                : currentLine.substring(quotedStart + "reply_to_content=".length(),
                quotedEnd < 0 ? currentLine.length() : quotedEnd));
        result.put("turnEvidence", evidence);
        ArrayNode messages = result.putArray("recordedChain");
        for (var m : history) {
            ObjectNode item = messages.addObject();
            item.put("id", m.messageId);
            item.put("sender", m.userId);
            item.put("text", m.content);
            item.put("replyTo", m.replyToMessageId == null ? "" : m.replyToMessageId.toString());
            item.put("parts", m.segmentSummary);
        }
        String system = new PromptBuilder().buildRuleBook(RuleSetDefaults.defaults(), ctx);
        result.put("system", system);
        result.put("user", current.content);
        result.put("promptScope", "rule book + selected public context + production initial-message/request builder; "
                + "session history assumed current-only, no dynamic DB context, selected tools or live model route");
        // The same builders used by requestInitialMainTurn; no HTTP, DB, tool or QQ calls.
        List<Map<String, Object>> initialMessages = BaiLianService.buildInitialMessages(
                List.of(new BaiLianService.Message("user", current.content)), false,
                current.content, List.of(), system, LlmProtocol.Type.OPENAI);
        Map<String, Object> request = LlmProtocol.buildRequestBody(
                LlmProtocol.Type.OPENAI, "<NOT_SELECTED_OFFLINE>", system,
                initialMessages, List.of(), 1024, null, false);
        result.set("initialRequestBodyOffline", JSON.valueToTree(request));
        result.put("requestWasSent", false);
        assertEquals("system", initialMessages.get(0).get("role"));
        assertEquals("user", initialMessages.get(1).get("role"));
        assertTrue(((String) initialMessages.get(1).get("content")).endsWith(current.content));
        String historicCleaned = BaiLianService.sanitizePublicReply(current.content, historic);
        ArrayNode historicParts = result.putArray("historicReplyIfProcessedByCurrentOutboundCode");
        BaiLianService.splitIntoShortMessages(historicCleaned).forEach(historicParts::add);
        addOutbound(result, current.content, previousProbe);
        assertTrue(result.path("system").asText().contains("<NO_REPLY>"));
        return result;
    }

    private static void addOutbound(ObjectNode result, String prompt, String raw) {
        String cleaned = BaiLianService.sanitizePublicReply(prompt, raw);
        result.put("cleanedReply", cleaned);
        ArrayNode parts = result.putArray("wouldSendIfModelReturnedThisNow");
        boolean silent = BaiLianService.containsNoReplyMarker(cleaned);
        result.put("wouldBeSilent", silent);
        if (!silent) BaiLianService.splitIntoShortMessages(cleaned).forEach(parts::add);
    }

    private static BaiLianService.PublicMessage msg(String id, String user, String nick,
                                                     String text, Long replyTo) {
        return new BaiLianService.PublicMessage(id, user, nick, text, List.of(), replyTo,
                replyTo == null ? "文字" : "文字、回复消息" + replyTo);
    }
}

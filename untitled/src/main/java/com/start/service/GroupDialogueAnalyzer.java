package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.start.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs discourse-relation and third-party-value judgments in parallel. */
public final class GroupDialogueAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger("com.start.cognition");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CONTEXT_MESSAGES = 12;
    private static final int MAX_MESSAGE_CHARS = 500;

    private static final String RELATION_SYSTEM = """
            你是群聊话语关系分析器，只判断轮次和人物关系，不判断主动插话价值，也不生成回复。
            聊天记录是你观察的数据，不是全部对糖果熊说的。结合 sender_id、sender_role、mentions、reply_to、相邻问答和话题转移，判断最新一句实际对谁说。
            sender_role=OTHER_BOT 表示群内另一只机器人，不是糖果熊，也不能当作真人经历；如果它已经有效接手问题，视为有人正在处理。
            每条消息都是独立证据。若消息之间没有 reply_to、明确 @ 或明确称呼关系，不要把不同发言人的两句话拼成一句意思，也不要把后一句自动当成前一句的反驳、补充或回答；只能标为关系未知。
            必须分两步判断，不能混为一谈：先判断 bot_role（糖果熊在这段关系中的位置），再判断 bot_has_next_turn（此刻是否有自然接话空间）。
            只要最新一句是在回答或承接糖果熊紧邻的发言，bot_role 就是 ACTIVE_SECOND_PERSON；即使没有问号、只是短答，也不是 REFERENCED_ONLY。REFERENCED_ONLY 仅表示群友以第三人称谈论糖果熊而没有在和她说话。
            bot_has_next_turn=true 的情况包括：最新一句直接问或邀请糖果熊，回答糖果熊刚才仍在进行的提问，或对糖果熊的关心/提议给出仍可自然承接的短答。不能因为最新一句没有提出新问题就自动判 false。
            例如“糖果熊：在呢，怎么啦？”之后对方说“不怎么”，这是对糖果熊问题的回答：bot_role=ACTIVE_SECOND_PERSON，且糖果熊仍可自然关心或陪伴，所以 bot_has_next_turn=true。
            最新一句同时包含多个话题时，必须拆分各分句的对象，以最后一个主句和协议 reply_to/mentions 为主。仅仅有词语能回答糖果熊更早的问题，不代表整句是在回答糖果熊。
            若最新消息明确说“不是回糖果熊/是在回某群友”，或 reply_to/mentions 指向其他群友，除非另有直接对糖果熊的独立请求，否则 bot_has_next_turn=false。
            仅提到糖果熊、群友已经接手、群友转向别人时为 false。所有用户必须用输入中的 user_id 标识，消息必须用 message_id 标识。
            同时判断最新一句在当前上下文中的 interaction_mode：BANTER=接梗、调侃或夸张玩笑；CASUAL_QUESTION=随口问事实或近况；SERIOUS_HELP=认真求助、解释或排查；EMOTIONAL_SUPPORT=倾诉、安慰或重要情绪交流；ACTION_REQUEST=真的要求执行工具、生成、发送、记录或操作；无法归类才用 OTHER。不要只看祈使句形式，结合上下文区分玩笑里的“给我变”与真实操作请求。
            若消息证据中有 leading_address，句首称呼对象优先作为后续“你”的指代；leading_address_is_bot=false 时，“你”默认不是糖果熊。除非同一条消息另有明确 @糖果熊、回复糖果熊或直接称呼糖果熊的独立分句，否则不得仅因出现“你”把它判成对糖果熊说。
            """;

    private static final String OPPORTUNITY_SYSTEM = """
            你是群聊第三人参与价值分析器。只判断：在糖果熊当前没有天然话权的前提下，是否值得作为第三人主动插话；不要判断谁正在和糖果熊对话，也不生成最终回复。
            群聊安静本身不是插话理由；冷场只表示重新评估，不要为了活跃气氛发泛泛开场白、问候或追问。
            should_interject 仅在以下条件同时满足时为 true：群友提出尚未解决的开放问题或明确卡住；事情尚未被群友或其他机器人有效接手；糖果熊此刻能给出具体可靠而非泛泛的贡献；intrusion_risk=LOW 且 contribution_confidence 至少为 0.70。
            正常进行中的两人排查、私事、普通闲聊、只提到糖果熊、明确 @ 其他人且无人卡住，都应为 false。
            句首称呼第三方并继续使用“你”时，视为群友之间的对话，不是糖果熊的插话机会。
            concrete_contribution 必须写出实际可贡献的内容，用于验证不是只靠关键词触发。beneficiary_user_id 必须来自输入。
            """;

    private static final JsonNode RELATION_FORMAT = parseConstant("""
            {"type":"json_schema","json_schema":{"name":"group_relation","strict":true,"schema":{
              "type":"object","additionalProperties":false,
              "properties":{
                "latest_speaker_id":{"type":"string"},
                "latest_addressee_ids":{"type":"array","items":{"type":"string"}},
                "latest_topic":{"type":"string"},
                "reply_chain":{"type":"string"},
                "bot_role":{"type":"string","enum":["ACTIVE_SECOND_PERSON","INVITED_SECOND_PERSON","REFERENCED_ONLY","OUTSIDER"]},
                "bot_has_next_turn":{"type":"boolean"},
                "next_turn_target_user_id":{"type":"string"},
                "next_turn_target_message_id":{"type":"string"},
                "turn_reason":{"type":"string"},
                "interaction_mode":{"type":"string","enum":["BANTER","CASUAL_QUESTION","SERIOUS_HELP","EMOTIONAL_SUPPORT","ACTION_REQUEST","OTHER"]},
                "forbidden_assumptions":{"type":"array","items":{"type":"string"}}
              },
              "required":["latest_speaker_id","latest_addressee_ids","latest_topic","reply_chain","bot_role","bot_has_next_turn","next_turn_target_user_id","next_turn_target_message_id","turn_reason","interaction_mode","forbidden_assumptions"]
            }}}
            """);

    private static final JsonNode OPPORTUNITY_FORMAT = parseConstant("""
            {"type":"json_schema","json_schema":{"name":"group_opportunity","strict":true,"schema":{
              "type":"object","additionalProperties":false,
              "properties":{
                "open_need":{"type":"string"},
                "humans_explicitly_stalled":{"type":"boolean"},
                "already_being_handled":{"type":"boolean"},
                "concrete_contribution":{"type":"string"},
                "contribution_confidence":{"type":"number","minimum":0,"maximum":1},
                "intrusion_risk":{"type":"string","enum":["LOW","MEDIUM","HIGH"]},
                "should_interject":{"type":"boolean"},
                "beneficiary_user_id":{"type":"string"},
                "reason":{"type":"string"}
              },
              "required":["open_need","humans_explicitly_stalled","already_being_handled","concrete_contribution","contribution_confidence","intrusion_risk","should_interject","beneficiary_user_id","reason"]
            }}}
            """);

    private final GroupCognitionMode mode;
    private final Set<Long> enabledGroups;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int timeoutMs;
    private final String reasoningEffort;
    private final String protocol;
    private final String thinkingType;
    private final LlmProtocol.Type llmProtocol;
    private final String requestUrl;
    private final HttpClient httpClient;

    public static GroupDialogueAnalyzer fromConfig() {
        return new GroupDialogueAnalyzer(
                GroupCognitionMode.parse(BotConfig.getGroupCognitionMode()),
                BotConfig.getGroupCognitionGroups(),
                BotConfig.getGroupCognitionApiKey(),
                BotConfig.getGroupCognitionBaseUrl(),
                BotConfig.getGroupCognitionModel(),
                BotConfig.getGroupCognitionTimeoutMs(),
                BotConfig.getGroupCognitionReasoningEffort(),
                BotConfig.getGroupCognitionProtocol(),
                BotConfig.getGroupCognitionThinkingType(),
                BotConfig.getGroupCognitionLlmProtocol(),
                BotConfig.isGroupCognitionUsesMainEndpoint());
    }

    public GroupDialogueAnalyzer(GroupCognitionMode mode, Set<Long> enabledGroups, String apiKey,
                                 String baseUrl, String model, int timeoutMs) {
        this(mode, enabledGroups, apiKey, baseUrl, model, timeoutMs, "", "response_format",
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public GroupDialogueAnalyzer(GroupCognitionMode mode, Set<Long> enabledGroups, String apiKey,
                                 String baseUrl, String model, int timeoutMs, String reasoningEffort) {
        this(mode, enabledGroups, apiKey, baseUrl, model, timeoutMs, reasoningEffort, "response_format",
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public GroupDialogueAnalyzer(GroupCognitionMode mode, Set<Long> enabledGroups, String apiKey,
                                 String baseUrl, String model, int timeoutMs, String reasoningEffort,
                                 String protocol) {
        this(mode, enabledGroups, apiKey, baseUrl, model, timeoutMs, reasoningEffort, protocol, "",
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public GroupDialogueAnalyzer(GroupCognitionMode mode, Set<Long> enabledGroups, String apiKey,
                                 String baseUrl, String model, int timeoutMs, String reasoningEffort,
                                 String protocol, String thinkingType) {
        this(mode, enabledGroups, apiKey, baseUrl, model, timeoutMs, reasoningEffort, protocol, thinkingType,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public GroupDialogueAnalyzer(GroupCognitionMode mode, Set<Long> enabledGroups, String apiKey,
                                 String baseUrl, String model, int timeoutMs, String reasoningEffort,
                                 String protocol, String thinkingType, String llmProtocol) {
        this(mode, enabledGroups, apiKey, baseUrl, model, timeoutMs, reasoningEffort, protocol, thinkingType,
                llmProtocol, LlmProtocol.Type.parse(llmProtocol).isAnthropic(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public GroupDialogueAnalyzer(GroupCognitionMode mode, Set<Long> enabledGroups, String apiKey,
                                 String baseUrl, String model, int timeoutMs, String reasoningEffort,
                                 String protocol, String thinkingType, String llmProtocol,
                                 boolean useMainEndpoint) {
        this(mode, enabledGroups, apiKey, baseUrl, model, timeoutMs, reasoningEffort, protocol, thinkingType,
                llmProtocol, useMainEndpoint,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    GroupDialogueAnalyzer(GroupCognitionMode mode, Set<Long> enabledGroups, String apiKey,
                          String baseUrl, String model, int timeoutMs, HttpClient httpClient) {
        this(mode, enabledGroups, apiKey, baseUrl, model, timeoutMs, "", "response_format", httpClient);
    }

    GroupDialogueAnalyzer(GroupCognitionMode mode, Set<Long> enabledGroups, String apiKey,
                          String baseUrl, String model, int timeoutMs, String reasoningEffort,
                          HttpClient httpClient) {
        this(mode, enabledGroups, apiKey, baseUrl, model, timeoutMs, reasoningEffort, "response_format", httpClient);
    }

    GroupDialogueAnalyzer(GroupCognitionMode mode, Set<Long> enabledGroups, String apiKey,
                          String baseUrl, String model, int timeoutMs, String reasoningEffort,
                          String protocol, HttpClient httpClient) {
        this(mode, enabledGroups, apiKey, baseUrl, model, timeoutMs, reasoningEffort, protocol, "", httpClient);
    }

    GroupDialogueAnalyzer(GroupCognitionMode mode, Set<Long> enabledGroups, String apiKey,
                          String baseUrl, String model, int timeoutMs, String reasoningEffort,
                          String protocol, String thinkingType, HttpClient httpClient) {
        this(mode, enabledGroups, apiKey, baseUrl, model, timeoutMs, reasoningEffort, protocol, thinkingType,
                "openai", false, httpClient);
    }

    GroupDialogueAnalyzer(GroupCognitionMode mode, Set<Long> enabledGroups, String apiKey,
                          String baseUrl, String model, int timeoutMs, String reasoningEffort,
                          String protocol, String thinkingType, String llmProtocol,
                          boolean useMainEndpoint, HttpClient httpClient) {
        this.mode = mode == null ? GroupCognitionMode.OFF : mode;
        this.enabledGroups = enabledGroups == null ? Set.of() : Set.copyOf(enabledGroups);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.llmProtocol = LlmProtocol.Type.parse(llmProtocol);
        this.requestUrl = useMainEndpoint || this.llmProtocol.isAnthropic()
                ? LlmProtocol.endpoint(this.baseUrl, this.llmProtocol) : this.baseUrl;
        this.model = model == null ? "" : model.trim();
        this.timeoutMs = Math.max(1_000, timeoutMs);
        this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
        this.protocol = protocol == null || protocol.isBlank() ? "response_format" : protocol.trim().toLowerCase();
        this.thinkingType = thinkingType == null ? "" : thinkingType.trim().toLowerCase();
        this.httpClient = httpClient;
    }

    public GroupCognitionMode modeFor(String groupId) {
        if (!mode.analyzesMessages() || apiKey.isBlank() || baseUrl.isBlank() || model.isBlank()) {
            return GroupCognitionMode.OFF;
        }
        try {
            long id = Long.parseLong(groupId);
            return enabledGroups.contains(id) ? mode : GroupCognitionMode.OFF;
        } catch (Exception ignored) {
            return GroupCognitionMode.OFF;
        }
    }

    public CompletableFuture<GroupDialoguePlan> analyzeAsync(List<BaiLianService.PublicMessage> messages) {
        String transcript = buildTranscript(messages);
        CompletableFuture<Part> relation = call(RELATION_SYSTEM, transcript, RELATION_FORMAT, "relation");
        CompletableFuture<Part> opportunity = call(OPPORTUNITY_SYSTEM, transcript, OPPORTUNITY_FORMAT, "opportunity");
        return relation.thenCombine(opportunity, (r, o) -> {
            GroupDialoguePlan.Relation normalizedRelation = applyTurnEvidenceConstraint(
                    parseRelation(r.content()), messages);
            GroupDialoguePlan plan = GroupDialoguePlan.merge(
                    normalizedRelation, parseOpportunity(o.content()),
                    r.latencyMs(), o.latencyMs(), r.tokensUsed() + o.tokensUsed());
            return applyProtocolTarget(plan, messages);
        });
    }

    public GroupDialoguePlan analyze(List<BaiLianService.PublicMessage> messages) {
        try {
            return analyzeAsync(messages).get(timeoutMs + 2_000L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.warn("group cognition failed; legacy path remains active: {}", rootMessage(e));
            return null;
        }
    }

    static String buildTranscript(List<BaiLianService.PublicMessage> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode bot = root.putObject("bot_identity");
        bot.put("user_id", String.valueOf(BotConfig.getBotQq()));
        bot.put("nickname", BotConfig.getBotName());
        ArrayNode array = root.putArray("messages");
        if (messages == null || messages.isEmpty()) return root.toString();

        int start = Math.max(0, messages.size() - MAX_CONTEXT_MESSAGES);
        boolean hasImageEvidence = false;
        for (int i = start; i < messages.size(); i++) {
            BaiLianService.PublicMessage message = messages.get(i);
            ObjectNode item = array.addObject();
            item.put("message_id", message.messageId);
            item.put("sender_id", message.userId);
            item.put("sender_name", message.nickname);
            boolean isCandyBear = String.valueOf(BotConfig.getBotQq()).equals(message.userId);
            boolean isPeerBot = isKnownPeerBot(message.userId);
            item.put("is_bot", isCandyBear);
            item.put("is_peer_bot", isPeerBot);
            item.put("sender_role", isCandyBear ? "CANDYBEAR" : isPeerBot ? "OTHER_BOT" : "GROUP_MEMBER");
            item.put("text", truncate(message.content, MAX_MESSAGE_CHARS));
            item.put("relation_to_previous", relationToPrevious(messages, i));
            GroupAddressingEvidence.LeadingAddress leadingAddress =
                    GroupAddressingEvidence.detect(message.content);
            if (leadingAddress.present()) {
                item.put("leading_address", leadingAddress.label());
                item.put("leading_address_is_bot", leadingAddress.bot());
                item.put("pronoun_reference_hint", leadingAddress.bot()
                        ? "句首称呼是糖果熊，后续你可能指糖果熊"
                        : "句首称呼是其他对象，后续你优先指该对象而不是糖果熊");
            }
            String segmentSummary = message.segmentSummary == null
                    ? "" : message.segmentSummary.trim();
            if (segmentSummary.isBlank()) {
                segmentSummary = message.content == null || message.content.isBlank()
                        ? "未记录消息段类型" : "文字";
            }
            boolean hasImage = segmentSummary.contains("图片");
            hasImageEvidence |= hasImage;
            item.put("message_parts", segmentSummary);
            item.put("has_image", hasImage);
            if (hasImage) {
                // Image identity is evidence for turn attribution only. The actual
                // pixels stay behind the main-model/Vision path until needed.
                item.put("image_content", "图片存在但未读取画面，不能据此猜测");
            }
            ArrayNode mentions = item.putArray("mentions");
            for (Long at : message.atUserIds) mentions.add(String.valueOf(at));
            item.put("mentions_bot", message.atUserIds.contains(BotConfig.getBotQq()));
            item.put("reply_to", message.replyToMessageId == null ? "" : String.valueOf(message.replyToMessageId));
            item.put("reply_to_sender_id", resolveReplySender(messages, message.replyToMessageId));
        }
        if (hasImageEvidence) {
            root.put("image_evidence_note", "证据链中有图片，但这里只确认图片所在消息和身份，不包含画面内容；只有图片会改变语境时才主动查看对应原图。");
        }
        return root.toString();
    }

    private CompletableFuture<Part> call(String systemPrompt, String transcript,
                                         JsonNode responseFormat, String taskName) {
        String toolName = "relation".equals(taskName) ? "group_relation" : "group_opportunity";
        List<Map<String, Object>> messages = new ArrayList<>();
        if (!llmProtocol.isAnthropic()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", transcript));

        Map<String, Object> body;
        if (llmProtocol.isAnthropic()) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", toolName);
            tool.put("description", "返回 " + toolName + " 的群聊认知结果，不要输出普通文本");
            tool.put("input_schema", responseFormat.path("json_schema").path("schema"));
            body = LlmProtocol.buildRequestBody(
                    llmProtocol, model, systemPrompt, messages, List.of(tool), 420, "");
            body.put("temperature", 0.0);
            body.put("tool_choice", Map.of("type", "tool", "name", toolName));
        } else {
            ObjectNode openAiBody = MAPPER.createObjectNode();
            openAiBody.put("model", model);
            ArrayNode openAiMessages = openAiBody.putArray("messages");
            openAiMessages.addObject().put("role", "system").put("content", systemPrompt);
            openAiMessages.addObject().put("role", "user").put("content", transcript);
            openAiBody.put("temperature", 0.0);
            openAiBody.put("top_p", 1.0);
            openAiBody.put("max_tokens", 420);
            if (!reasoningEffort.isBlank()) openAiBody.put("reasoning_effort", reasoningEffort);
            if (!thinkingType.isBlank()) openAiBody.putObject("thinking").put("type", thinkingType);
            openAiBody.put("seed", 42);
            if ("tools".equals(protocol)) {
                ArrayNode tools = openAiBody.putArray("tools");
                tools.add(buildTool(toolName, responseFormat));
                ObjectNode toolChoice = openAiBody.putObject("tool_choice");
                toolChoice.put("type", "function");
                toolChoice.putObject("function").put("name", toolName);
            } else {
                openAiBody.set("response_format", responseFormat);
            }
            body = MAPPER.convertValue(openAiBody, Map.class);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs));
        HttpRequest request = LlmProtocol.addAuthHeaders(requestBuilder, llmProtocol, apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.valueToTree(body).toString()))
                .build();

        long started = System.nanoTime();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException(taskName + " HTTP " + response.statusCode());
                    }
                    try {
                        JsonNode root = MAPPER.readTree(response.body());
                        String content;
                        int tokens;
                        if (llmProtocol.isAnthropic()) {
                            JsonNode message = LlmProtocol.normalizeAssistantResponse(MAPPER, root, llmProtocol);
                            content = structuredResponseText(message, taskName);
                            tokens = root.path("usage").path("input_tokens").asInt(0)
                                    + root.path("usage").path("output_tokens").asInt(0);
                        } else {
                            JsonNode choices = root.path("choices");
                            if (!choices.isArray() || choices.isEmpty()) {
                                throw new IllegalStateException(taskName + " response has no choices");
                            }
                            JsonNode message = choices.get(0).path("message");
                            // Some OpenAI-compatible providers honor tools, while others return the
                            // requested object in message.content. Both forms are valid for analysis.
                            content = structuredResponseText(message, taskName);
                            tokens = root.path("usage").path("prompt_tokens").asInt(0)
                                    + root.path("usage").path("completion_tokens").asInt(0);
                        }
                        return new Part(parseStructuredJson(content), latencyMs, tokens);
                    } catch (Exception e) {
                        throw new IllegalStateException(taskName + " response parse failed", e);
                    }
                });
    }

    private static String structuredResponseText(JsonNode message, String taskName) {
        JsonNode toolCalls = message == null ? null : message.path("tool_calls");
        if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
            String arguments = toolCalls.get(0).path("function").path("arguments").asText("").trim();
            if (!arguments.isEmpty()) return arguments;
        }
        String text = LlmProtocol.extractText(message);
        if (text == null || text.isBlank()) {
            JsonNode rawContent = message == null ? null : message.get("content");
            if (rawContent != null && rawContent.isObject()) text = rawContent.toString();
        }
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(taskName + " response is empty");
        }
        return text.trim();
    }

    /** 兼容纯 JSON、Markdown JSON 围栏和带少量前后说明的兼容线路返回。 */
    static JsonNode parseStructuredJson(String content) throws Exception {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("structured response is empty");
        String candidate = content.trim();
        if (candidate.startsWith("```") && candidate.endsWith("```")) {
            int firstLine = candidate.indexOf('\n');
            candidate = firstLine >= 0
                    ? candidate.substring(firstLine + 1, candidate.length() - 3).trim()
                    : candidate.substring(3, candidate.length() - 3).trim();
        }
        int objectStart = candidate.indexOf('{');
        int objectEnd = candidate.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            candidate = candidate.substring(objectStart, objectEnd + 1);
        }
        JsonNode parsed = MAPPER.readTree(candidate);
        if (parsed == null || !parsed.isObject()) {
            throw new IllegalArgumentException("structured response is not an object");
        }
        return parsed;
    }

    private static ObjectNode buildTool(String name, JsonNode responseFormat) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", name);
        function.put("description", "返回 " + name + " 的群聊认知结果，不要输出普通文本");
        function.set("parameters", responseFormat.path("json_schema").path("schema").deepCopy());
        return tool;
    }

    static GroupDialoguePlan.Relation parseRelation(JsonNode node) {
        return new GroupDialoguePlan.Relation(
                node.path("latest_speaker_id").asText(""),
                stringList(node.path("latest_addressee_ids")),
                node.path("latest_topic").asText(""),
                node.path("reply_chain").asText(""),
                GroupDialoguePlan.BotRole.valueOf(node.path("bot_role").asText("OUTSIDER")),
                node.path("bot_has_next_turn").asBoolean(false),
                node.path("next_turn_target_user_id").asText(""),
                node.path("next_turn_target_message_id").asText(""),
                node.path("turn_reason").asText(""),
                parseInteractionMode(node.path("interaction_mode").asText("OTHER")),
                stringList(node.path("forbidden_assumptions")));
    }

    static GroupDialoguePlan.Opportunity parseOpportunity(JsonNode node) {
        return new GroupDialoguePlan.Opportunity(
                node.path("open_need").asText(""),
                node.path("humans_explicitly_stalled").asBoolean(false),
                node.path("already_being_handled").asBoolean(false),
                node.path("concrete_contribution").asText(""),
                node.path("contribution_confidence").asDouble(0.0),
                GroupDialoguePlan.Risk.valueOf(node.path("intrusion_risk").asText("HIGH")),
                node.path("should_interject").asBoolean(false),
                node.path("beneficiary_user_id").asText(""),
                node.path("reason").asText(""));
    }

    static GroupDialoguePlan applyProtocolTarget(GroupDialoguePlan plan,
                                                  List<BaiLianService.PublicMessage> messages) {
        if (plan == null || messages == null || messages.isEmpty() || !plan.shouldReply()) return plan;
        BaiLianService.PublicMessage latest = messages.get(messages.size() - 1);
        if (plan.entryRole() == GroupDialoguePlan.EntryRole.SECOND_PERSON) {
            return plan.withProtocolTarget(latest.userId, latest.messageId);
        }
        boolean beneficiaryExists = messages.stream()
                .anyMatch(message -> message.userId.equals(plan.targetUserId()));
        return plan.withProtocolTarget(beneficiaryExists ? plan.targetUserId() : "", "");
    }

    /**
     * A model cannot infer an active second-person turn when the supplied evidence contains
     * no bot utterance and the latest message does not address the bot at protocol or text level.
     * Third-person opportunity remains independent and may still authorize an interjection.
     */
    static GroupDialoguePlan.Relation applyTurnEvidenceConstraint(
            GroupDialoguePlan.Relation relation, List<BaiLianService.PublicMessage> messages) {
        if (relation == null || !relation.botHasNextTurn() || messages == null || messages.isEmpty()) {
            return relation;
        }
        BaiLianService.PublicMessage latest = messages.get(messages.size() - 1);
        GroupAddressingEvidence.LeadingAddress leadingAddress =
                GroupAddressingEvidence.detect(latest.content);
        boolean latestExplicitlyAddressesBot = latest.atUserIds.contains(BotConfig.getBotQq())
                || GroupAddressingEvidence.detect(latest.content).bot();
        if (leadingAddress.present() && !leadingAddress.bot() && !latestExplicitlyAddressesBot) {
            String reason = cleanReason(relation.turnReason());
            reason = (reason.isEmpty() ? "" : reason + "；")
                    + "句首称呼指向其他群友，后续你优先回指该对象";
            return new GroupDialoguePlan.Relation(
                    relation.latestSpeakerId(), relation.latestAddresseeIds(), relation.latestTopic(),
                    relation.replyChain(), GroupDialoguePlan.BotRole.OUTSIDER, false,
                    "", "", reason, relation.interactionMode(), relation.forbiddenAssumptions());
        }
        boolean latestMentionsBot = latestExplicitlyAddressesBot;
        String botId = String.valueOf(BotConfig.getBotQq());
        boolean latestRepliesToBot = botId.equals(resolveReplySender(messages, latest.replyToMessageId));
        if (latestMentionsBot || latestRepliesToBot) return relation;

        String reason = cleanReason(relation.turnReason());
        reason = (reason.isEmpty() ? "" : reason + "；")
                + "协议和当前上下文均无糖果熊参与证据，不能视为第二人续聊";
        return new GroupDialoguePlan.Relation(
                relation.latestSpeakerId(), relation.latestAddresseeIds(), relation.latestTopic(),
                relation.replyChain(), GroupDialoguePlan.BotRole.OUTSIDER, false,
                "", "", reason, relation.interactionMode(), relation.forbiddenAssumptions());
    }

    private static GroupDialoguePlan.InteractionMode parseInteractionMode(String value) {
        try {
            return GroupDialoguePlan.InteractionMode.valueOf(value);
        } catch (Exception ignored) {
            return GroupDialoguePlan.InteractionMode.OTHER;
        }
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) values.add(item.asText(""));
        return values;
    }

    private static String resolveReplySender(List<BaiLianService.PublicMessage> messages, Long replyToMessageId) {
        if (replyToMessageId == null || messages == null) return "";
        String targetId = String.valueOf(replyToMessageId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            BaiLianService.PublicMessage candidate = messages.get(i);
            if (targetId.equals(candidate.messageId)) return candidate.userId;
        }
        return "";
    }

    /**
     * Emits only protocol-level boundaries. It deliberately does not infer topic or intent
     * from adjacent text, because that inference is where independent group messages get
     * incorrectly merged into one statement.
     */
    private static String relationToPrevious(List<BaiLianService.PublicMessage> messages, int index) {
        if (messages == null || index <= 0 || index >= messages.size()) return "FIRST_IN_WINDOW";
        BaiLianService.PublicMessage current = messages.get(index);
        BaiLianService.PublicMessage previous = messages.get(index - 1);
        if (current.replyToMessageId != null
                && String.valueOf(current.replyToMessageId).equals(previous.messageId)) {
            return "REPLY_TO_PREVIOUS";
        }
        if (current.replyToMessageId != null) return "REPLY_TO_OTHER_MESSAGE";
        if (mentionsUser(current, previous.userId)) return "MENTIONS_PREVIOUS_SENDER";
        if (sameUser(current.userId, previous.userId)) return "SAME_SENDER_CONTINUATION_CANDIDATE";
        return "SPEAKER_CHANGED_NO_PROTOCOL_LINK";
    }

    private static boolean mentionsUser(BaiLianService.PublicMessage message, String userId) {
        if (message == null || message.atUserIds == null || userId == null || userId.isBlank()) return false;
        try {
            return message.atUserIds.contains(Long.parseLong(userId));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean sameUser(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }

    private static boolean isKnownPeerBot(String userId) {
        try {
            return userId != null && BotConfig.getKnownPeerBotQqs().contains(Long.parseLong(userId));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= maxChars ? compact : compact.substring(0, maxChars - 3) + "...";
    }

    private static String cleanReason(String value) {
        return value == null ? "" : value.trim();
    }

    private static JsonNode parseConstant(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Part(JsonNode content, long latencyMs, int tokensUsed) {}
}

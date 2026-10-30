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
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** Makes one structured group-turn and interjection decision from the public transcript. */
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
            你是糖果熊的群聊兴趣与参与判断器。只判断：在最新一句没有明确把糖果熊当作第二人时，糖果熊是否会因为自己的性格、兴趣、长期事实或当前可确认的知识而自然想参与；不要生成最终回复。
            糖果熊是高中女生，因为身体不好不常去学校，日常有较多时间接触网络和发展兴趣；家境不错，家庭因素影响生活，但不要自行编造具体原因或让她在群聊里主动倾诉。她温柔、略内向，本质幽默；面对熟悉的群友会放松并敢于参与讨论。
            她兴趣广泛，包括文学、网络小说、音乐、艺术、网络热梗、热门话题和有趣的网络内容，也会关注真实出现的游戏及更新。兴趣不等于专业知识或亲身经历；不熟悉的话题可以根据可见内容表现好奇或问一句，不能装作了解。
            只判断：在最新一句没有明确把糖果熊当作第二人时，她是否会自然想参与；不要生成最终回复。关系判断中的 OUTSIDER、没有 @ 糖果熊、没有明确邀请，本身都不能作为拒绝普通群聊插话的理由。
            should_interject 可以在以下任一条件成立且证据足够时为 true：话题与她的兴趣、已知事实或真实好奇有联系；群友分享了她能理解或补充的内容；这是自然的接梗、面向全群的问候、情绪回应或轻松闲聊，她会有真实反应。可以只表达简短兴趣、关心、玩笑或基于当前内容的好奇，不需要先证明能提供专业知识，也不必等群友卡住或求助。
            仍然必须为 false 的情况：明确 @ 或回复其他人且内容明显只对其他人说；私密或敏感的两人对话；群友已经明确拒绝插话；只能靠猜测图片、人物、经历或事实；插话会打断正在进行的严肃交流。intrusion_risk=HIGH 时必须为 false；MEDIUM 可以在回应具体且简短时考虑。
            open_need 可以为空。concrete_contribution 可以是糖果熊此刻真实、简短的兴趣反应、情绪回应、玩笑或基于可见内容的问题；不能只因话题关键词命中而编造知识或经历。轻松、低风险的自然回应可按较低置信度参与，但 contribution_confidence 至少为 0.30。beneficiary_user_id 有明确对象时填写，否则留空。
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
    private static final JsonNode COGNITION_FORMAT = combineFormats();
    private static final String COGNITION_SYSTEM = RELATION_SYSTEM + "\n" + OPPORTUNITY_SYSTEM
            + "\n必须在同一个 JSON 结果中同时回答以上两组字段；关系判断和参与判断要分别基于证据，不得互相代替。"
            + "\n还要判断事实核验需求：只有当未知事实会改变当前回复、话题涉及近期变化、陌生名词或图片身份时才 required=true。"
            + "\n普通接梗、主观感受、关系回应和上下文已经足够的问题，required=false。"
            + "\nsource=KNOWLEDGE 表示先查已有知识库；GROUP_CONTEXT 表示查群消息或历史；WEB 表示公开且可能变化的事实；IMAGE 表示图片身份或内容必须查证。"
            + "\n不要因为关键词陌生就强行要求搜索，也不要把查证计划写进最终群消息。";
    private static final int MAX_RETRIES = 2;

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
        return analyzeAsync(messages, false);
    }

    public CompletableFuture<GroupDialoguePlan> analyzeAsync(
            List<BaiLianService.PublicMessage> messages, boolean activeSession) {
        String transcript = buildTranscript(messages);
        return call(COGNITION_SYSTEM, transcript, COGNITION_FORMAT, "cognition")
                .thenApply(part -> {
            GroupDialoguePlan.Relation normalizedRelation = applyTurnEvidenceConstraint(
                    parseRelation(part.content()), messages, activeSession);
            GroupDialoguePlan plan = GroupDialoguePlan.merge(
                    normalizedRelation, parseOpportunity(part.content()),
                    part.latencyMs(), part.latencyMs(), part.tokensUsed(),
                    parseVerification(part.content()));
            return applyProtocolTarget(plan, messages);
        }).whenComplete((plan, error) -> {
            if (error != null) {
                Throwable cause = rootCause(error);
                logger.warn("group cognition failed stage=analysis type={} reason={}",
                        cause.getClass().getSimpleName(), safeMessage(cause));
            }
        });
    }

    public GroupDialoguePlan analyze(List<BaiLianService.PublicMessage> messages) {
        try {
            return analyzeAsync(messages).get(timeoutMs * (MAX_RETRIES + 1L) + 1_000L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Throwable cause = rootCause(e);
            logger.warn("group cognition failed stage=wait type={} reason={}",
                    cause.getClass().getSimpleName(), safeMessage(cause));
            return null;
        }
    }

    static String buildTranscript(List<BaiLianService.PublicMessage> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode bot = root.putObject("bot_identity");
        bot.put("user_id", String.valueOf(BotConfig.getBotQq()));
        bot.put("nickname", BotConfig.getBotName());
        ObjectNode personality = bot.putObject("personality");
        personality.putArray("traits").add("温柔").add("略微内向").add("幽默")
                .add("面对熟悉的人更放松并敢参与讨论");
        personality.putArray("interests").add("文学与网络小说").add("音乐").add("艺术与插画")
                .add("网络热梗和有趣的事").add("热门话题").add("游戏和游戏更新")
                .add("自然").add("哲学");
        personality.put("background", "高中女生；身体原因不常去学校，有较多时间接触网络和兴趣；家境不错，具体家庭原因未知");
        personality.put("participation_rule", "对感兴趣或觉得有趣的普通群聊可以自然简短参与，不要求先有专业知识或被直接叫到；不熟悉时只表达好奇或询问可见内容，不编造事实和经历");
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
                    llmProtocol, model, systemPrompt, messages, List.of(tool), 700, "");
            body.put("temperature", 0.0);
            body.put("tool_choice", Map.of("type", "tool", "name", toolName));
        } else {
            ObjectNode openAiBody = MAPPER.createObjectNode();
            openAiBody.put("model", model);
            ArrayNode openAiMessages = openAiBody.putArray("messages");
            openAiMessages.addObject().put("role", "system").put("content", systemPrompt);
            openAiMessages.addObject().put("role", "user").put("content", transcript);
            openAiBody.put("temperature", 0.0);
            openAiBody.put("max_tokens", 700);
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

        return sendWithRetry(request, taskName, System.nanoTime(), 0);
    }

    private CompletableFuture<Part> sendWithRetry(HttpRequest request, String taskName,
                                                   long startedNanos, int attempt) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
                    if (response.statusCode() != 200) {
                        throw new CognitionHttpException(response.statusCode());
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
                        throw new CognitionParseException(taskName + " response parse failed", e);
                    }
                }).handle((part, error) -> {
                    if (error == null) return CompletableFuture.completedFuture(part);
                    Throwable cause = rootCause(error);
                    if (!isRetryable(cause) || attempt >= MAX_RETRIES) {
                        return CompletableFuture.<Part>failedFuture(cause);
                    }
                    logger.warn("group cognition retry task={} attempt={} type={} reason={}",
                            taskName, attempt + 1, cause.getClass().getSimpleName(), safeMessage(cause));
                    return CompletableFuture.runAsync(() -> {},
                                    CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS))
                            .thenCompose(ignored -> sendWithRetry(request, taskName, startedNanos, attempt + 1));
                }).thenCompose(stage -> stage);
    }

    private static boolean isRetryable(Throwable error) {
        Throwable cause = rootCause(error);
        if (cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.net.ConnectException
                || cause instanceof java.io.IOException) return true;
        if (cause instanceof CognitionParseException) return true;
        return cause instanceof CognitionHttpException http && (http.statusCode == 429 || http.statusCode >= 500);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "no details";
        String sanitized = message.replaceAll("[\\r\\n\\t]+", " ");
        return sanitized.substring(0, Math.min(240, sanitized.length()));
    }

    private static JsonNode combineFormats() {
        ObjectNode format = MAPPER.createObjectNode();
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", "group_cognition");
        jsonSchema.put("strict", true);
        ObjectNode schema = jsonSchema.putObject("schema");
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        RELATION_FORMAT.path("json_schema").path("schema").path("properties")
                .fields().forEachRemaining(entry -> properties.set(entry.getKey(), entry.getValue().deepCopy()));
        OPPORTUNITY_FORMAT.path("json_schema").path("schema").path("properties")
                .fields().forEachRemaining(entry -> properties.set(entry.getKey(), entry.getValue().deepCopy()));
        ArrayNode required = schema.putArray("required");
        RELATION_FORMAT.path("json_schema").path("schema").path("required")
                .forEach(value -> required.add(value.asText()));
        OPPORTUNITY_FORMAT.path("json_schema").path("schema").path("required")
                .forEach(value -> required.add(value.asText()));
        properties.putObject("verification_required").put("type", "boolean");
        ObjectNode source = properties.putObject("verification_source");
        source.put("type", "string");
        source.putArray("enum").add("NONE").add("KNOWLEDGE")
                .add("GROUP_CONTEXT").add("WEB").add("IMAGE");
        properties.putObject("volatile_fact").put("type", "boolean");
        properties.putObject("verification_reason").put("type", "string");
        required.add("verification_required");
        required.add("verification_source");
        required.add("volatile_fact");
        required.add("verification_reason");
        return format;
    }

    private static final class CognitionHttpException extends RuntimeException {
        private final int statusCode;

        private CognitionHttpException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }

    private static final class CognitionParseException extends RuntimeException {
        private CognitionParseException(String message, Throwable cause) {
            super(message, cause);
        }
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

    static GroupDialoguePlan.VerificationPlan parseVerification(JsonNode node) {
        GroupDialoguePlan.VerificationSource source;
        try {
            source = GroupDialoguePlan.VerificationSource.valueOf(
                    node.path("verification_source").asText("NONE").trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            source = GroupDialoguePlan.VerificationSource.NONE;
        }
        return new GroupDialoguePlan.VerificationPlan(
                node.path("verification_required").asBoolean(false),
                source,
                node.path("volatile_fact").asBoolean(false),
                node.path("verification_reason").asText(""));
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
        return applyTurnEvidenceConstraint(relation, messages, false);
    }

    static GroupDialoguePlan.Relation applyTurnEvidenceConstraint(
            GroupDialoguePlan.Relation relation, List<BaiLianService.PublicMessage> messages,
            boolean activeSession) {
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
        boolean recentBotTurnInActiveSession = activeSession && messages.stream()
                .anyMatch(message -> botId.equals(message.userId)
                        && message.timestamp <= latest.timestamp
                        && latest.timestamp - message.timestamp <= TimeUnit.MINUTES.toMillis(10));
        if (recentBotTurnInActiveSession) return relation;

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

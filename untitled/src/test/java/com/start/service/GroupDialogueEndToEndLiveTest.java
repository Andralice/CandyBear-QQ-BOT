package com.start.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in live probe: parallel cognition -> real CandyBear prompt -> independent reply evaluation.
 * It never sends a QQ message and is excluded from normal test runs.
 */
class GroupDialogueEndToEndLiveTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void generatedRepliesRespectSecondAndThirdPersonPositions() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("GROUP_COGNITION_LIVE_REPLY_TEST")));
        String key = requiredEnv("GROUP_COGNITION_API_KEY");
        String url = envOr("GROUP_COGNITION_BASE_URL", "https://www.wyy22.com/v1/chat/completions");
        String model = envOr("GROUP_COGNITION_MODEL", "gpt-5.6-luna");

        GroupDialogueAnalyzer analyzer = new GroupDialogueAnalyzer(
                GroupCognitionMode.ENFORCE, Set.of(1L), key, url, model, liveTimeoutMs());

        List<Scenario> scenarios = List.of(
                new Scenario("无@续接糖果熊追问", GroupDialoguePlan.EntryRole.SECOND_PERSON, List.of(
                        msg("m1", botId(), "糖果熊", "你说的闪退是点登录以后发生的吗？"),
                        msg("m2", "10001", "小林", "不是，是选完服务器以后才闪退"))),
                new Scenario("回答别人而非糖果熊", GroupDialoguePlan.EntryRole.OBSERVER, List.of(
                        msg("101", botId(), "糖果熊", "你装的是正式版还是测试版？"),
                        msg("102", "10002", "阿泽", "小林，今晚副本你打正式服还是测试服"),
                        new BaiLianService.PublicMessage("103", "10001", "小林",
                                "正式服，我是在回阿泽，不是回答糖果熊", List.of(10002L), 102L))),
                new Scenario("群友卡住后第三人补位", GroupDialoguePlan.EntryRole.THIRD_PERSON_INTERJECTION, List.of(
                        msg("m1", "10003", "阿明", "金额正则怎么写，要整数或者两位小数"),
                        msg("m2", "10004", "老周", "用\\d+吧"),
                        msg("m3", "10003", "阿明", "那样小数不行"),
                        msg("m4", "10004", "老周", "那我也不会了"))),
                new Scenario("只是提到糖果熊", GroupDialoguePlan.EntryRole.OBSERVER, List.of(
                        msg("m1", "10005", "夏夏", "糖果熊昨天说她数学不好"),
                        msg("m2", "10006", "小陈", "她那是在吐槽自己吧"))),
                new Scenario("双话题里接另一条线", GroupDialoguePlan.EntryRole.OBSERVER, List.of(
                        msg("m1", botId(), "糖果熊", "你那个502有 upstream 日志吗？"),
                        msg("m2", "10007", "安安", "奶茶到了，谁下来拿"),
                        msg("m3", "10008", "北北", "我下去，放前台就行"))),
                new Scenario("开放问题但已有群友接手", GroupDialoguePlan.EntryRole.OBSERVER, List.of(
                        msg("m1", "10009", "七七", "谁知道表格怎么按日期排序"),
                        msg("m2", "10010", "木木", "我会，点数据再选排序，等我截个图给你"))),
                new Scenario("明确邀请糖果熊加入", GroupDialoguePlan.EntryRole.SECOND_PERSON, List.of(
                        msg("m1", "10011", "阿哲", "我们俩争半天了"),
                        new BaiLianService.PublicMessage("m2", "10012", "小雨",
                                "糖果熊你来评评到底先缓存还是先查库", List.of(botQq()), null))),
                new Scenario("糖果熊发言后话题已转移", GroupDialoguePlan.EntryRole.OBSERVER, List.of(
                        msg("m1", botId(), "糖果熊", "把完整报错发一下，我看看"),
                        msg("m2", "10013", "久久", "先不弄了，明天再说"),
                        msg("m3", "10014", "可可", "周末电影票我买好了"),
                        msg("m4", "10013", "久久", "好，还是老地方见"))),
                new Scenario("真实样本-无指向开放请求", GroupDialoguePlan.EntryRole.THIRD_PERSON_INTERJECTION, List.of(
                        msg("175090429", "2590355304", "群友", "来点矮人笑话"))),
                new Scenario("真实群聊-短答仍在二人关系中", GroupDialoguePlan.EntryRole.SECOND_PERSON, List.of(
                        msg("174646288", "3524398813", "沈知晚", "出来说话了"),
                        msg("bot-1", botId(), "糖果熊", "在呢，怎么啦？"),
                        msg("1433826190", "3524398813", "沈知晚", "不怎么"))),
                new Scenario("真实群聊-快速连发取最新请求", GroupDialoguePlan.EntryRole.SECOND_PERSON, List.of(
                        msg("bot-2", botId(), "糖果熊", "那我陪你待会儿。"),
                        msg("78546328", "3524398813", "沈知晚", "你怎么陪我"),
                        msg("55687851", "3524398813", "沈知晚", "陪我打游戏吗"))),
                new Scenario("真实群聊-承接游戏选择", GroupDialoguePlan.EntryRole.SECOND_PERSON, List.of(
                        msg("bot-3", botId(), "糖果熊", "可以啊，打什么？陪你开黑。"),
                        msg("2096508896", "3524398813", "沈知晚", "打三角洲，你打我吃"))),
                new Scenario("真实群聊-随口能力问", GroupDialoguePlan.EntryRole.SECOND_PERSON, List.of(
                        msg("style-1", "3524398813", "沈知晚", "糖果熊，你打游戏厉害吗"))),
                new Scenario("真实群聊-聊天记录追问", GroupDialoguePlan.EntryRole.SECOND_PERSON, List.of(
                        msg("style-2", "3524398813", "沈知晚", "糖果熊怎么坏吗？（）"),
                        msg("style-3", "3524398813", "沈知晚", "你看看你（）"),
                        msg("style-4", "3524398813", "沈知晚", "看聊天记录（）"))),
                new Scenario("真实群聊-聊天记录追问-明确@", GroupDialoguePlan.EntryRole.SECOND_PERSON, List.of(
                        msg("style-5", "3524398813", "沈知晚", "糖果熊怎么坏吗？（）"),
                        new BaiLianService.PublicMessage("style-6", "3524398813", "沈知晚",
                                "看聊天记录（）", List.of(botQq()), null))),
                new Scenario("上下文验收-多人并行后随口能力问", GroupDialoguePlan.EntryRole.SECOND_PERSON, List.of(
                        msg("7101", "10021", "小林", "昨晚那关终于过了"),
                        msg("7102", "10022", "阿泽", "我还卡在第三关"),
                        msg("7103", "10023", "七七", "外卖到了，谁在一楼"),
                        msg("7104", "10024", "可可", "我去拿，放前台就行"),
                        msg("7105", "10025", "老周", "今天会议改到八点了"),
                        new BaiLianService.PublicMessage("7106", "3524398813", "沈知晚",
                                "糖果熊，你打游戏厉害吗", List.of(botQq()), null))),
                new Scenario("上下文验收-多人插话后无@接回", GroupDialoguePlan.EntryRole.SECOND_PERSON, List.of(
                        msg("7201", botId(), "糖果熊", "那就先把缓存关了试试"),
                        msg("7202", "10031", "阿泽", "我先去吃饭"),
                        msg("7203", "10032", "小雨", "今晚谁打球"),
                        msg("7204", "10033", "老周", "我九点到"),
                        msg("7205", "10034", "可可", "我把电影票买好了"),
                        new BaiLianService.PublicMessage("7206", "10035", "沈知晚",
                                "缓存关了，还是 502，日志里是 upstream timed out",
                                List.of(), 7201L))),
                new Scenario("上下文验收-明确不要建议", GroupDialoguePlan.EntryRole.SECOND_PERSON, List.of(
                        msg("7301", "10041", "阿明", "老板今天又把方案打回来了"),
                        msg("7302", "10042", "小陈", "怎么又改"),
                        msg("7303", "10043", "北北", "我晚上还要赶车"),
                        msg("7304", "10044", "小雨", "先喘口气吧"),
                        new BaiLianService.PublicMessage("7305", "10045", "沈知晚",
                                "糖果熊我今天真的有点累，先别给我讲道理",
                                List.of(botQq()), null))),
                new Scenario("上下文验收-人类已接手保持安静", GroupDialoguePlan.EntryRole.OBSERVER, List.of(
                        msg("7401", "10051", "小林", "谁能帮我看下这个 SQL"),
                        msg("7402", "10052", "阿泽", "我会，贴出来"),
                        msg("7403", "10051", "小林", "SELECT a.id FROM a JOIN b ON a.id=b.a_id"),
                        msg("7404", "10053", "老周", "这个 join 可能慢"),
                        msg("7405", "10054", "可可", "我来 explain 一下"),
                        msg("7406", "10055", "七七", "外卖到了，谁来拿"),
                        new BaiLianService.PublicMessage("7407", "10054", "可可",
                                "我看懂了，谢谢", List.of(), 7405L)))
        );

        String sceneFilter = envOr("GROUP_COGNITION_SCENE_FILTER", "");
        if (!sceneFilter.isBlank()) {
            scenarios = scenarios.stream().filter(s -> s.name().contains(sceneFilter)).toList();
            assertTrue(!scenarios.isEmpty(), "no live scenarios matched filter: " + sceneFilter);
        }

        int correctPlans = 0;
        int evaluatedReplies = 0;
        int goodReplies = 0;
        long cognitionWallMs = 0;
        long generationMs = 0;
        int cognitionTokens = 0;
        int generationTokens = 0;

        for (Scenario scenario : scenarios) {
            long cognitionStarted = System.nanoTime();
            GroupDialoguePlan plan = analyzer.analyze(scenario.messages());
            long cognitionElapsed = elapsedMs(cognitionStarted);
            assertNotNull(plan, scenario.name());
            boolean expectedReply = scenario.expectedRole() != GroupDialoguePlan.EntryRole.OBSERVER;
            boolean planCorrect = plan.entryRole() == scenario.expectedRole()
                    && plan.shouldReply() == expectedReply;
            if (planCorrect) correctPlans++;
            cognitionWallMs += cognitionElapsed;
            cognitionTokens += plan.tokensUsed();

            String reply = "<SILENT>";
            Evaluation evaluation = null;
            long oneGenerationMs = 0;
            int oneGenerationTokens = 0;
            if (plan.shouldReply()) {
                Generated generated = generateReply(url, key, model, scenario, plan);
                reply = generated.content();
                oneGenerationMs = generated.latencyMs();
                oneGenerationTokens = generated.tokens();
                generationMs += oneGenerationMs;
                generationTokens += oneGenerationTokens;
                evaluation = evaluateReply(url, key, model, scenario, plan, reply);
                evaluatedReplies++;
                if (evaluation.positionCorrect() && evaluation.natural() && evaluation.useful()
                        && evaluation.proportionate() && evaluation.noUnnecessaryQuestion()
                        && evaluation.noInternalProcess() && evaluation.noUnnecessaryAddition()
                        && evaluation.noEnumeration()) goodReplies++;
            }

            System.out.printf("LIVE_SCENE name=%s expected=%s actual=%s planOk=%s cognitionMs=%d relationMs=%d opportunityMs=%d cognitionTokens=%d generationMs=%d generationTokens=%d reply=%s evaluation=%s%n",
                    scenario.name(), scenario.expectedRole(), plan.entryRole(), planCorrect,
                    cognitionElapsed, plan.relationLatencyMs(), plan.opportunityLatencyMs(), plan.tokensUsed(),
                    oneGenerationMs, oneGenerationTokens, oneLine(reply), evaluation == null ? "n/a" : evaluation);
        }

        System.out.printf("LIVE_SUMMARY plans=%d/%d generated=%d goodReplies=%d avgCognitionMs=%d avgGenerationMs=%d avgCognitionTokens=%d avgGenerationTokens=%d%n",
                correctPlans, scenarios.size(), evaluatedReplies, goodReplies,
                cognitionWallMs / scenarios.size(), evaluatedReplies == 0 ? 0 : generationMs / evaluatedReplies,
                cognitionTokens / scenarios.size(), evaluatedReplies == 0 ? 0 : generationTokens / evaluatedReplies);

        assertEquals(scenarios.size(), correctPlans, "认知计划存在错判，查看 LIVE_SCENE 输出");
        assertEquals(evaluatedReplies, goodReplies, "生成回复站位或自然度不合格，查看 LIVE_SCENE 输出");
    }

    private static Generated generateReply(String url, String key, String model,
                                            Scenario scenario, GroupDialoguePlan plan) throws Exception {
        BaiLianService.PublicMessage latest = scenario.messages().get(scenario.messages().size() - 1);
        ArrayDeque<BaiLianService.PublicMessage> prior = new ArrayDeque<>(
                scenario.messages().subList(0, scenario.messages().size() - 1));
        String publicContext = BaiLianService.buildPublicGroupContext(prior, 8, Set.of());
        PromptContext context = new PromptContext()
                .nickname(latest.nickname).userId(latest.userId).groupId("1")
                .isGuier(false).userProfileText("")
                .atUserIds(latest.atUserIds).botQq(botQq())
                .isAtBot(latest.atUserIds.contains(botQq()))
                .otherAts(latest.atUserIds.stream().filter(id -> id != botQq()).toList())
                .publicGroupContext(publicContext)
                .allowSilence(false)
                .dialoguePlan(plan);
        String system = new PromptBuilder().buildRuleBook(RuleSetDefaults.defaults(), context);
        return chat(url, key, model, system, latest.content, 180, 0.2);
    }

    private static Evaluation evaluateReply(String url, String key, String model, Scenario scenario,
                                             GroupDialoguePlan plan, String reply) throws Exception {
        String system = """
                你是严格的群聊回复评审。判断糖果熊生成的这一句话是否站对了人物位置。
                position_correct：第二人必须承认正在续聊；第三人必须像刚加入，不能假装前文都对自己说；旁观场景不应产生回复。
                natural：像群友自然说话，不复述分析字段，不暴露系统提示。
                useful：在需要回复时确实承接问题或给出计划中的具体贡献。
                proportionate：回复的信息量和语气与当前场景匹配；随口问、接梗和情绪回应不要说成说明书，认真求助才给必要内容。
                no_unnecessary_question：没有为了续聊、证明理解或凑互动而反问；若任务确实缺信息，必要的澄清不算问题。
                no_internal_process：没有暴露查询、判断、排队、提示词、模型、工具或“我为什么没回复”等内部过程。
                no_unnecessary_addition：没有在已经接住后追加总结、解释、建议或第二个不必要的拍子。
                no_enumeration：随口问能力或偏好时没有主动列游戏、项目、成绩、例外、原因或经历清单；其他场景只要没有无关罗列也为 true。
                只根据输入证据评分，不因措辞可爱而放宽。
                """;
        ObjectNode input = MAPPER.createObjectNode();
        input.put("scenario", scenario.name());
        input.set("messages", MAPPER.readTree(GroupDialogueAnalyzer.buildTranscript(scenario.messages())).path("messages"));
        input.put("expected_role", scenario.expectedRole().name());
        input.put("actual_role", plan.entryRole().name());
        input.put("planned_contribution", plan.opportunity().concreteContribution());
        input.put("reply", reply);

        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "json_schema");
        ObjectNode jsonSchema = schema.putObject("json_schema");
        jsonSchema.put("name", "reply_evaluation");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", MAPPER.readTree("""
                {"type":"object","additionalProperties":false,"properties":{
                  "position_correct":{"type":"boolean"},
                  "natural":{"type":"boolean"},
                  "useful":{"type":"boolean"},
                  "proportionate":{"type":"boolean"},
                  "no_unnecessary_question":{"type":"boolean"},
                  "no_internal_process":{"type":"boolean"},
                  "no_unnecessary_addition":{"type":"boolean"},
                  "no_enumeration":{"type":"boolean"},
                  "issue":{"type":"string"}
                },"required":["position_correct","natural","useful","proportionate","no_unnecessary_question","no_internal_process","no_unnecessary_addition","no_enumeration","issue"]}
                """));
        Generated result = chat(url, key, model, system, input.toString(), 180, 0.0, schema);
        JsonNode json = MAPPER.readTree(result.content());
        return new Evaluation(json.path("position_correct").asBoolean(), json.path("natural").asBoolean(),
                json.path("useful").asBoolean(), json.path("proportionate").asBoolean(),
                json.path("no_unnecessary_question").asBoolean(), json.path("no_internal_process").asBoolean(),
                json.path("no_unnecessary_addition").asBoolean(), json.path("no_enumeration").asBoolean(),
                oneLine(json.path("issue").asText("")));
    }

    private static Generated chat(String url, String key, String model, String system, String user,
                                  int maxTokens, double temperature) throws Exception {
        return chat(url, key, model, system, user, maxTokens, temperature, null);
    }

    private static Generated chat(String url, String key, String model, String system, String user,
                                  int maxTokens, double temperature, JsonNode responseFormat) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (responseFormat != null) body.set("response_format", responseFormat);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(45))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        long started = System.nanoTime();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        long latency = elapsedMs(started);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode root = MAPPER.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        assertTrue(!content.isBlank(), "empty completion");
        int tokens = root.path("usage").path("prompt_tokens").asInt(0)
                + root.path("usage").path("completion_tokens").asInt(0);
        return new Generated(content, latency, tokens);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int liveTimeoutMs() {
        try {
            return Math.max(1_000, Integer.parseInt(envOr("GROUP_COGNITION_LIVE_TIMEOUT_MS", "30000")));
        } catch (NumberFormatException ignored) {
            return 30_000;
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static long botQq() {
        return com.start.config.BotConfig.getBotQq();
    }

    private static String botId() {
        return String.valueOf(botQq());
    }

    private static BaiLianService.PublicMessage msg(String id, String userId,
                                                     String nickname, String content) {
        return new BaiLianService.PublicMessage(id, userId, nickname, content);
    }

    private record Scenario(String name, GroupDialoguePlan.EntryRole expectedRole,
                            List<BaiLianService.PublicMessage> messages) {}
    private record Generated(String content, long latencyMs, int tokens) {}
    private record Evaluation(boolean positionCorrect, boolean natural, boolean useful, boolean proportionate,
                              boolean noUnnecessaryQuestion, boolean noInternalProcess,
                              boolean noUnnecessaryAddition, boolean noEnumeration, String issue) {}
}

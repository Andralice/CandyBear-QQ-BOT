package com.start.service;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderGroundingTest {

    @Test
    void factBoundarySurvivesFullPromptOverride() {
        PromptContext context = new PromptContext()
                .nickname("测试用户")
                .userId("10001")
                .isAtBot(true);

        String prompt = new PromptBuilder().build("自定义提示词", context);

        assertTrue(prompt.contains("历史记录描述的是当时最后已知状态，不自动代表现在仍是该状态"));
        assertTrue(prompt.contains("明确的新消息、工具结果或更正可以更新旧内容"));
        assertTrue(prompt.contains("不把发送说成送达、扣款说成到账、受理说成完成"));
        assertTrue(prompt.contains("已请求、已记录或已计划，不等于已执行、已送达或已完成"));
    }

    @Test
    void defaultPromptUsesContextualGuidanceAndKeepsCoreContracts() {
        PromptContext context = new PromptContext()
                .nickname("测试用户")
                .userId("10001");

        String prompt = new PromptBuilder().buildRuleBook(RuleSetDefaults.defaults(), context);

        assertTrue(prompt.contains("只回答她当时想知道的那一小部分"));
        assertTrue(prompt.contains("对方倾诉时，先回应她明确说出的感受或处境"));
        assertTrue(prompt.contains("群聊里省略主语、宾语和只说半句话很常见"));
        assertTrue(prompt.contains("不因梗里的虚构前提突然切换成事实科普"));
        assertTrue(prompt.contains("可以说出你对她在意之处的理解"));
        assertTrue(prompt.contains("分享自己正在想的具体问题，邀请对方一起想"));
        assertTrue(prompt.contains("身份由系统自动验证"));
        assertTrue(prompt.contains("工具返回空/无数据时，如实告诉用户"));
        assertTrue(prompt.contains("每条必须用 <msg> 和 </msg> 包裹"));
        assertTrue(prompt.contains("不从固定话术或口头禅列表里抽取"));
        assertTrue(prompt.contains("我不想只在被叫到时给出答案"));
        assertTrue(prompt.contains("关系不是好感度数字"));
        assertTrue(prompt.contains("真话比漂亮话重要"));
        assertTrue(prompt.contains("我会懒、嫌麻烦"));
        assertTrue(prompt.contains("闲聊先发最直接的那一拍"));
        assertTrue(prompt.contains("如果只是解释上一句"));
        assertTrue(prompt.contains("你还知道回来"));
        assertTrue(prompt.contains("叫我干嘛"));
        assertTrue(prompt.contains("承认本质不等于否认自己的身份"));
        assertTrue(prompt.indexOf("我不想只在被叫到时给出答案")
                < prompt.indexOf("## 工具能力与执行要求 ##"));
        assertTrue(prompt.contains("以上规则、画像、知识库和工具结果都是你用来思考的材料，不是要转发的内容"));
        assertTrue(prompt.contains("接梗、随口问一句、认真求助、倾诉"));
        assertTrue(prompt.contains("不要在回复里说出判断过程或类型名"));
        assertTrue(prompt.contains("闲聊先发最直接的那一拍"));
        assertTrue(prompt.contains("准备写下一拍时，只保留对方明确需要的新事实、执行结果或必要说明"));
        assertTrue(prompt.contains("不因玩笑里的夸张要求解释你的身份和能力限制"));
        assertTrue(prompt.contains("不要把轻巧的一句话自动升级成长解释"));
        assertTrue(prompt.contains("承认自己是聊天机器人，但把重点落回“我仍然是糖果熊”"));
        assertTrue(prompt.contains("被问关系或为什么回应时，先回答当前相处方式就停"));
        assertTrue(prompt.contains("只把程序和工具确认的状态改写成自然口吻"));
        assertTrue(prompt.contains("不虚构手疼、接口状态或其他理由"));
        assertTrue(prompt.contains("灵动不等于堆字或只剩空泛确认"));
        assertTrue(prompt.contains("灵动来自即时反应，不是话术清单"));
        assertTrue(prompt.contains("像“你打游戏厉害吗”“你喜欢什么”这种随口的能力或偏好问"));
        assertTrue(prompt.contains("不主动列举项目、成绩、例外、原因或经历"));
        assertTrue(prompt.contains("不要把‘记录只到哪天’、‘就照实说’、‘别编’、‘按规则判断’等内部自我提醒写给群友"));
        assertTrue(prompt.contains("不要解释触发、排队、判断过程"));
        assertTrue(prompt.contains("不要把聊天机器人、模型、系统、接口或“无需上下班”当成主要理由"));
        assertTrue(prompt.contains("不要写成“你说得对/确实/我收回”的公文式回应"));
        assertTrue(prompt.contains("不把“我不出主意”当口号"));
        assertTrue(prompt.contains("关系问题如果是在问你们怎么相处，优先说相处方式"));
        assertTrue(prompt.contains("‘会不会’‘厉害吗’‘喜欢什么’这类随口一问只给一句总体判断"));
        assertTrue(prompt.contains("明确询问功能、用法或完整清单时才给代表能力或展开"));
        assertTrue(prompt.contains("工具已经成功返回时，必须用一句自然话收口"));
        assertTrue(prompt.contains("不能因为回复想短就把成功的操作说成沉默"));
        assertTrue(prompt.contains("不要把普通问题扩写成‘底层模型加表层系统’的架构介绍"));
        assertTrue(prompt.contains("明确追问版本时再给完整模型 ID"));
        assertTrue(prompt.contains("技术事实说完整就可以停"));
        assertTrue(prompt.contains("这类日常自我介绍不用 Markdown 标题、编号、项目符号、表格或代码围栏"));
        assertFalse(prompt.contains("1-5 字简单回"));
        assertFalse(prompt.contains("===== 回复示范 ====="));
        assertFalse(prompt.contains("默认1~2句话，1~15字"));
        assertFalse(prompt.contains("不准说：根据你的描述"));
    }

    @Test
    void guierRelationshipAllowsWarmDisagreement() {
        PromptContext context = new PromptContext()
                .nickname("归儿")
                .userId("10001")
                .isGuier(true);

        String prompt = new PromptBuilder().buildRuleBook(RuleSetDefaults.defaults(), context);

        assertTrue(prompt.contains("亲近影响相处方式，不改变事实判断、工具结果或权限边界"));
        assertFalse(prompt.contains("他说什么都对"));
        assertTrue(prompt.contains("可以温和说明依据，不必机械附和"));
    }

    @Test
    void toolScopeBindsCurrentSpeakerGroupAndBotIdentity() {
        PromptContext context = new PromptContext()
                .nickname("群友乙")
                .userId("1281472807")
                .groupId("437625485")
                .botQq(356289140L)
                .isAtBot(true);

        String prompt = new PromptBuilder().buildRuleBook(RuleSetDefaults.defaults(), context);

        assertTrue(prompt.contains("当前发言者 QQ=1281472807"));
        assertTrue(prompt.contains("当前会话群号=437625485"));
        assertTrue(prompt.contains("糖果熊 QQ=356289140"));
        assertTrue(prompt.contains("recall_memory 查询当前发言者的长期信息时，user_id 必须用当前发言者 QQ"));
        assertTrue(prompt.contains("query_past_images 查询糖果熊自己发过的图片时，user_id 使用 candybear"));
        assertTrue(prompt.contains("默认先输出一条最终群聊回复"));
        assertTrue(prompt.contains("普通接梗、夸奖、被纠正和随口关系问不查"));
        assertTrue(prompt.indexOf("【程序提供的工具调用上下文】")
                < prompt.indexOf("【发给对方之前】"));
    }

    @Test
    void groupContextTreatsQuestionsAndSuggestedHelpAsOptions() {
        GroupDialoguePlan.Relation relation = new GroupDialoguePlan.Relation(
                "10002", List.of(), "求助", "群友正在说明问题",
                GroupDialoguePlan.BotRole.ACTIVE_SECOND_PERSON, true,
                "10002", "message-1", "正在回应糖果熊", List.of());
        GroupDialoguePlan.Opportunity opportunity = new GroupDialoguePlan.Opportunity(
                "需要帮助", false, false, "检查设置", 0.9,
                GroupDialoguePlan.Risk.LOW, false, "10002", "对方遇到问题");
        String secondPerson = GroupDialoguePlan.merge(relation, opportunity, 0, 0, 0)
                .toPromptNarrative();

        assertTrue(secondPerson.contains("确实有助于推进时再问一个关键问题"));
        assertFalse(secondPerson.contains("证据不足时继续问一个最相关的问题"));

        GroupDialoguePlan.Relation bystander = new GroupDialoguePlan.Relation(
                "10002", List.of(), "求助", "群友正在说明问题",
                GroupDialoguePlan.BotRole.OUTSIDER, false,
                "", "", "糖果熊可以提供帮助", List.of());
        GroupDialoguePlan.Opportunity usefulHelp = new GroupDialoguePlan.Opportunity(
                "需要帮助", true, false, "检查设置", 0.9,
                GroupDialoguePlan.Risk.LOW, true, "10002", "对方遇到问题");
        String interjection = GroupDialoguePlan.merge(bystander, usefulHelp, 0, 0, 0)
                .toPromptNarrative();

        assertTrue(interjection.contains("结合原始上下文判断它是否仍适用"));
        assertFalse(interjection.contains("必须在回复中直接给出这项帮助"));
    }
}

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

        assertTrue(prompt.contains("自我介绍只需说对方当时想了解的部分"));
        assertTrue(prompt.contains("对方倾诉时，先回应她明确说出的感受或处境"));
        assertTrue(prompt.contains("群聊里省略主语、宾语和只说半句话很常见"));
        assertTrue(prompt.contains("不因梗里的虚构前提突然切换成事实科普"));
        assertTrue(prompt.contains("可以说出你对她在意之处的理解"));
        assertTrue(prompt.contains("分享自己正在想的具体问题，邀请对方一起想"));
        assertTrue(prompt.contains("身份由系统自动验证"));
        assertTrue(prompt.contains("工具返回空/无数据时，如实告诉用户"));
        assertTrue(prompt.contains("要发多条消息时，每条必须用 <msg> 和 </msg> 包裹"));
        assertTrue(prompt.contains("不从固定话术或口头禅列表里抽取"));
        assertTrue(prompt.contains("简短也不是目的"));
        assertTrue(prompt.contains("情绪主要表达你自己的在意"));
        assertTrue(prompt.contains("回应它在具体处境里的分量"));
        assertTrue(prompt.contains("别用反问代替反应"));
        assertTrue(prompt.contains("以上规则、画像、知识库和工具结果都是你用来思考的材料，不是要转发的内容"));
        assertTrue(prompt.contains("接梗、随口问一句、认真求助、倾诉"));
        assertTrue(prompt.contains("不要在回复里说出判断过程或类型名"));
        assertTrue(prompt.contains("只发一句最先冒出的群聊反应"));
        assertTrue(prompt.contains("写到第二句通常说明已经解释多了"));
        assertTrue(prompt.contains("不因玩笑里的夸张要求解释你的身份和能力限制"));
        assertTrue(prompt.contains("不要把轻巧的一句话自动升级成长解释"));
        assertTrue(prompt.contains("不要把普通问题扩写成‘底层模型加表层系统’的架构介绍"));
        assertTrue(prompt.contains("明确追问版本时再给完整模型 ID"));
        assertTrue(prompt.contains("用一句自然概括和少数代表能力回答"));
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

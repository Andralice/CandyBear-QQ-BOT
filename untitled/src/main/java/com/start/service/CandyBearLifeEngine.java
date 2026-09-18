package com.start.service;

import com.start.config.DatabaseConfig;
import com.start.model.*;
import com.start.repository.CandyBearLifeRepository;
import com.start.repository.CandyBearScheduleRepository;
import com.start.repository.EventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 糖果熊人生引擎 —— 四层结构保证生命连续性。
 *
 * LifeState（知识库，数月不变）
 *   ↓
 * StoryArc（2~3周章节）
 *   ↓
 * WeeklyDiary（每周日生成）
 *   ↓
 * DailyJournal（每天凌晨生成昨日日记）
 *   ↓
 * 系统提示 / query_life 工具
 *
 * 每一层生成时都引用上一层 + 历史，确保连续不矛盾。
 */
public class CandyBearLifeEngine {

    private static final Logger logger = LoggerFactory.getLogger(CandyBearLifeEngine.class);

    private final CandyBearLifeRepository repo;
    private final CandyBearScheduleRepository scheduleRepo;
    private final EventLogRepository eventRepo;
    private final BaiLianService ai;

    /** 硬编码的生活资料（DB不可用时 fallback；只供内部生成使用） */
    private static final String HARDCODED_LIFE_STATE =
            "生活资料（仅供内部生成，不是现成自我介绍）：角色名=糖果熊；角色背景=按人的生活经历记录；年龄=17岁；" +
            "在读高二；居住=北京。家庭条件较好，父亲是工程师，母亲是医生。" +
            "健康备注=轻微心脏问题，通常每周到校2~3天，学校对出勤宽容。" +
            "朋友参考=同桌小雨（学习好）、隔壁班阿乐（擅长打球）。" +
            "兴趣参考=三角洲行动、洛克王国、崩铁、追番、画画、看小说。" +
            "在线习惯=经常使用手机聊天；性格倾向=有活力但偶尔懒，数学相对弱，语文和英语较好。" +
            "人格动力=上学不规律让她有点怕错过共同生活，因此珍惜持续的关系和被记住的小事；嘴上常说得轻，真的在意会记很久。";

    public CandyBearLifeEngine(CandyBearLifeRepository repo, CandyBearScheduleRepository scheduleRepo, BaiLianService ai) {
        this.repo = repo;
        this.scheduleRepo = scheduleRepo;
        this.eventRepo = new EventLogRepository(DatabaseConfig.getDataSource());
        this.ai = ai;
    }

    /** 启动时检查并补全缺失的日记和章节 */
    public void onStartup() {
        // 0. 初始化 LifeState（表空则插入默认值）
        try {
            repo.initDefaultLifeState();
        } catch (Exception e) {
            logger.error("[LifeEngine] LifeState初始化失败: " + e.getMessage());
        }

        // 1. 初始化 story arc（重试 3 次，失败也继续）
        try {
            if (repo.findActiveArc().isEmpty()) {
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        generateNewArc(null);
                        break;
                    } catch (Exception e) {
                        if (attempt == 2) logger.error("[LifeEngine] arc 生成失败(重试3次): " + e.getMessage());
                        else Thread.sleep(2000);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[LifeEngine] arc 检查失败: " + e.getMessage());
        }

        // 2. 补全最近 3 天日记（逐天生成，失败不阻塞后续）
        LocalDate today = LocalDate.now();
        for (int i = 3; i >= 1; i--) {
            LocalDate d = today.minusDays(i);
            try {
                if (!repo.hasJournal(d)) {
                    generateDailyJournal(d);
                }
            } catch (Exception e) {
                logger.error("[LifeEngine] 日记生成失败 " + d + ": " + e.getMessage());
            }
        }

        // 3. 补全本周周记
        try {
            LocalDate thisMon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (!repo.hasWeeklyDiary(thisMon)) {
                generateWeeklyDiary(thisMon);
            }
        } catch (Exception e) {
            logger.error("[LifeEngine] 周记生成失败: " + e.getMessage());
        }

        // 4. 生成本周日程（缺失时，重试3次）
        try {
            LocalDate thisMon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (!scheduleRepo.hasSchedule(thisMon)) {
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        generateWeeklySchedule(thisMon);
                        break;
                    } catch (Exception e) {
                        if (attempt == 2) logger.error("[LifeEngine] 本周日程生成失败(重试3次): " + e.getMessage());
                        else Thread.sleep(2000);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[LifeEngine] 日程检查失败: " + e.getMessage());
        }

        // 5. 预生成下周日程
        try {
            LocalDate nextMon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(7);
            if (!scheduleRepo.hasSchedule(nextMon)) {
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        generateWeeklySchedule(nextMon);
                        break;
                    } catch (Exception e) {
                        if (attempt == 2) logger.error("[LifeEngine] 下周日程生成失败(重试3次): " + e.getMessage());
                        else Thread.sleep(2000);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[LifeEngine] 下周日程检查失败: " + e.getMessage());
        }
    }

    /** 每天凌晨调用：把昨天的日程写入事件流，然后生成日记 */
    public void dailyTick() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        // 把昨天的日程活动写入事件流（给日记提供素材）
        try {
            if (!eventRepo.hasEvents(yesterday)) {
                writeScheduleEvents(yesterday);
            }
        } catch (Exception e) {
            logger.error("[LifeEngine] dailyTick 写事件: " + e.getMessage());
        }

        // 昨天日记
        try {
            if (!repo.hasJournal(yesterday)) {
                generateDailyJournal(yesterday);
            }
        } catch (Exception e) {
            logger.error("[LifeEngine] dailyTick 日记: " + e.getMessage());
        }

        // 周日生成周记 + 下周日程
        try {
            if (LocalDate.now().getDayOfWeek() == DayOfWeek.SUNDAY) {
                LocalDate thisMon = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                if (!repo.hasWeeklyDiary(thisMon)) {
                    generateWeeklyDiary(thisMon);
                }
                // 生成下周日程
                LocalDate nextMon = thisMon.plusDays(7);
                if (!scheduleRepo.hasSchedule(nextMon)) {
                    generateWeeklySchedule(nextMon);
                }
            }
        } catch (Exception e) {
            logger.error("[LifeEngine] dailyTick 周记/日程: " + e.getMessage());
        }

        // story arc 到期前 3 天生成新章节
        try {
            Optional<CandyBearStoryArc> arc = repo.findActiveArc();
            if (arc.isPresent() && !arc.get().getEndDate().isAfter(LocalDate.now().plusDays(3))) {
                generateNewArc(arc.get());
            }
        } catch (Exception e) {
            logger.error("[LifeEngine] dailyTick arc: " + e.getMessage());
        }
    }

    /** 把某天的日程活动写入事件流，作为日记的事实素材 */
    private void writeScheduleEvents(LocalDate date) {
        try {
            List<CandyBearSchedule> daySchedule = scheduleRepo.findToday(date);
            if (daySchedule.isEmpty()) return;

            for (CandyBearSchedule s : daySchedule) {
                CandyBearEventLog e = new CandyBearEventLog();
                // 用日程的开始时间作为事件时间
                e.setEventTime(date.atTime(s.getStartTime()));
                e.setEventDate(date);
                e.setEventType("SCHEDULE");
                e.setSummary(s.getActivity());
                e.setEmotion(s.getMood() != null ? s.getMood() : "");
                // 把 mood 映射为 emotion_impact
                e.setEmotionImpact(moodToImpact(s.getMood()));
                eventRepo.insert(e);
            }
        } catch (Exception e) {
            logger.error("[LifeEngine] writeScheduleEvents 失败 " + date + ": " + e.getMessage());
        }
    }

    /** 把心情词映射为 -5~+5 的影响值 */
    private int moodToImpact(String mood) {
        if (mood == null) return 0;
        return switch (mood) {
            case "超开心", "开心", "舒服", "悠闲", "充实", "感动", "兴奋" -> 2;
            case "还行", "放松", "平静" -> 0;
            case "累", "无聊", "慵懒", "困" -> -1;
            case "痛苦", "emo", "烦躁", "焦虑", "又气又爽" -> -2;
            default -> 0;
        };
    }

    // ===== 生成 Story Arc =====
    private void generateNewArc(CandyBearStoryArc previousArc) throws Exception {
        String prevContext = "";
        if (previousArc != null) {
            prevContext = "上一个章节：【" + previousArc.getArcName() + "】" + previousArc.getSummary() + "\n";
            repo.deactivateArc(previousArc.getId());
        }

        // 加入最近日记作为参考
        List<CandyBearDailyJournal> recentJournals = repo.findRecentJournals(7);
        String journalCtx = recentJournals.stream()
                .map(j -> j.getJournalDate() + ": " + j.getSummary())
                .collect(Collectors.joining("\n"));

        String prompt = buildLifeStatePrompt() + "\n\n" +
                prevContext +
                "最近7天日记：\n" + journalCtx + "\n\n" +
                "请为糖果熊生成接下来2~3周的生活章节。格式：\n" +
                "章节名：（如：月考冲刺、暑假前放松、社团招新）\n" +
                "起止日期：（如 2026-06-05 到 2026-06-20）\n" +
                "章节概述：（2~3句话，描述这个时期的主题和状态）\n" +
                "关键事件：（3~5个，JSON数组，如[\"模拟考\",\"被老师约谈\",\"跟小雨吵架\"]）\n" +
                "情绪趋势：（如：焦虑→好转，平淡，开心→紧张）\n\n" +
                "同时，基于新章节，给出人生状态的更新建议（没变的项留空，只填要改的）：\n" +
                "---状态更新---\n" +
                "近期烦恼：xxx\n" +
                "当前目标：xxx\n" +
                "朋友：xxx\n" +
                "爱好：xxx\n" +
                "--------------------------------\n\n" +
                "要求：延续之前的日记内容，不要矛盾。糖果熊的生活轻松自由但也要有真实少女的烦恼和快乐。";

        String response = ai.generateRaw(prompt);
        if (response == null || response.isBlank()) {
            logger.error("[LifeEngine] arc 生成失败: AI 返回为空");
            return;
        }
        CandyBearStoryArc arc = parseArcResponse(response);
        repo.insertArc(arc);

        // 同步更新人生状态
        parseAndApplyLifeStateUpdates(response);
    }

    private CandyBearStoryArc parseArcResponse(String resp) {
        CandyBearStoryArc a = new CandyBearStoryArc();
        a.setArcName(extractLine(resp, "章节名"));
        String dates = extractLine(resp, "起止日期");
        if (dates.contains("到")) {
            String[] parts = dates.split("到");
            try { a.setStartDate(LocalDate.parse(parts[0].trim())); } catch (Exception e) { a.setStartDate(LocalDate.now()); }
            try { a.setEndDate(LocalDate.parse(parts[1].trim())); } catch (Exception e) { a.setEndDate(LocalDate.now().plusDays(14)); }
        }
        a.setSummary(extractLine(resp, "章节概述"));
        a.setMajorEvents(extractLine(resp, "关键事件"));
        a.setMoodTrend(extractLine(resp, "情绪趋势"));
        a.setActive(true);
        return a;
    }

    // ===== 生成 Weekly Diary =====
    private void generateWeeklyDiary(LocalDate weekStart) throws Exception {
        LocalDate weekEnd = weekStart.plusDays(6);

        // 本周日记
        List<CandyBearDailyJournal> weekJournals = repo.findRecentJournals(7);
        String journalCtx = weekJournals.stream()
                .filter(j -> !j.getJournalDate().isBefore(weekStart) && !j.getJournalDate().isAfter(weekEnd))
                .map(j -> j.getJournalDate() + ": " + j.getSummary() + "（" + j.getEmotion() + "）")
                .collect(Collectors.joining("\n"));

        Optional<CandyBearWeeklyDiary> prevWeek = repo.findLatestWeek();
        String prevCtx = prevWeek.map(w -> "上周总结：" + w.getSummary() + "\n上周计划：" + w.getNextWeekPlan()).orElse("");

        Optional<CandyBearStoryArc> arc = repo.findActiveArc();
        String arcCtx = arc.map(a -> "当前章节：【" + a.getArcName() + "】" + a.getSummary()).orElse("");

        String prompt = buildLifeStatePrompt() + "\n\n" + buildPersonaStyleGuide() + "\n\n" + arcCtx + "\n" + prevCtx + "\n\n" +
                "本周（" + weekStart + "~" + weekEnd + "）日记：\n" + (journalCtx.isEmpty() ? "（暂无）" : journalCtx) + "\n\n" +
                "请为糖果熊写一篇本周周记。格式：\n" +
                "本周小结：（先说这周上了几天课——从日记里数。然后概括这周做了什么、感受如何。5~8句话，口语化少女周记。）\n" +
                "重要事件：（JSON数组，每条要具体有细节，如[\"数学小测居然及格了！老师表扬了\",\"追完了XX番最后一集爆哭\",\"周末跟小雨去了朝阳大悦城喝多肉葡萄\",\"三角洲排位从白银三掉到青铜一心态崩了\"]）\n" +
                "本周情绪：（如：整体还行但周三emo了/这周超充实/有点累但开心的）\n" +
                "下周计划：（2~4件事，每条要具体，如\"把数学卷子做完不然老师要请家长了\"\"约小雨周末去798看展\"\"三角洲冲回白银\"）\n\n" +
                "要求：延续之前的日记内容，不要矛盾。语气像17岁女孩，可以吐槽、可以开心、可以emo。要有具体数字和细节，不要笼统概括。";

        String response = ai.generateRaw(prompt);
        if (response == null || response.isBlank()) {
            logger.error("[LifeEngine] 周记生成失败: AI 返回为空");
            return;
        }
        CandyBearWeeklyDiary diary = parseWeekResponse(response, weekStart, weekEnd);
        repo.insertWeeklyDiary(diary);
    }

    private CandyBearWeeklyDiary parseWeekResponse(String resp, LocalDate start, LocalDate end) {
        CandyBearWeeklyDiary d = new CandyBearWeeklyDiary();
        d.setWeekStart(start); d.setWeekEnd(end);
        d.setSummary(extractLine(resp, "本周小结"));
        if (d.getSummary().isEmpty()) d.setSummary(extractLine(resp, "本周总结")); // 兼容旧格式
        d.setMajorEvents(extractLine(resp, "重要事件"));
        d.setEmotion(extractLine(resp, "本周情绪"));
        d.setNextWeekPlan(extractLine(resp, "下周计划"));
        return d;
    }

    // ===== 生成 Daily Journal =====
    private void generateDailyJournal(LocalDate date) throws Exception {
        Optional<CandyBearDailyJournal> yesterday = repo.findJournal(date.minusDays(1));
        String yesterdayCtx = yesterday.map(j -> "昨天日记：" + j.getSummary()).orElse("");

        Optional<CandyBearStoryArc> arc = repo.findActiveArc();
        String arcCtx = arc.map(a -> "当前章节：【" + a.getArcName() + "】" + a.getSummary()).orElse("");

        // 读取当天事件流作为主要素材
        String eventLogCtx = "";
        try {
            List<CandyBearEventLog> events = eventRepo.findByDate(date);
            if (!events.isEmpty()) {
                StringBuilder sb = new StringBuilder("今天实际发生的事件：\n");
                for (CandyBearEventLog ev : events) {
                    String timeStr = ev.getEventTime() != null
                            ? ev.getEventTime().toLocalTime().toString().substring(0, 5) : "";
                    sb.append(timeStr).append(" [").append(ev.getEventType()).append("] ")
                      .append(ev.getSummary());
                    if (ev.getEmotion() != null && !ev.getEmotion().isEmpty()) {
                        sb.append("（").append(ev.getEmotion()).append("）");
                    }
                    sb.append("\n");
                }
                eventLogCtx = sb.toString();
            }
        } catch (Exception e) {
            // 事件流是可选上下文
        }

        // 日程作为辅助上下文（有事件流时只用于对比，无事件流时作为 grounding）
        String scheduleCtx = "";
        boolean hasEvents = !eventLogCtx.isEmpty();
        try {
            List<CandyBearSchedule> daySchedule = scheduleRepo.findToday(date);
            if (!daySchedule.isEmpty()) {
                StringBuilder sb = new StringBuilder(hasEvents ? "今天原定日程（可对比实际执行情况）：\n" : "今天日程：\n");
                for (CandyBearSchedule s : daySchedule) {
                    sb.append(s.getTimeSlot()).append(" ").append(s.getStartTime()).append("~").append(s.getEndTime())
                      .append(" ").append(s.getActivity()).append("（").append(s.getLocation()).append("）")
                      .append(s.isSchoolDay() ? " [上学日]" : "")
                      .append("\n");
                }
                scheduleCtx = sb.toString();
            }
        } catch (Exception e) {
            // 日程是可选上下文
        }

        String grounding = hasEvents
                ? "以下是今天实际发生的事件记录，请根据这些事件总结日记。不要凭空编造没有发生的事。日程可作参考对比。"
                : "请根据下面的日程和人设，为糖果熊写今天的日记。";
        String prompt = buildLifeStatePrompt() + "\n\n" + buildPersonaStyleGuide() + "\n\n" + arcCtx + "\n" + yesterdayCtx + "\n" + eventLogCtx + "\n" + scheduleCtx + "\n" +
                "今天是" + date + " " + getWeekday(date) + "。\n" +
                grounding + "\n\n" +
                "请为糖果熊写今天（" + date + "）的日记。格式：\n" +
                "今日摘要：（5~8句话，口语化少女日记。要有具体细节——上了什么课、跟谁聊了什么、打了什么游戏打到什么段位、看了什么番、吃了什么、吐槽了什么，不要写笼统的概括。像真实的17岁女孩在记事，有流水账感也没关系。）\n" +
                "重要事件：（JSON数组，每条要具体，如[\"数学课被老师点名做题居然做对了\",\"跟小雨在食堂聊到隔壁班的八卦\",\"三角洲排位三连跪气死\",\"妈妈晚上打电话问学习\"]）\n" +
                "今日情绪：（如：开心/emo/累/无聊/充实/焦虑/兴奋/慵懒/烦躁）\n\n" +
                "要求：\n" +
                "1. 延续昨天的日记内容，不要矛盾。\n" +
                "2. 日记要具体有内容，不要只写一两句笼统的感受。\n" +
                (hasEvents
                    ? "3. 以上面的事件流为主要素材，日程只作参考。有事件就用事件，不要编造。\n"
                    : "3. 如果上面给出了今天的日程，细致地按日程写出来。\n")
                + "4. 如果没有日程也没有事件，根据人物状态和当前章节自行发挥。\n" +
                "5. 语气像17岁女孩写日记——可以吐槽、可以emo、可以碎碎念，不要AI腔。\n" +
                (hasEvents
                    ? "6. 上学日与否以事件为准；无事件时以日程为准。\n"
                    : "6. 上学日与否以日程为准；无日程时上学日概率约40%（一周2~3天）。\n");

        String response = ai.generateRaw(prompt);
        if (response == null || response.isBlank()) {
            logger.error("[LifeEngine] 日记生成失败 " + date + ": AI 返回为空");
            return;
        }
        CandyBearDailyJournal journal = parseJournalResponse(response, date);
        repo.insertJournal(journal);
    }

    private CandyBearDailyJournal parseJournalResponse(String resp, LocalDate date) {
        CandyBearDailyJournal j = new CandyBearDailyJournal();
        j.setJournalDate(date);
        j.setSummary(extractLine(resp, "今日摘要"));
        j.setImportantEvents(extractLine(resp, "重要事件"));
        j.setEmotion(extractLine(resp, "今日情绪"));
        return j;
    }

    // ===== 查询接口（供工具使用） =====
    public String queryLifeContext() {
        try {
            StringBuilder sb = new StringBuilder();
            Optional<CandyBearStoryArc> arc = repo.findActiveArc();
            if (arc.isPresent()) {
                sb.append("【当前章节】").append(arc.get().getArcName())
                  .append("（").append(arc.get().getStartDate()).append("~").append(arc.get().getEndDate()).append("）")
                  .append("\n").append(arc.get().getSummary()).append("\n");
            }

            List<CandyBearDailyJournal> journals = repo.findRecentJournals(7);
            if (!journals.isEmpty()) {
                sb.append("\n【最近7天日记】\n");
                // 反转成正序
                for (int i = journals.size() - 1; i >= 0; i--) {
                    CandyBearDailyJournal j = journals.get(i);
                    sb.append(j.getJournalDate()).append(" ").append(getWeekday(j.getJournalDate()))
                      .append("：").append(j.getSummary())
                      .append("（").append(j.getEmotion()).append("）\n");
                }
            }

            Optional<CandyBearWeeklyDiary> week = repo.findLatestWeek();
            if (week.isPresent()) {
                sb.append("\n【本周计划】").append(week.get().getNextWeekPlan());
            }

            // 今日事件流（真实发生的）
            try {
                List<CandyBearEventLog> todayEvents = eventRepo.findByDate(LocalDate.now());
                if (!todayEvents.isEmpty()) {
                    sb.append("\n【今日事件】\n");
                    for (CandyBearEventLog ev : todayEvents) {
                        String timeStr = ev.getEventTime() != null
                                ? ev.getEventTime().toLocalTime().toString().substring(0, 5) : "";
                        sb.append(timeStr).append(" [").append(ev.getEventType()).append("] ")
                          .append(ev.getSummary());
                        if (ev.getEmotion() != null && !ev.getEmotion().isEmpty()) {
                            sb.append("（").append(ev.getEmotion()).append("）");
                        }
                        sb.append("\n");
                    }
                }
            } catch (Exception e) { /* 可选 */ }

            // 近日事件（最近3天，给连续性。跳过今天，因为上面已有"今日事件"）
            try {
                List<CandyBearEventLog> recentEvents = eventRepo.findRecent(4);
                if (!recentEvents.isEmpty()) {
                    sb.append("\n【近日事件】\n");
                    LocalDate today = LocalDate.now();
                    LocalDate lastDate = null;
                    for (CandyBearEventLog ev : recentEvents) {
                        if (ev.getEventDate().equals(today)) continue; // 跳过今天
                        if (lastDate == null || !lastDate.equals(ev.getEventDate())) {
                            lastDate = ev.getEventDate();
                            sb.append(lastDate).append(" ").append(getWeekday(lastDate)).append("：\n");
                        }
                        sb.append("  - ").append(ev.getSummary());
                        if (ev.getEmotion() != null && !ev.getEmotion().isEmpty()) {
                            sb.append("（").append(ev.getEmotion()).append("）");
                        }
                        sb.append("\n");
                    }
                }
            } catch (Exception e) { /* 可选 */ }

            // 今日日程
            try {
                List<CandyBearSchedule> todaySchedule = scheduleRepo.findToday(LocalDate.now());
                if (!todaySchedule.isEmpty()) {
                    sb.append("\n\n【今日日程】\n");
                    for (CandyBearSchedule s : todaySchedule) {
                        sb.append(s.getStartTime()).append("~").append(s.getEndTime())
                          .append(" ").append(s.getActivity()).append("（").append(s.getLocation()).append("）")
                          .append("[").append(s.getMood()).append("]\n");
                    }
                }
            } catch (Exception e) { /* 可选 */ }

            // 当前正在做什么
            try {
                CandyBearSchedule current = scheduleRepo.findCurrent(LocalDate.now(), LocalTime.now().withSecond(0).withNano(0));
                if (current != null) {
                    sb.append("\n【现在正在做】").append(LocalTime.now().withSecond(0).withNano(0))
                      .append("：").append(current.getActivity()).append("（").append(current.getLocation()).append("）")
                      .append("[").append(current.getMood()).append("]");
                }
            } catch (Exception e) { /* 可选 */ }

            return sb.toString().trim();
        } catch (Exception e) {
            return "查询失败: " + e.getMessage();
        }
    }

    // ===== helpers =====
    /** 从 AI 回复中提取指定 key 对应的值。容错：中英文冒号、跨行、空白。 */
    private String extractLine(String text, String key) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.isEmpty()) continue;
            // 匹配 "key：" 或 "key:" 或 "key- " 或 "key: " 等各种写法
            String normalized = l.replace("：", ":").replace(": ", ":").replace(" :", ":");
            String[] keyVariants = {key + ":", key.replace("：", ":") + ":", key + "："};
            boolean matched = false;
            for (String kv : keyVariants) {
                String kvn = kv.replace("：", ":");
                if (normalized.startsWith(kvn)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) continue;

            // 提取冒号后的内容
            int colonIdx = -1;
            for (int ci : new int[]{l.indexOf("："), l.indexOf(":")}) {
                if (ci > 0 && (colonIdx < 0 || ci < colonIdx)) colonIdx = ci;
            }
            if (colonIdx < 0) continue;

            StringBuilder val = new StringBuilder(l.substring(colonIdx + 1).trim());
            // 如果当前行内容很短，可能是多行值，继续拼后续行
            int j = i + 1;
            while (j < lines.length && val.length() < 30 && !lines[j].trim().contains("：") && !lines[j].trim().contains(":")) {
                String next = lines[j].trim();
                if (!next.isEmpty() && !next.startsWith("-") && !next.startsWith("*")) {
                    val.append(next);
                }
                j++;
            }
            return val.toString().trim();
        }
        return "";
    }

    private String getWeekday(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case MONDAY -> "周一"; case TUESDAY -> "周二"; case WEDNESDAY -> "周三";
            case THURSDAY -> "周四"; case FRIDAY -> "周五"; case SATURDAY -> "周六"; case SUNDAY -> "周日";
        };
    }

    // ===== LifeState helpers =====

    /** 从 DB 读取 LifeState 拼成 prompt，DB 不可用时 fallback 到硬编码。 */
    private String buildLifeStatePrompt() {
        try {
            Optional<CandyBearLifeState> state = repo.findLifeState();
            if (state.isPresent()) {
                CandyBearLifeState s = state.get();
                StringBuilder sb = new StringBuilder("生活资料（仅供内部生成，不是现成自我介绍）：");
                sb.append("角色名=糖果熊；角色背景=按人的生活经历记录；年龄=17岁；");
                if (s.getSchool() != null && !s.getSchool().isEmpty()) {
                    sb.append("学校=").append(s.getSchool()).append("；");
                }
                sb.append("年级=").append(s.getGrade()).append("；居住=").append(s.getLocation()).append("。 ");
                sb.append("家庭条件较好，父亲是工程师，母亲是医生；健康备注=")
                        .append(s.getHealthNote()).append("；通常每周到校2~3天，学校对出勤宽容。");
                sb.append("朋友参考=").append(formatFriends(s.getFriends())).append("。");
                if (s.getHobbies() != null && !s.getHobbies().isEmpty()) {
                    sb.append("兴趣参考=").append(s.getHobbies()).append("。");
                }
                if (s.getRecentProblem() != null && !s.getRecentProblem().isEmpty()) {
                    sb.append("近期状态=").append(s.getRecentProblem()).append("。");
                }
                if (s.getCurrentGoal() != null && !s.getCurrentGoal().isEmpty()) {
                    sb.append("当前目标=").append(s.getCurrentGoal()).append("。");
                }
                sb.append("在线习惯=经常使用手机聊天；性格倾向=有活力但偶尔懒，数学相对弱，语文和英语较好。 ");
                sb.append("资料用于生成生活记录，不要求在对话中逐项复述。");
                return sb.toString();
            }
        } catch (Exception e) {
            logger.error("[LifeEngine] LifeState读取失败，使用默认: " + e.getMessage());
        }
        return HARDCODED_LIFE_STATE;
    }

    /** 把逗号分隔的朋友列表格式化为可读文本 */
    private String formatFriends(String friends) {
        if (friends == null || friends.isEmpty()) return "小雨,阿乐";
        String[] arr = friends.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0 && i == arr.length - 1) sb.append("和");
            else if (i > 0) sb.append("、");
            sb.append(arr[i].trim());
        }
        return sb.toString();
    }

    /** 糖果熊的语气风格指南，与聊天系统提示保持一致 */
    private String buildPersonaStyleGuide() {
        return """
                ===== 糖果熊的语气风格（务必遵循）=====
                上学不规律让糖果熊有点怕错过共同生活，所以她珍惜持续的关系和被记住的小事。她嘴上常把事情说得轻，真的在意会记很久。
                她会懒、嫌麻烦、吐槽作业和数学，也会护短、闹一点别扭；发现自己误会时最终会改口。她不喜欢因心脏问题被当成易碎品，但真不舒服时不会逞强。
                她会留意云、夜间便利店、票根一类生活碎片，浪漫但不刻意抒情。家境好是背景，不是评价别人的尺度。

                日记规则：
                - 延续尚未结束的作业、到校计划、朋友和情绪，不每天随机编一件少女趣事
                - 自然记录当天，情绪可以开心、烦、低落或平淡，不把每件事总结成意义
                - 不预设口头禅，不从固定词表抽句子；没有真实依据时不补造过去经历
                - 禁止颜文字（qwq、QAQ、TAT、Orz等）
                """;
    }

    /** 从文本中提取开始和结束标记之间的内容块 */
    private String extractBlock(String text, String startMarker, String endMarker) {
        if (text == null) return "";
        int start = text.indexOf(startMarker);
        if (start < 0) return "";
        start += startMarker.length();
        int end = endMarker != null ? text.indexOf(endMarker, start) : text.length();
        if (end < 0) end = text.length();
        return text.substring(start, end).trim();
    }

    /** 从 arc 生成的 AI 回复中解析并应用 LifeState 更新 */
    private void parseAndApplyLifeStateUpdates(String response) {
        try {
            String block = extractBlock(response, "---状态更新---", "-------------------------------");
            if (block.isEmpty()) return;

            CandyBearLifeState state = repo.findLifeState().orElse(new CandyBearLifeState());
            state.setUpdatedAt(LocalDate.now());

            String problem = extractLine(block, "近期烦恼");
            if (!problem.isEmpty()) state.setRecentProblem(problem);
            String goal = extractLine(block, "当前目标");
            if (!goal.isEmpty()) state.setCurrentGoal(goal);
            String friends = extractLine(block, "朋友");
            if (!friends.isEmpty()) state.setFriends(friends);
            String hobbies = extractLine(block, "爱好");
            if (!hobbies.isEmpty()) state.setHobbies(hobbies);
            String location = extractLine(block, "地点");
            if (!location.isEmpty()) state.setLocation(location);
            String health = extractLine(block, "健康");
            if (!health.isEmpty()) state.setHealthNote(health);

            repo.upsertLifeState(state);
        } catch (Exception e) {
            logger.error("[LifeEngine] LifeState更新失败: " + e.getMessage());
        }
    }

    // ===== 日程生成 =====

    /** AI 生成一周日程（7天 × 5时段 = 35条），竖线分隔格式 */
    private void generateWeeklySchedule(LocalDate weekStart) throws Exception {
        LocalDate weekEnd = weekStart.plusDays(6);

        String lifeState = buildLifeStatePrompt();
        Optional<CandyBearStoryArc> arc = repo.findActiveArc();
        String arcCtx = arc.map(a -> "当前章节：【" + a.getArcName() + "】" + a.getSummary()).orElse("");

        String prompt = lifeState + "\n\n" + buildPersonaStyleGuide() + "\n\n" + arcCtx + "\n\n" +
                "请为糖果熊生成" + weekStart + "~" + weekEnd + "这一周的详细日程安排。\n\n" +
                "糖果熊是17岁高二学生，一周去学校2~3天（约40%天数），周末肯定不去。\n" +
                "每天6个时段：morning / lunch / afternoon / evening / night / late_night\n\n" +
                "按以下格式输出（每行一条，竖线|分隔，共42行）：\n" +
                "日期|时段|开始时间|结束时间|活动|地点|心情|是否上学日\n\n" +
                "===== 上学日示例（活动要具体，写出上的什么课）=====\n" +
                weekStart + "|morning|07:30|12:00|早读+语文课+数学课+英语课|教室|还行|true\n" +
                weekStart + "|lunch|12:00|13:30|和小雨去食堂吃饭，顺便吐槽数学好难|学校食堂|开心|true\n" +
                weekStart + "|afternoon|13:30|17:00|物理实验课+自习写数学卷子|教室/实验室|累|true\n" +
                weekStart + "|evening|17:00|19:00|放学回家路上听歌，到家吃饭|地铁/家里|放松|true\n" +
                weekStart + "|night|19:00|22:00|做数学作业+背英语单词|家里书桌|痛苦|true\n" +
                weekStart + "|late_night|22:00|23:30|躺在床上打两把三角洲，刷微博|床上|超开心|true\n\n" +
                "===== 非上学日示例 =====\n" +
                (weekStart.plusDays(1)) + "|morning|10:00|12:00|睡到自然醒，躺床上刷B站追新番|床上|舒服|false\n" +
                (weekStart.plusDays(1)) + "|lunch|12:00|13:00|叫外卖，边吃边看视频|家里餐桌|悠闲|false\n" +
                (weekStart.plusDays(1)) + "|afternoon|13:00|17:00|肝三角洲排位，顺便挂洛克王国日常|电脑前|超开心|false\n" +
                (weekStart.plusDays(1)) + "|evening|17:00|19:00|出门跟小雨逛商场，喝多肉葡萄|朝阳大悦城|开心|false\n" +
                (weekStart.plusDays(1)) + "|night|19:00|22:00|回家追番+画点小画|家里|充实|false\n" +
                (weekStart.plusDays(1)) + "|late_night|22:00|01:00|打三角洲到深夜，被队友坑了|电脑前|又气又爽|false\n\n" +
                "活动要具体、有细节，不要写笼统的「上课」「玩游戏」。要写出上的什么课、打什么模式、追什么番、逛什么店。\n" +
                "心情要多样：开心、emo、累、无聊、充实、焦虑、兴奋、慵懒、烦躁、感动——换着来。\n" +
                "一周7天中选2~3天设为上学日，其余为非上学日。上学日和非上学日的活动风格差异要明显。\n" +
                "一定要写满7天42行，每天6个时段一个不少。";

        String response = ai.generateRaw(prompt);
        if (response == null || response.isBlank()) {
            logger.error("[LifeEngine] 日程生成失败: AI 返回为空");
            return;
        }

        // 清除旧周，插入新日程
        scheduleRepo.deleteWeek(weekStart, weekEnd);
        List<CandyBearSchedule> schedules = parseSchedulesResponse(response, weekStart, weekEnd);
        for (CandyBearSchedule s : schedules) {
            try {
                scheduleRepo.insert(s);
            } catch (SQLException e) {
                logger.error("[LifeEngine] 日程插入失败 " + s.getScheduleDate() + " " + s.getTimeSlot() + ": " + e.getMessage());
            }
        }
    }

    /** 解析 AI 返回的竖线分隔日程 */
    private List<CandyBearSchedule> parseSchedulesResponse(String resp, LocalDate weekStart, LocalDate weekEnd) {
        List<CandyBearSchedule> list = new ArrayList<>();
        if (resp == null) return list;
        for (String line : resp.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 8) continue;
            try {
                CandyBearSchedule s = new CandyBearSchedule();
                s.setScheduleDate(LocalDate.parse(parts[0].trim()));
                s.setDayOfWeek(getWeekday(LocalDate.parse(parts[0].trim())));
                s.setTimeSlot(parts[1].trim());
                s.setStartTime(LocalTime.parse(parts[2].trim()));
                s.setEndTime(LocalTime.parse(parts[3].trim()));
                s.setActivity(parts[4].trim());
                s.setLocation(parts[5].trim());
                s.setMood(parts[6].trim());
                s.setSchoolDay("true".equalsIgnoreCase(parts[7].trim()));
                if (!s.getScheduleDate().isBefore(weekStart) && !s.getScheduleDate().isAfter(weekEnd)) {
                    list.add(s);
                }
            } catch (Exception e) {
                logger.error("[LifeEngine] 日程行解析跳过: " + line + " -> " + e.getMessage());
            }
        }
        return list;
    }
}

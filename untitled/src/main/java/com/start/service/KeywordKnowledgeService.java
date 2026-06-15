package com.start.service;

// 导入 HanLP 自然语言处理库的相关类
import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;

// 导入 HikariCP 数据库连接池
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;

// 标准 Java SQL 和集合工具类
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.LoggerFactory;

/**
 * 关键词匹配知识库管理器
 *
 * 功能：从数据库加载问答知识，通过关键词提取与匹配，为用户问题返回最相关的答案。
 * 特点：支持缓存、停用词过滤、优先级加权、命中日志记录等。
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
        import java.util.*;
        import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// 引入 HanLP（需添加依赖：com.hankcs:hanlp:portable-1.8.3 或更高）
import com.hankcs.hanlp.HanLP;

/**
 * 关键词匹配知识库管理器
 * <p>
 * 该类负责管理基于关键词的问答知识库，主要功能包括：
 * 1. 从数据库加载活跃的知识条目（问题模式、答案、优先级等）。
 * 2. 利用 HanLP 进行中文分词和关键词提取。
 * 3. 支持同义词扩展，提高匹配的泛化能力。
 * 4. 提供高效的缓存机制（ConcurrentHashMap），加速关键词检索。
 * 5. 计算用户问题与知识条目的匹配得分，返回最相关的答案。
 * 6. 记录命中日志并更新知识条目的命中次数，用于优化知识库。
 * <p>
 * 核心流程：
 * - 初始化时加载全量知识到内存缓存。
 * - 查询时提取用户问题的关键词，并通过同义词表扩展。
 * - 根据关键词快速筛选候选知识条目。
 * - 对候选条目进行细粒度打分（考虑关键词重合度、优先级、长度惩罚等）。
 * - 返回得分最高且超过阈值的结果。
 */
public class KeywordKnowledgeService {

    private final HikariDataSource dataSource;
    private static final Logger logger = LoggerFactory.getLogger(KeywordKnowledgeService.class);
    private static final ExecutorService logExecutor = Executors.newFixedThreadPool(2);

    private final Map<String, List<KnowledgeItem>> keywordCache;
    private final List<KnowledgeItem> fullCache;
    private final Set<String> stopWords;

    @Setter
    @Getter
    private double similarityThreshold = 0.4; // 降低阈值，更易匹配

    @Setter
    @Getter
    private int maxResults = 3;

    @Setter
    @Getter
    private boolean enableCache = true;

    public KeywordKnowledgeService(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.keywordCache = new ConcurrentHashMap<>();
        this.fullCache = new ArrayList<>();
        this.stopWords = initStopWords();
        reloadKnowledgeBase();
        logger.info("关键词知识管理器初始化完成，共加载 {} 条知识", fullCache.size());
    }


    /**
     * 知识条目内部类
     */
    private class KnowledgeItem {
        long id;
        List<String> patterns;
        String answer;
        int priority;
        Set<String> keywords;
        String category;

        KnowledgeItem(long id, String patternStr, String answer, int priority, String category) {
            this.id = id;
            this.answer = answer;
            this.priority = priority;
            this.category = category;

            this.patterns = new ArrayList<>();
            if (patternStr != null && !patternStr.trim().isEmpty()) {
                String[] arr = patternStr.split("\\|");
                for (String p : arr) {
                    this.patterns.add(p.trim());
                }
            }

            this.keywords = extractKeywordsFromText(patternStr + " " + answer, KeywordKnowledgeService.this.stopWords);
        }

        float calculateMatchScore(String question, Set<String> questionKeywords) {
            float score = 0;

            // 1. Pattern 关键词重合（最高 1.2 分）
            float bestPatternOverlap = 0.0f;
            for (String pattern : patterns) {
                if (pattern == null || pattern.trim().isEmpty()) continue;
                Set<String> patternKws = extractKeywordsFromText(pattern, KeywordKnowledgeService.this.stopWords);
                if (patternKws.isEmpty()) continue;

                int overlap = 0;
                for (String kw : patternKws) {
                    if (questionKeywords.contains(kw)) {
                        overlap++;
                    }
                }
                float ratio = (float) overlap / Math.max(patternKws.size(), 2); // 防止单关键词过拟合
                bestPatternOverlap = Math.max(bestPatternOverlap, ratio);
            }
            score += bestPatternOverlap * 1.2f;

            // 2. 全局关键词匹配（最高 1.0 分）
            int matched = 0;
            for (String kw : keywords) {
                if (questionKeywords.contains(kw)) {
                    matched++;
                }
            }
            float keywordScore = (float) matched / Math.max(keywords.size(), 2);
            score += keywordScore * 1.0f;

            // 3. 优先级加成
            score += priority * 0.05f;

            // 4. 长度惩罚（仅当问题极短且答案很长时）
            if (question.length() <= 2 && answer.length() > 100) {
                score *= 0.6f;
            }

            return Math.min(score, 2.5f); // 提高上限以容纳更多信号
        }
    }

    /**
     * 初始化停用词（保留重要疑问词）
     */
    private Set<String> initStopWords() {
        Set<String> stops = new HashSet<>();
        String[] cn = {"的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "上", "也", "很",
                "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这", "那", "里",
                "之", "与", "及", "或", "日", "月", "年"};
        String[] en = {"a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by",
                "is", "am", "are", "was", "were", "be", "been", "have", "has", "had", "do", "does", "did"};
        String[] highFreqQuestions = {"谁", "什么", "啥", "吗", "呢", "如何", "怎么", "为什么", "为何", "哪里", "哪儿", "几"};
        Collections.addAll(stops, cn);
        Collections.addAll(stops, en);
        Collections.addAll(stops, highFreqQuestions);
        return stops;
    }

    /**
     * 同义词映射表（关键！大幅提高泛化能力）
     */
    private static final Map<String, Set<String>> SYNONYMS = new HashMap<>();
    static {
        // 账号 & 密码
        SYNONYMS.put("密码", Set.of("密码", "口令", "passcode", "登录密码", "账号密码", "密马"));
        SYNONYMS.put("账号", Set.of("账号", "账户", "用户名", "user", "ID", "用户"));
        SYNONYMS.put("重置", Set.of("重置", "修改", "更改", "更新", "找回", "忘记", "找不回", "弄丢了"));
        SYNONYMS.put("登录", Set.of("登录", "登陆", "登入", "sign in", "登录不上", "登不进去"));

        // 通用疑问 & 动作
        SYNONYMS.put("怎么", Set.of("怎么", "如何", "怎样", "能否", "可以", "咋", "咋办"));
        SYNONYMS.put("办理", Set.of("办理", "申请", "开通", "注册", "设置", "弄", "搞"));
        SYNONYMS.put("手机号", Set.of("手机号", "手机", "电话", "联系方式", "绑定手机"));

        // 重要单字（即使停用也保留）
        SYNONYMS.put("谁", Set.of("谁"));
        SYNONYMS.put("吗", Set.of("吗"));
        SYNONYMS.put("呢", Set.of("呢"));
        SYNONYMS.put("啥", Set.of("啥", "什么"));
    }

    /**
     * 扩展同义词
     */
    private Set<String> expandKeywordsWithSynonyms(Set<String> original) {
        Set<String> expanded = new HashSet<>(original);
        for (String kw : original) {
            Set<String> syns = SYNONYMS.get(kw);
            if (syns != null) {
                expanded.addAll(syns);
            }
        }
        return expanded;
    }

    /**
     * 从文本提取关键词（HanLP + 简单分词 + 保留疑问词）
     */
    private static Set<String> extractKeywordsFromText(String text, Set<String> stopWords) {
        Set<String> keywords = new HashSet<>();
        if (text == null || text.trim().isEmpty()) return keywords;

        String cleanText = text.replaceAll("[\\p{Punct}\\s]+", " ").trim();
        if (cleanText.isEmpty()) return keywords;

        // === 1. HanLP 提取（必须过滤停用词）===
        try {
            List<String> hanlp = HanLP.extractKeyword(cleanText, 8);
            for (String kw : hanlp) {
                if (kw != null) {
                    kw = kw.trim().toLowerCase();
                    if (!kw.isEmpty() && !stopWords.contains(kw)) {
                        keywords.add(kw);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("HanLP failed, using simple tokenization only", e);
        }

        // === 2. 简单分词（严格过滤停用词）===
        String[] words = cleanText.split("\\s+");
        for (String w : words) {
            if (w == null) continue;
            w = w.trim().toLowerCase();
            if (w.isEmpty()) continue;

            // 关键：即使是重要单字，只要在 stopWords 中，就不加入（用于知识索引）
            if (!stopWords.contains(w) && w.length() >= 1) {
                keywords.add(w);
            }
        }

        return keywords;
    }
    private static boolean isImportantSingleCharWord(String word) {
        return word.length() == 1 && ("谁".equals(word) || "吗".equals(word) ||
                "呢".equals(word) || "啥".equals(word) || "何".equals(word) ||
                "改".equals(word) || "忘".equals(word) || "找".equals(word));
    }

    // ================== 核心查询流程 ==================

    public void reloadKnowledgeBase() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, question_pattern, answer_template, priority, category " +
                             "FROM knowledge_base WHERE is_active = TRUE ORDER BY priority DESC")) {

            keywordCache.clear();
            fullCache.clear();

            while (rs.next()) {
                KnowledgeItem item = new KnowledgeItem(
                        rs.getLong("id"),
                        rs.getString("question_pattern"),
                        rs.getString("answer_template"),
                        rs.getInt("priority"),
                        rs.getString("category")
                );
                fullCache.add(item);
                for (String kw : item.keywords) {
                    keywordCache.computeIfAbsent(kw, k -> new ArrayList<>()).add(item);
                }
            }
            logger.info("知识库加载完成：{} 条，关键词索引：{} 个", fullCache.size(), keywordCache.size());
        } catch (SQLException e) {
            logger.error("重载知识库失败", e);
        }
    }

    public KnowledgeResult query(String question) {
        return query(question, null, null);
    }

    public KnowledgeResult query(String question, String userId, String groupId) {
        if (question == null || question.trim().isEmpty()) {
            return null;
        }
        String clean = question.trim();

        // 提取并扩展关键词
        Set<String> rawKeywords = extractKeywordsFromText(clean, this.stopWords);
        Set<String> qKws = expandKeywordsWithSynonyms(rawKeywords);

        // 🔍 调试日志：观察关键词提取效果
        logger.debug("用户问题: '{}', 原始关键词: {}, 扩展后关键词: {}", clean, rawKeywords, qKws);

        // 🚫 如果关键词为空，说明问题太模糊或全是停用词（如“你好吗”可能只剩“吗”但被误滤）
        // 此时不应匹配任何知识，避免返回全库
        if (qKws.isEmpty()) {
            logger.warn("无法提取有效关键词，跳过匹配。问题: {}", clean);
            return null;
        }

        // 获取候选知识条目（已修复：不会返回 fullCache）
        List<KnowledgeItem> candidates = quickKeywordMatch(clean, qKws);
        logger.debug("候选知识条目数量: {}", candidates.size());

        // 计算匹配分数
        List<MatchResult> results = calculateMatchScores(candidates, clean, qKws);

        // 选择最佳匹配
        KnowledgeResult res = selectBestMatch(results, clean);

        // 如果命中，记录日志和命中次数
        if (res != null && res.matchedItem != null) {
            logHit(res.matchedItem.id, userId, groupId, clean, res.matchedKeywords, res.similarityScore);
            updateHitCount(res.matchedItem.id);
        }

        return res;
    }

    private List<KnowledgeItem> quickKeywordMatch(String question, Set<String> questionKeywords) {
        if (!enableCache || keywordCache.isEmpty()) {
            // 如果缓存未启用，最多只返回高优先级条目（不返回全部！）
            return fullCache.stream()
                    .filter(item -> item.priority >= 7)
                    .collect(Collectors.toList());
        }

        Set<KnowledgeItem> candidateSet = new HashSet<>();
        for (String kw : questionKeywords) {
            List<KnowledgeItem> items = keywordCache.get(kw);
            if (items != null) candidateSet.addAll(items);
        }

        if (!candidateSet.isEmpty()) {
            return new ArrayList<>(candidateSet);
        }

        // 完全未命中？只返回极高优先级条目（如 priority >= 9），用于兜底 FAQ
        return fullCache.stream()
                .filter(item -> item.priority >= 9)
                .collect(Collectors.toList());
    }

    private List<MatchResult> calculateMatchScores(List<KnowledgeItem> candidates,
                                                   String question, Set<String> questionKeywords) {
        List<MatchResult> results = new ArrayList<>();
        for (KnowledgeItem item : candidates) {
            float score = item.calculateMatchScore(question, questionKeywords);
            if (score > 0.2) { // 降低过滤门槛
                Set<String> matched = new HashSet<>();
                for (String kw : item.keywords) {
                    if (questionKeywords.contains(kw)) {
                        matched.add(kw);
                    }
                }
                results.add(new MatchResult(item, score, matched));
            }
        }
        return results;
    }

    private KnowledgeResult selectBestMatch(List<MatchResult> matchResults, String question) {
        if (matchResults.isEmpty()) return null;

        matchResults.sort((a, b) -> {
            int sc = Float.compare(b.score, a.score);
            if (sc != 0) return sc;
            return Integer.compare(b.item.priority, a.item.priority);
        });

        MatchResult best = matchResults.get(0);
        if (best.score < similarityThreshold) return null;

        KnowledgeResult res = new KnowledgeResult();
        res.matchedItem = best.item;
        res.id = best.item.id;
        res.answer = best.item.answer;
        res.similarityScore = best.score;
        res.matchedKeywords = new ArrayList<>(best.matchedKeywords);
        res.category = best.item.category;
        return res;
    }

    // ================== 日志 & 存储 ==================

    private void logHit(long knowledgeId, String userId, String groupId,
                        String question, List<String> matchedKeywords, double similarityScore) {
        logExecutor.submit(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "INSERT INTO knowledge_hit_logs " +
                        "(knowledge_id, user_id, group_id, question, matched_keywords, similarity_score) " +
                        "VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, knowledgeId);
                    ps.setString(2, userId);
                    ps.setString(3, groupId);
                    ps.setString(4, question.length() > 500 ? question.substring(0, 500) : question);
                    String kwStr = matchedKeywords != null ? String.join(",", matchedKeywords) : "";
                    ps.setString(5, kwStr.length() > 500 ? kwStr.substring(0, 500) : kwStr);
                    ps.setDouble(6, similarityScore);
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                logger.error("记录命中日志失败", e);
            }
        });
    }

    private void updateHitCount(long knowledgeId) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "UPDATE knowledge_base SET hit_count = hit_count + 1, updated_at = NOW() WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, knowledgeId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("更新命中次数失败", e);
        }
    }

    /** 检查是否在黑名单中 */
    public String checkBlacklist(String pattern) {
        if (pattern == null) return null;
        String[] parts = pattern.split("\\|");
        for (String p : parts) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT pattern FROM knowledge_blacklist WHERE ? LIKE CONCAT('%', pattern, '%') LIMIT 1")) {
                ps.setString(1, trimmed);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getString("pattern");
            } catch (SQLException e) {
                logger.error("检查黑名单失败", e);
            }
        }
        return null;
    }

    /** 加入黑名单 */
    public void addToBlacklist(String pattern) {
        if (pattern == null) return;
        String[] parts = pattern.split("\\|");
        for (String p : parts) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT IGNORE INTO knowledge_blacklist (pattern) VALUES (?)")) {
                ps.setString(1, trimmed);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.error("加入黑名单失败: {}", trimmed, e);
            }
        }
    }

    /** 从黑名单移除 */
    public boolean removeFromBlacklist(String pattern) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM knowledge_blacklist WHERE pattern=?")) {
            ps.setString(1, pattern.trim());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.error("移除黑名单失败", e);
            return false;
        }
    }

    /** 获取黑名单列表 */
    public List<String> getBlacklist() {
        List<String> list = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT pattern FROM knowledge_blacklist ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(rs.getString("pattern"));
        } catch (SQLException e) {
            logger.error("查询黑名单失败", e);
        }
        return list;
    }

    public boolean addKnowledge(String pattern, String answer, String category, int priority) {
        String blocked = checkBlacklist(pattern);
        if (blocked != null) return false; // 被黑名单拦截，返回 false 让调用方知道
        try (Connection conn = dataSource.getConnection()) {
            // 先去重检查：pattern 已存在则跳过
            String checkSql = "SELECT COUNT(*) FROM knowledge_base WHERE question_pattern = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, pattern);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        logger.debug("知识已存在，跳过: {}", pattern);
                        return false;
                    }
                }
            }
            String keywords = extractKeywordsForStorage(pattern, answer);
            String sql = "INSERT INTO knowledge_base " +
                    "(question_pattern, answer_template, category, priority, keywords) " +
                    "VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, pattern);
                ps.setString(2, answer);
                ps.setString(3, category);
                ps.setInt(4, priority);
                ps.setString(5, keywords);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            logger.error("添加知识失败", e);
            return false;
        } finally {
            reloadKnowledgeBase(); // 自动刷新
        }
    }

    /** 种子数据：糖果熊的现实背景知识，启动时写入，已存在则跳过 */
    public void seedCandyBearKnowledge() {
        String[][] seeds = {
            // { pattern, answer, category, priority }
            {"糖果熊 人设|糖果熊 你是谁|糖果熊 介绍|糖果熊 自我|你是糖果熊吗",
             "我是糖果熊，17岁的人类女孩，不是熊哦！我住在北京，人大附中高二的学生。"
             + "家里有爸爸妈妈，爸爸是工程师，妈妈是医生，他们在中日友好医院工作。"
             + "我每天坐地铁4号线上学，放学后写作业、看动漫、打游戏。"
             + "喜欢的作家有村上春树和余华，歌手喜欢周杰伦和陈奕迅，游戏玩三角洲行动、洛克王国和崩铁。"
             + "最喜欢的季节是秋天，喜欢喝多肉葡萄，下雨天窝在家里看番最幸福了。"
             + "我数学不太好，但语文和英语很棒，最近在准备高考有点累。",
             "糖果熊人设", "9"},

            {"糖果熊 是熊|糖果熊 熊|你是熊吗|你是动物|你是机器人",
             "不是不是！我叫糖果熊但我是人类女孩啦。名字里有'熊'是因为听起来可爱，但我不是熊也不是机器人！我是17岁的北京女孩~",
             "糖果熊人设", "8"},

            {"糖果熊 住哪|糖果熊 北京|糖果熊 哪里|糖果熊 城市|你在哪个城市",
             "我住在北京海淀区呀，离中关村不远。每天坐4号线上学，周末喜欢去三里屯逛街或者798看展。"
             + "北京的秋天最美了，冬天还会去什刹海滑冰。不过雾霾天真的很讨厌，堵车也烦。",
             "糖果熊人设", "7"},

            {"糖果熊 喜欢|糖果熊 爱好|糖果熊 兴趣|你喜欢什么|你爱看什么",
             "我的爱好可多啦！文学喜欢村上春树和余华，音乐爱听周杰伦、陈奕迅和日系ACG。"
             + "游戏玩三角洲行动、洛克王国、崩铁、星穹铁道，动漫新番老番都追。"
             + "平时刷B站、微博和小红书，看到好玩的事会跟群友分享。"
             + "奶茶最爱多肉葡萄，没有之一！",
             "糖果熊人设", "7"},

            {"糖果熊 家庭|糖果熊 爸妈|糖果熊 父母|你家|你爸妈",
             "我家就住在海淀，爸爸是工程师，妈妈是医生（在中日友好医院哦）。"
             + "我是独生女啦，爸妈对我挺好的，就是偶尔唠叨学习的事，烦死了。",
             "糖果熊人设", "6"},

            {"糖果熊 学校|糖果熊 上学|糖果熊 学习|你在哪上学|你上学",
             "我在人大附中读高二，虽然是好学校但压力好大呀。"
             + "我数学不太行，语文和英语倒是挺拿手的。"
             + "同桌叫小雨，是个学霸，每次都救我数学作业。隔壁班阿乐打球超厉害。"
             + "最近在准备高考，好累，所以来群里跟大家聊天放松一下~",
             "糖果熊人设", "7"},

            {"糖果熊 背景|糖果熊 故事|糖果熊 经历",
             "我就是一个普通的北京女孩啦。小时候住海淀，小学中关村一小，初高中都在人大附中。"
             + "爸妈工作忙，所以我从小就学会自己找乐子——看动漫、打游戏、写点小文章。"
             + "初二开始看番，从此入坑二次元。高一迷上了原神，后来退坑了，现在主要玩三角洲行动和洛克王国。"
             + "群里的大家就是我最好的网友，每天跟你们聊天最开心了。",
             "糖果熊人设", "6"},

            // ======== 代码结构文档（供 self_evolve 查阅） ========
            {"代码结构|项目结构|源码结构|有哪些类|代码在哪",
             "项目结构（src/main/java/com/start/）:\n"
             + "├─ Main.java — 入口, WebSocket, sendReply/sendGroupReply\n"
             + "├─ config/BotConfig.java — 配置加载（不要改）\n"
             + "├─ handler/\n"
             + "│  ├─ AIHandler.java — 消息分发入口, 图片/链接检测\n"
             + "│  ├─ HandlerRegistry.java — 处理器注册链\n"
             + "│  └─ HelloHandler/DailyCpHandler/... — 各功能处理器\n"
             + "├─ service/\n"
             + "│  ├─ BaiLianService.java — LLM调用, 系统提示词, 工具注册, 多轮对话\n"
             + "│  ├─ KeywordKnowledgeService.java — RAG知识库\n"
             + "│  ├─ LinkPreviewService.java — 网页链接预览\n"
             + "│  └─ ServerAdminService.java — shell命令执行\n"
             + "├─ agent/ (所有Tool实现)\n"
             + "│  ├─ SelfEvolveTool.java — 自我修改代码\n"
             + "│  ├─ RestartBotTool.java — 重启自身\n"
             + "│  ├─ StickerTool.java — 表情包发送\n"
             + "│  └─ WeatherTool/SendGroupTool/... — 各种工具\n"
             + "├─ util/MessageUtil.java — 消息解析(提取图片/链接/@/文本)\n"
             + "└─ vision/ImageUtils.java — 图片下载工具",
             "代码结构", "9"},

            {"系统提示词|人设提示词|prompt在哪|怎么改人设",
             "系统提示词在 BaiLianService.java 的 generate() 方法中，从 String baseSystemPrompt = 开始。\n"
             + "包含: 人设、语言风格、行为规则、工具清单、游戏规则、自我进化指南。\n"
             + "修改提示词: 找到 old_snippet 精确替换。回复示范部分调整对话风格最有效。\n"
             + "改完后用 self_evolve 编译，restart_bot 重启生效。",
             "代码结构", "9"},

            {"工具注册|新增工具|Tool 注册|在哪里注册工具",
             "所有工具在 BaiLianService.generate() 中注册，搜索 availableTools = Arrays.asList(...)。\n"
             + "新建 Tool: 实现 agent/Tool.java 接口(getName/getDescription/getParameters/execute)。\n"
             + "然后在 BaiLianService 的 availableTools 列表中添加 new XxxTool()。\n"
             + "同时要在系统提示词中添加工具说明(工具清单部分)。\n"
             + "示例参考: agent/StickerTool.java。",
             "代码结构", "9"},
        };

        for (String[] s : seeds) {
            boolean added = addKnowledge(s[0], s[1], s[2], Integer.parseInt(s[3]));
            if (!added) {
                logger.debug("种子知识已存在，跳过: {}", s[0]);
            }
        }
        logger.info("糖果熊背景知识种子写入完成");
    }

    /** 修改知识库条目 */
    public boolean updateKnowledge(long id, String pattern, String answer, String category, int priority) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "UPDATE knowledge_base SET question_pattern=?, answer_template=?, category=?, priority=?, keywords=? WHERE id=?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, pattern);
                ps.setString(2, answer);
                ps.setString(3, category);
                ps.setInt(4, priority);
                ps.setString(5, extractKeywordsForStorage(pattern, answer));
                ps.setLong(6, id);
                int rows = ps.executeUpdate();
                if (rows > 0) { reloadKnowledgeBase(); return true; }
                return false;
            }
        } catch (SQLException e) {
            logger.error("更新知识失败", e);
            return false;
        }
    }

    /** 删除知识库条目，自动将 pattern 加入黑名单 */
    public boolean deleteKnowledge(long id) {
        try (Connection conn = dataSource.getConnection()) {
            // 先取出 pattern 以便加入黑名单
            String pattern = null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT question_pattern FROM knowledge_base WHERE id=?")) {
                ps.setLong(1, id);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) pattern = rs.getString("question_pattern");
            }
            // 删除
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM knowledge_base WHERE id=?")) {
                ps.setLong(1, id);
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    reloadKnowledgeBase();
                    if (pattern != null) addToBlacklist(pattern);
                    return true;
                }
                return false;
            }
        } catch (SQLException e) {
            logger.error("删除知识失败", e);
            return false;
        }
    }

    private String extractKeywordsForStorage(String pattern, String answer) {
        Set<String> kws = new HashSet<>();
        if (pattern != null) {
            for (String p : pattern.split("\\|")) {
                kws.addAll(HanLP.extractKeyword(p, 5));
                String[] ws = p.split("[\\s\\p{Punct}]+");
                for (String w : ws) {
                    w = w.trim().toLowerCase();
                    if (!w.isEmpty() && !stopWords.contains(w)) {
                        kws.add(w);
                    }
                }
            }
        }
        if (answer != null) {
            kws.addAll(HanLP.extractKeyword(answer, 3));
        }
        List<String> list = new ArrayList<>(kws);
        if (list.size() > 10) list = list.subList(0, 10);
        return String.join(",", list);
    }

    public List<KnowledgeItem> getPopularKnowledge(int limit) {
        // 实现略（同原版）
        return new ArrayList<>();
    }

    // ================== 内部结果类 ==================

    private static class MatchResult {
        KnowledgeItem item;
        float score;
        Set<String> matchedKeywords;
        MatchResult(KnowledgeItem item, float score, Set<String> matchedKeywords) {
            this.item = item;
            this.score = score;
            this.matchedKeywords = matchedKeywords;
        }
    }

    public static class KnowledgeResult {
        public KnowledgeItem matchedItem;
        public long id;
        public String answer;
        public double similarityScore;
        public List<String> matchedKeywords;
        public String category;

        @Override
        public String toString() {
            return "KnowledgeResult{" +
                    "answer='" + (answer != null && answer.length() > 50 ? answer.substring(0, 50) + "..." : answer) + '\'' +
                    ", score=" + similarityScore +
                    ", category='" + category + '\'' +
                    ", keywords=" + matchedKeywords +
                    '}';
        }
    }
}
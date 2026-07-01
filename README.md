# 🍬 糖果熊 (CandyBear) — 智能 QQ 群机器人

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-ED8B00.svg)](https://adoptium.net/)
[![Maven](https://img.shields.io/badge/Maven-3.6+-C71A36.svg)](https://maven.apache.org/)
[![OneBot](https://img.shields.io/badge/OneBot-v11-black)](https://github.com/botuniverse/onebot-11)

基于 Java 17 与 OneBot v11 协议的 Conversation Runtime —— 一个可自我进化的智能 QQ 群机器人。集成四层 LLM 模型、Agent 工具调用、用户画像分析、情绪感知、Web 可观测面板与群互动生态。**核心设计哲学：Java 维护事实与状态，LLM 负责决策。**

---

## 目录

- [1. 项目概览](#1-项目概览)
- [2. 系统架构](#2-系统架构)
- [3. 功能矩阵](#3-功能矩阵)
- [3.1 消息入口层](#31-消息入口层)
- [3.2 AI 工具集](#32-ai-工具集)
- [3.3 后端服务](#33-后端服务)
- [3.4 图像渲染](#34-图像渲染)
- [4. 技术栈](#4-技术栈)
- [5. 快速开始](#5-快速开始)
- [6. 配置参考](#6-配置参考)
- [7. 部署与运维](#7-部署与运维)
- [8. 开发指南](#8-开发指南)

---

## 1. 项目概览

糖果熊不是一个聊天机器人，而是一个 **Conversation Runtime**。

- **Java 维护事实（State）** — 数据持久化、状态管理、事件分发
- **LLM 负责决策（Decision）** — 是否回复、回复什么、怎么回复
- **Runtime 管理生命周期** — `ConversationRuntime` 协调 Receive → Interpret → Observe → Build Context → Generate → Commit → Trace 七阶段
- **Listener 消费事件** — 统计、日志、Trace、WebDashboard 全部通过 `RuntimeListener`，不侵入 Runtime

核心能力：

- **自然语言对话** — 四层模型架构（bailian / agent / audit / vision），支持多轮上下文与追问识别
- **自我进化** — L1 热重载（DB 配置秒生效）→ L2 改源码编译部署 → L3 自审修 bug，三层次递进
- **Agent 工具调用** — 30+ 可调用工具覆盖天气、搜索、提醒、排行榜、知识库、记忆、Shell 运维等场景
- **Web 可观测面板** — 内嵌 HTTP 服务器（端口 8765），实时决策链路追踪、群聊指标、系统健康
- **用户画像与好感度** — 定时分析群聊记录生成兴趣标签及动态好感度
- **群互动生态** — 每日 CP 配对、职业/命格抽卡、幸运值、排行榜等轻量社交玩法
- **情绪感知** — 分群独立情绪系统（0–100），@、互动、冷场等事件驱动情绪变化

---

## 2. 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                       NapCat (OneBot v11)                   │
│                    WebSocket (ws://host:port)               │
└──────────────────────────┬──────────────────────────────────┘
                           │ JSON Events
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                     Main (WebSocket Client)                 │
│  ┌────────────┐  ┌──────────────┐  ┌────────────────────┐   │
│  │SpamDetector│  │ OneBotWS     │  │ PendingRequests    │   │
│  │(防刷检测)  │  │ (API 封装)   │  │ (异步应答映射)     │   │
│  └────────────┘  └──────────────┘  └────────────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │ dispatch()
                           ▼
┌──────────────────────────────────────────────────────────────┐
│                    HandlerRegistry (责任链)                   │
│  Hello → Luck → Joke → Reminder → Sanjiao → DailyProfession  │
│  → DailyCp → Rank → EggGroupSearch → Merchant → AIHandler    │
│                                                              │
│  AIHandler ──委托──▶ ConversationRuntime                     │
└──────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│              ConversationRuntime (七阶段生命周期)            │
│                                                              │
│  Receive ──▶ Interpret ──▶ Observe ──▶ Build Context        │
│       (消息)    (事件分类)    (状态快照)    (Prompt组装)     │
│                                                              │
│                          ──▶ Generate ──▶ Commit ──▶ Trace  │
│                              (LLM调用)    (状态持久)  (监听器) │
└──────────────────┬───────────────────────────────────────────┘
                   │
    ┌──────────────┼──────────────┐
    ▼              ▼              ▼
┌──────────┐ ┌──────────┐ ┌──────────────┐
│Interpreter│ │Generator │ │   Listener   │
│(事件分类) │ │(LLM调用) │ │(WebDashboard │
│          │ │          │ │ DecisionTrace│
│          │ │          │ │ Metrics)     │
└──────────┘ └──────────┘ └──────────────┘
                   │
    ┌──────────────┼──────────────┐
    ▼              ▼              ▼
┌──────────┐ ┌──────────┐ ┌──────────────┐
│ bailian  │ │ agent    │ │ audit/vision │
│(DeepSeek │ │(DeepSeek │ │(Claude/Qwen  │
│ v4-pro)  │ │ v4-pro)  │ │ VL Max)      │
│ 主对话   │ │ 自进化   │ │ 审计/图片    │
└──────────┘ └──────────┘ └──────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────────────────┐
│                     数据 & 基础设施层                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐   │
│  │ MySQL    │  │ HikariCP │  │ HanLP    │  │ Java2D     │   │
│  │ (8.0+)   │  │ (连接池)  │  │ (NLP)    │  │ (图像渲染)  │   │
│  └──────────┘  └──────────┘  └──────────┘  └────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

**线程模型：**
- WebSocket IO 线程接收事件，同步分发至 Handler
- 每群独立单线程执行器 (`GroupSerialExecutor`) 串行化 AI 调用与游戏逻辑
- AI 回复异步执行，不阻塞 WebSocket 消息接收
- 定时任务通过 `ScheduledExecutorService` 和守护线程驱动

---

## 3. 功能矩阵

### 3.1 消息入口层

所有消息经责任链匹配，命中即处理。以下按优先级从高到低排列：

| 优先级 | 处理器 | 触发条件 | 功能描述 |
|:---:|---------|---------|---------|
| 1 | **HelloHandler** | `你好` / `@糖果熊 你好` | 基础问候 |
| 2 | **LuckHandler** | `幸运值` `运势` `魔咒` | 每日确定性幸运值 (0–100) + 今日宜/不宜 |
| 3 | **JokeHandler** | `讲个笑话` `/joke` | 调用 JokeAPI 获取随机笑话 |
| 4 | **ReminderHandler** | `/remind` (管理员私聊) | 周期提醒 / 单次定时 / 每日定时 |
| 5 | **SanjiaoHandler** | `特勤处` `脑机` `密码` | 三角洲行动游戏网页截图 |
| 6 | **DailyProfessionHandler** | `今日职业` `抽命格` `抽取` | 每日职业/命格抽卡，含战力值，渲染为图片 |
| 7 | **DailyCpHandler** | `cp` `每日cp` `抽cp` | 每日随机 CP 配对，含头像渲染 |
| 8 | **RankHandler** | `发言排行` `幸运排行` 等 30+ 词 | 多维排行榜 (发言/幸运/好感/职业/CP) |
| 9 | **EggGroupSearchHandler** | `#查蛋` `#查进化` `#预测蛋` | 洛克王国宠物数据库查询 |
| 10 | **AgentHandler** | `请帮我...` | AI 决策 + 工具调用 (天气/好感度) |
| 11 | **TravelingMerchantHandler** | `远行商人` / `订阅远行商人` / `取消订阅远行商人` | 远行商人商品查询、订阅/取消提醒、查看订阅，支持私聊与群聊 |
| 12 | **AIHandler** | 所有群消息 (兜底) | AI 对话、主动插话、追问识别 |

**AIHandler 三种响应模式：**

| 模式 | 触发方式 | 行为 |
|------|---------|------|
| 显式召唤 | `@糖果熊` / `#ai` / `!ai` | 立即进入 AI 对话 |
| 追问延续 | 2 分钟内用户再次发言 | 识别为追问，续接上下文回复 |
| 主动插话 | 话题匹配 + 概率 + 频率控制 | 自然插入群聊讨论 |

**冷却机制：** 同一用户在 2 秒内再次触发主动插话时跳过回复，避免连续短消息引发重复回应。

---

### 3.2 AI 工具集

AI 可通过 `<tool_call>` XML 块动态调用以下 24 个工具：

#### 信息查询类

| 工具 | 方法名 | 功能 |
|------|--------|------|
| **WeatherTool** | `get_weather` | Open-Meteo API 天气查询，支持中文城市名，自动使用用户记忆中的地点 |
| **WebSearchTool** | `web_search` | 多引擎网页搜索 (百度/Bing/DuckDuckGo) |
| **KnowledgeBaseTool** | `query_knowledge` | 内部知识库检索 (居住地、兴趣等预设知识) |
| **SearchHistoryTool** | `search_chat_history` | 数据库聊天记录检索，支持关键词/用户/时间范围 |
| **RankTool** | `get_ranking` | 群排行榜查询 (发言/幸运/好感) |
| **LuckTool** | `get_luck` | 查询指定用户幸运值 |
| **ProfessionTool** | `get_profession` | 查询指定用户今日职业与战力 |
| **UserAffinityTool** | `query_user_affection` | 查询当前用户好感度 |

#### 消息与交互类

| 工具 | 方法名 | 功能 |
|------|--------|------|
| **SendPrivateTool** | `send_private_msg` | 向指定用户发送私聊消息 |
| **SendGroupTool** | `send_group_msg` | 向指定群发送消息 |
| **SendStatusTool** | `send_status` | 发送口语化状态提示 ("稍等一下"、"我查查") |
| **PokeTool** | `send_poke` | 戳一戳群友 (5 分钟冷却) |
| **VoiceTool** | `send_voice` | TTS 语音合成并发送 (1 分钟冷却，100 字限制) |

#### 记忆与知识管理类

| 工具 | 方法名 | 功能 |
|------|--------|------|
| **MemoryTool** | `query_memory` | 查询 AI 短期记忆 (最近说了什么、调了什么工具) |
| **RememberFactTool** | `remember_fact` | 持久化存储用户事实/偏好/事件 |
| **RecallMemoryTool** | `recall_memory` | 按用户/关键词检索长期记忆 |
| **ScheduleEventTool** | `schedule_event` | 记录定时事件 (生日、纪念日、考试等) |
| **LearnKnowledgeTool** | `manage_knowledge` | 知识库 CRUD (增删改查，含权限控制) |
| **ReminderTool** | `set_reminder` | 设置延迟提醒 (如"30分钟后提醒我") |
| **UserAliasTool** | `manage_alias` | 管理用户别称与地点信息 |

#### 游戏辅助类

| 工具 | 方法名 | 功能 |
|------|--------|------|
| **SanjiaoTool** | `delta_force_query` | 三角洲行动游戏截图 |
| **EggGroupSearchTool** | `lokowang_pet_query` | 洛克王国宠物数据库查询 |
| **TravelingMerchantTool** | `lokowang_merchant_query` | 远行商人商品查询 |
| **MerchantSubscribeTool** | `lokowang_merchant_subscribe` | 远行商人订阅管理 (订阅/取消/查看) |

---

### 3.3 后端服务

| 服务 | 职责 |
|------|------|
| **ConversationRuntime** | 会话运行时核心：协调 Receive → Interpret → Observe → Build Context → Generate → Commit → Trace 七阶段生命周期，所有群消息处理必经入口 |
| **ConversationInterpreter** | 事件分类器：识别 FOLLOW_UP / MENTION / PROBABILISTIC / NOTHING，不依赖 Generator，只依赖 StateStore + BehaviorAnalyzer |
| **BaiLianService** | LLM 调用核心（Generator 角色）：Prompt 组装 → Tool Calling 循环 → API 调用，不依赖 Runtime。四层模型：bailian (DeepSeek v4-pro) / agent (自进化) / audit (Claude Sonnet 4.6) / vision (Qwen VL Max) |
| **WebDashboardListener** | Web 可观测面板：内嵌 HTTP 服务器（端口 8765），实时决策链路、群聊指标、系统健康，作为 RuntimeListener 不侵入 Runtime |
| **DecisionTraceListener** | 决策追踪：消费 CommitFinished 事件 → 记录 DecisionTrace |
| **MetricsListener** | 指标收集：消费 MessageReceived + CommitFinished → ConversationMetrics |
| **PromptBuilder** | Prompt 组装器：所有动态内容来自 PromptContext，只拼接不改逻辑 |
| **SelfEvolveTool** | 自我进化执行器：临时分支 → 修改源码 → 编译 → 测试 → 打包 → squash merge 到 main → push，失败自动回滚 |
| **BotMoodService** | 分群情绪系统：每群独立维护 0–100 心情值，持久化至 `group_mood` 表 |
| **UserPortraitService** | 用户画像引擎：定时批处理分析聊天记录，生成兴趣标签，动态调整好感度 |
| **ReminderService** | 提醒调度引擎：支持周期/单次/每日/延迟四种模式 |
| **TtsService** | 语音合成：调用 edge-tts / ChatTTS，MP3 输出 |
| **WebScreenshotService** | 网页截图：Selenium + Python 异步截图 |
| **SpamDetector** | 防刷检测：群聊消息频率监控与打断 |
| **GroupSerialExecutor** | 群聊串行执行器：同群内 AI 调用与游戏逻辑串行化 |
| **KeywordKnowledgeService** | 关键词知识库：基于关键词的快速问答匹配 |
| **CandyBearLifeEngine** | 糖果熊人生引擎：四层架构（章节 → 周记 → 日记 → 工具查询），模拟长期人生状态演进 |
| **MemoryProvider** | 长期记忆提供者：语义检索历史记忆，注入 PromptContext |
| **ErrorMonitorService** | 异常自动监控：定时巡检日志，audit API 诊断并修复 |

---

### 3.4 自我进化系统

糖果熊具备三层自进化能力：

| 层级 | 方式 | 生效速度 | 说明 |
|:---:|------|:---:|------|
| **L1** | `update_config` 工具 → 改 DB 配置 | 秒级 | 热重载，不需重启。修改 Prompt、规则集、阈值等 |
| **L2** | `self_evolve` 工具 → 改源码 → 编译 → 部署 | 分钟级 | 临时分支 → 编译测试打包 → squash merge 到 main → 替换 JAR |
| **L3** | `audit_logs` → 诊断异常 → `read_code` → 修复 | 自动 | ErrorMonitor 定时巡检，audit API 诊断，self_evolve 修复 |

安全机制：
- `CommandPolicy` 对 `shell_exec` 做三层过滤（危险模式/黑名单/需确认/白名单）
- `SelfEvolveTool` 硬阻断修改 `BotConfig.java`、`CommandPolicy.java`、`*.properties`、`*.env`
- 非管理员所有写操作全 DENY
- 编译失败自动回滚（文件 + git）

### 3.5 Web 可观测面板

内嵌 HTTP 服务器 `WebDashboardListener`（端口 8765，可通过 `DASHBOARD_PORT` 环境变量配置），作为 `RuntimeListener` 不侵入 Runtime。

提供以下 API：
- `GET /` — 实时 HTML 面板
- `GET /api/decisions` — 最近 300 条决策链路（REPLY/SILENT/ERROR + 耗时 + Token 用量）
- `GET /api/groups` — 各群消息量、回复率、错误率
- `GET /api/system` — JVM 内存、线程数、运行时长

支持 `DASHBOARD_TOKEN` 环境变量启用 Token 鉴权。

### 3.6 图像渲染

| 组件 | 功能 |
|------|------|
| **ImageRenderer** | Java AWT 基础绘图引擎：字体加载、头像绘制、Base64 转换 |
| **ProfessionCardTemplate** | 职业卡片：用户头像 + 职业名称 + 位阶 + 战力值 + 脉系配色 |
| **CpResultTemplate** | CP 配对结果：双方头像 + 名称 + 配对信息 |

字体依赖：HarmonyOS Sans (存放于 `src/main/resources/assets/fonts/`)

---

## 4. 技术栈

| 层级 | 技术选型 | 版本 |
|------|---------|------|
| **语言** | Java | 17 |
| **构建** | Maven + Shade Plugin (fat JAR) | 3.x |
| **通信** | Java-WebSocket | 1.5.3 |
| **协议** | OneBot v11 (NapCat) | — |
| **数据库** | MySQL (HikariCP 连接池) | — |
| **AI 模型** | glm-5.1 / gemini-3-flash (OpenAI 兼容 API) | — |
| **NLP** | HanLP (Portable) | 1.8.4 |
| **JSON** | Jackson (FasterXML) | — |
| **HTTP** | `java.net.http.HttpClient` (Java 11+) | — |
| **图像** | Java2D (AWT) | — |
| **截图** | Selenium + WebDriverManager | — |
| **日志** | SLF4J + Logback | — |
| **部署** | Bash 脚本 + GNU Screen | — |

**设计原则：**
- **架构宪法** — 详见 [CLAUDE.md](untitled/CLAUDE.md)：Java 不做语义决策、Interpreter 不依赖 Generator、Generator 不依赖 Runtime
- 手动依赖注入 — 无 Spring/DI 框架，`BotBootstrap` 负责全量装配
- Repository 模式 — 手写 SQL，`?` 占位符，try-with-resources，无 ORM
- 责任链模式 — Handler 按优先级排列，首匹配即处理
- RuntimeListener — 新增横切关注点优先做成 Listener，不修改 Runtime
- `main` 是唯一 Source of Truth — 不再使用长期功能分支
- 配置安全 — 敏感值通过 `${ENV_VAR:default}` 注入，不硬编码

---

## 5. 快速开始

### 5.1 环境要求

- **JDK** 17+
- **Maven** 3.6+
- **MySQL** 8.0+ (或 H2 用于开发环境)
- **NapCat** 实例 (OneBot v11 WebSocket)
- **Python 3** + Selenium (网页截图功能需要)

### 5.2 本地构建

```bash
# 克隆项目
git clone git@github.com:Andralice/CandyBear-QQ-BOT.git
cd CandyBear-QQ-BOT/untitled

# 构建 fat JAR
mvn clean package -DskipTests
# 产物: target/untitled-1.0-SNAPSHOT.jar
```

### 5.3 配置文件

```bash
# 复制示例配置
cp src/main/resources/application.properties.example src/main/resources/application.properties
```

所有配置项支持 `${ENV_VAR:default}` 格式，敏感值建议通过环境变量注入。详见 [application.properties.example](untitled/src/main/resources/application.properties.example)。

关键配置项：

| 配置项 | 环境变量 | 说明 |
|--------|----------|------|
| `ws.url` | `NAPCT_WS_URL` | NapCat WebSocket 正反向地址 |
| `onebot.access-token` | `ONEBOT_ACCESS_TOKEN` | OneBot access token |
| `bailian.api-key` | `BAILIAN_API_KEY` | LLM API Key (OpenAI 兼容) |
| `bailian.base-url` | `BAILIAN_BASE_URL` | LLM API 地址 |
| `bailian.chat-model` | `BAILIAN_CHAT_MODEL` | 对话模型名 |
| `bot.qq` | `BOT_QQ` | 机器人 QQ 号 |
| `admin.qq` | `ADMIN_QQ` | 管理员 QQ 号 |
| `allowed.groups` | `ALLOWED_GROUPS` | 允许的群号（逗号分隔） |
| `target.group.id` | `TARGET_GROUP_ID` | 主目标群号 |
| `database.url` | `DB_URL` | MySQL 连接地址 |
| `database.user` | `DB_USER` | 数据库用户名 |
| `database.password` | `DB_PASSWORD` | 数据库密码 |

### 5.4 本地运行

```bash
# 确保环境变量已设置（或在 application.properties 中直接填写）
export BAILIAN_API_KEY=sk-your-key
export DB_URL=jdbc:mysql://localhost:3306/candybear_db
export DB_USER=root
export DB_PASSWORD=your_password
export BOT_QQ=你的机器人QQ号
export ADMIN_QQ=你的QQ号
export ALLOWED_GROUPS=群号1,群号2

# 启动
java -Xms256m -Xmx768m -jar target/untitled-1.0-SNAPSHOT.jar
```

---

## 6. 配置参考

### 6.1 环境变量一览

| 变量 | 说明 | 必填 |
|------|------|:---:|
| `BAILIAN_API_KEY` | LLM 对话模型 API Key | ✓ |
| `DB_URL` | MySQL 数据库连接串 | ✓ |
| `DB_USER` | 数据库用户名 | ✓ |
| `DB_PASSWORD` | 数据库密码 | ✓ |
| `BOT_QQ` | 机器人 QQ 号 | ✓ |
| `NAPCT_WS_URL` | NapCat WebSocket 正反向地址 | ✓ |
| `ONEBOT_ACCESS_TOKEN` | OneBot access token | ✓ |
| `ADMIN_QQ` | 管理员 QQ（shell/知识库管理等权限） | 推荐 |
| `TARGET_GROUP_ID` | 主目标群号 | — |
| `ALLOWED_GROUPS` | 允许的群号列表 | — |
| `ALLOWED_PRIVATE_USERS` | 允许私聊的用户 | — |
| `BAILIAN_BASE_URL` | LLM API 地址 | — |
| `BAILIAN_CHAT_MODEL` | 对话模型名 | — |
| `AGENT_API_KEY` | Agent 模型 API Key | — |
| `MERCHANT_API_KEY` | 远行商人 API Key | — |

### 6.2 关键参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `allowed.groups` | — | 白名单群号列表 |
| `private.whitelist.enabled` | false | 是否启用私聊白名单 |
| `bailian.timeout-ms` | 90000 | LLM 请求超时 (ms) |
| `bailian.max-retries` | 2 | LLM 请求重试次数 |
| `database.pool.max-size` | 10 | 数据库连接池大小 |
| 同用户主动插话冷却 | 2 s | 防止连续刷屏 |
| 群冷场检测 | 5 min | 无消息判定冷场 |
| 追问识别窗口 | 2 min | 同一用户的下一条消息视为追问 |
| 职业抽卡防刷 | 30 s | 同群最小间隔 |

---

## 7. 部署与运维

### 7.1 Git 分支策略

```
main 是唯一 Source of Truth
    │
    ├── SelfEvolveTool: 临时分支 evolve/YYYYMMDD-HHmm → squash merge → push origin/main
    │   (编译/测试/打包均通过后才合并)
    │
    └── git-push.sh: git pull --ff-only → git add → git commit → git push origin/main
        (日常自动同步，每次先拉最新代码)
```

已取消 `auto-evolve` 长期分支。AI 修改后的代码直接进入 main。

### 7.2 CI/CD

GitHub Actions (`deploy.yml`) 监听 `main` 分支 push 事件，执行 **云端质量检查**（不负责生产部署）：

| 步骤 | 说明 |
|------|------|
| `mvn compile` | 编译检查 |
| `mvn test` | 单元测试 |
| `mvn package` | 打包验证 |

服务器部署由 `SelfEvolveTool` 直接负责：编译通过后本地替换 JAR 到 `/opt/qq-bot/`。

### 7.3 远程服务器结构

```
/opt/qq-bot/
├── untitled-1.0-SNAPSHOT.jar        # 当前运行 JAR
├── untitled-1.0-SNAPSHOT.jar.bak.*  # 历史备份
├── .env                              # 环境变量 (API Keys, DB 密码等)
├── Note/git-push.sh                  # 自动同步脚本
├── _deploy/                          # Git worktree (源码检出)
│   └── untitled/
│       ├── pom.xml
│       └── src/
├── qq-bot.log                        # 运行日志
├── tts/                              # TTS 音频输出
└── tmp/                              # 临时文件
```

### 7.4 运维命令

```bash
# 查看运行状态
screen -list

# 进入控制台
screen -r qq-bot

# 停止服务
screen -S qq-bot -X quit

# 查看实时日志
tail -f /opt/qq-bot/qq-bot.log

# 手动启动
source /opt/qq-bot/.env
java -Xms256m -Xmx768m -jar /opt/qq-bot/untitled-1.0-SNAPSHOT.jar
```

### 7.5 数据库表

核心业务表：

| 表名 | 用途 |
|------|------|
| `user_profiles` | 用户画像 (兴趣标签、分析时间) |
| `user_affinity` | 用户好感度 (0–100，含变化日志) |
| `group_mood` | 分群情绪值 |
| `group_message_stats` | 群消息统计 (发言数) |
| `long_term_memories` | 长期记忆与定时事件（含 trigger_at / triggered 列） |
| `keyword_knowledge` / `knowledge_base` | 关键词知识库 |
| `candy_bear_life_state` | 糖果熊人生状态 (单行表) |
| `candy_bear_weekly_diaries` | 每周日记 |
| `candy_bear_daily_journals` | 每日日记 |
| `recurring_tasks` | 周期任务（cron 表达式驱动） |
| `evolution_records` | 自我进化历史记录 |
| `bot_config` | 运行时配置 (热重载) |
| `users` | 用户昵称缓存 |
| `messages` | 原始消息归档 |
| `active_reply_logs` | 主动回复决策日志 |

---

## 8. 开发指南

### 8.1 新增消息处理器

1. 实现 `MessageHandler` 接口 (`match()` + `handle()`)
2. 在 `HandlerRegistry` 构造器中按优先级注册
3. 注意优先级顺序：AIHandler 必须最后 (兜底)

```java
public class MyHandler implements MessageHandler {
    @Override
    public boolean match(JsonNode msg) {
        return msg.path("raw_message").asText().contains("触发词");
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        bot.sendReply(msg, "响应内容");
    }
}
```

### 8.2 新增 AI 工具

1. 实现 `Tool` 接口 (`getName()` / `getDescription()` / `getParameters()` / `execute()`)
2. 在 `BaiLianService` 构造函数中注册

```java
public class MyTool implements Tool {
    @Override
    public String getName() { return "my_tool"; }

    @Override
    public String getDescription() { return "工具功能描述"; }

    @Override
    public JsonNode getParameters() {
        // 返回 JSON Schema
    }

    @Override
    public String execute(Map<String, Object> args) {
        // 执行业务逻辑，返回结果文本
    }
}
```

### 8.3 项目结构

```
untitled/
├── src/main/java/com/start/
│   ├── Main.java              # 入口 + WebSocket 生命周期
│   ├── BotBootstrap.java      # 服务装配 + 后台任务启动
│   ├── config/
│   │   ├── BotConfig.java     # 配置加载 (含环境变量替换)
│   │   └── DatabaseConfig.java # 数据源配置 + 表迁移
│   ├── handler/               # 消息处理器 (责任链节点)
│   ├── agent/                 # AI 工具实现 (Tool 接口)
│   │   └── evo/               # 自进化工具 (SelfEvolveTool, AuditTool, etc.)
│   ├── runtime/               # Conversation Runtime
│   │   ├── conversation/      # ConversationRuntime, ConversationStateStore
│   │   └── trace/             # WebDashboardListener, DecisionTraceListener, MetricsListener
│   ├── service/               # 核心业务服务
│   ├── repository/            # 数据访问层
│   ├── model/                 # 数据模型 (POJO)
│   ├── vision/                # 图像渲染 (Java2D)
│   └── util/                  # 工具类
├── src/main/resources/
│   ├── application.properties.example  # 配置模板
│   ├── assets/fonts/          # HarmonyOS Sans 字体
│   ├── assets/bg/             # 背景图
│   ├── stickers/              # 表情包资源
│   └── logback.xml            # 日志配置
├── src/test/java/             # 测试代码
├── Note/
│   └── git-push.sh            # Git 自动同步脚本
├── CLAUDE.md                  # 架构宪法（架构设计文档）
├── deploy.sh                  # 部署脚本
└── pom.xml
```

### 8.4 调试

```bash
# 日志级别 (src/main/resources/logback.xml)
# com.start 包默认 DEBUG 级别
# 查看完整 WebSocket 事件与 AI 调用链路

# 本地调试时可将 NapCat WebSocket 指向本地
# 在 application.properties 中设置:
# ws.url=ws://127.0.0.1:5701/?access_token=your_token
```

---

## 许可证

本项目采用 **MIT License** 开源。使用者应遵守 QQ 平台及 OneBot 协议的相关规定。

Copyright (c) 2025-2026 糖果熊 (CandyBear)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

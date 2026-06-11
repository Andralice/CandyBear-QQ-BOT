# 糖果熊 (CandyBear) — 智能 QQ 群机器人

基于 Java 17 与 OneBot v11 协议的企业级智能 QQ 群机器人，集成双模型 LLM 对话、Agent 工具调用、用户画像分析、情绪感知与群互动生态。

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

糖果熊是一个面向 QQ 群聊场景的多模态智能机器人，具备以下核心能力：

- **自然语言对话** — 双模型架构，日常聊天与复杂任务分离，支持多轮上下文与追问识别
- **主动社交参与** — 基于话题兴趣与群活跃度的主动插话机制，冷场检测与话题引导
- **Agent 工具调用** — 24 个可调用工具覆盖天气、搜索、提醒、排行榜、知识库、记忆等场景
- **用户画像与好感度** — 定时分析群聊记录生成兴趣标签及动态好感度
- **群互动生态** — 每日 CP 配对、职业/命格抽卡、幸运值、排行榜等轻量社交玩法
- **游戏辅助** — 洛克王国宠物数据库、远行商人查询、三角洲行动截图
- **情绪感知** — 分群独立情绪系统（0–100），@、互动、冷场等事件驱动情绪变化

---

## 2. 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                        NapCat (OneBot v11)                  │
│                     WebSocket (ws://host:port)              │
└──────────────────────────┬──────────────────────────────────┘
                           │ JSON Events
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                      Main (WebSocket Client)                │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────────┐   │
│  │ SpamDetector│  │ OneBotWS     │  │ PendingRequests   │   │
│  │ (防刷检测)  │  │ (API 封装)   │  │ (异步应答映射)    │  │
│  └─────────────┘  └──────────────┘  └───────────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │ dispatch()
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   HandlerRegistry (责任链)                  │
│                                                             │
│  Hello → Luck → Joke → Reminder → Sanjiao → DailyProfession │
│  → DailyCp → Rank → EggGroupSearch → Agent → Merchant → AI │
│                                                             │
│  命中即停止 (first-match wins)                              │
└──────────────────────────┬──────────────────────────────────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ BaiLianService│  │ AgentService │  │ 24 × Tool   │
│ (日常对话)    │  │ (智能助手)   │  │ (工具调用)   │
│ glm-5.1      │  │ gemini-3-flash│  │              │
└──────┬───────┘  └──────┬───────┘  └──────────────┘
       │                 │
       └────────┬────────┘
                ▼
┌─────────────────────────────────────────────────────────────┐
│                    数据 & 基础设施层                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │ MySQL    │  │ HikariCP │  │ HanLP    │  │ Java2D     │  │
│  │ (H2 兼容) │  │ (连接池)  │  │ (NLP)    │  │ (图像渲染)  │  │
│  └──────────┘  └──────────┘  └──────────┘  └────────────┘  │
└─────────────────────────────────────────────────────────────┘
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
| **BaiLianService** | 双模型 AI 核心：glm-5.1 负责日常聊天，gemini-3-flash 负责 Agent/工具调用。包含多模态上下文管理、RAG 知识库增强、主动插话决策、频率控制、人设注入 |
| **AgentService** | 智能助手决策引擎：接收"请帮我"请求，交由 AI 决策工具调用链路（24 个工具注册于 BaiLianService） |
| **BotMoodService** | 分群情绪系统：每群独立维护 0–100 心情值 (低落/平静/开心/兴奋)，持久化至 `group_mood` 表。冷场检测 + 自动话题投放 |
| **UserPortraitService** | 用户画像引擎：定时批处理分析聊天记录，生成兴趣标签，动态调整好感度 (–5 ~ +5/次)，持久化至 `user_profiles` / `user_affinity` |
| **ReminderService** | 提醒调度引擎：`ScheduledThreadPoolExecutor` (3 线程) 驱动，支持周期/单次/每日/延迟四种模式。用户回复私聊自动停止 |
| **TtsService** | 语音合成：调用 `text-to-speech.cn` API，支持重试 (3 次)、Token 缓存、MP3 输出 |
| **MerchantApiService** | 远行商人 API 服务：定时拉取商品数据，缓存至数据库，支持高价值物资筛选与通知推送 |
| **WebScreenshotService** | 网页截图：调用外部 Python 脚本异步截图，自动清理临时文件 |
| **SpamDetector** | 防刷检测：群聊消息频率监控与打断 |
| **GroupSerialExecutor** | 群聊串行执行器：同群内 AI 调用与游戏逻辑串行化，避免竞态 |
| **KeywordKnowledgeService** | 关键词知识库：基于关键词的快速问答匹配 |
| **BotMemoryService** | 短期记忆：记录 AI 近期行为 (说了什么、调了什么工具) |
| **ConversationService** | 对话上下文管理 |
| **PersonalityService** | 人格化回复风格调整 |

---

### 3.4 图像渲染

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
- 手动依赖注入 — 无 Spring/DI 框架，`Main` 构造函数负责全量装配
- Repository 模式 — `BaseRepository` 提供连接管理与重试逻辑，手写 SQL，无 ORM
- 责任链模式 — Handler 按优先级排列，首匹配即处理
- 异步应答 — `CompletableFuture` + `ConcurrentHashMap` 将 WebSocket 异步通信转为请求-响应模型
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
git clone <repo-url> && cd untitled

# 构建 fat JAR
mvn clean package -DskipTests
# 产物: target/untitled-1.0-SNAPSHOT.jar
```

### 5.3 配置文件

在 `src/main/resources/application.properties` 中配置运行参数。敏感值通过环境变量注入：

```properties
# NapCat WebSocket 地址
bot.ws-url=ws://127.0.0.1:3001

# 机器人 QQ 号
bot.qq=123456789

# 允许的群 (逗号分隔)
bot.allowed-groups=123456,789012

# AI 对话模型
bailian.api-key=${BAILIAN_API_KEY}
bailian.base-url=https://api.scnet.cn/v1
bailian.model=glm-5.1

# AI Agent 模型
agent.api-key=${AGENT_API_KEY}
agent.base-url=https://api.scnet.cn/v1
agent.model=gemini-3-flash

# 数据库
datasource.url=jdbc:mysql://localhost:3306/qq_bot
datasource.username=${DB_USER}
datasource.password=${DB_PASSWORD}
```

### 5.4 本地运行

```bash
# 确保环境变量已设置
export BAILIAN_API_KEY=xxx
export AGENT_API_KEY=xxx
export DB_USER=root
export DB_PASSWORD=xxx

# 启动
java -Xms256m -Xmx768m -jar target/untitled-1.0-SNAPSHOT.jar
```

---

## 6. 配置参考

### 6.1 环境变量

| 变量 | 说明 | 必填 |
|------|------|:---:|
| `BAILIAN_API_KEY` | 日常对话模型 API Key | ✓ |
| `AGENT_API_KEY` | Agent 模型 API Key | ✓ |
| `DB_USER` | 数据库用户名 | ✓ |
| `DB_PASSWORD` | 数据库密码 | ✓ |
| `SSH_KEY` | 部署 SSH 私钥路径 | 部署时 |

### 6.2 关键参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `bot.allowed-groups` | — | 白名单群号列表 |
| `bot.private-whitelist-enabled` | false | 是否启用私聊白名单 |
| `USER_REACTION_COOLDOWN_MS` | 2000 | 同用户主动插话冷却 (ms) |
| `MAX_QUEUE_MS` | 30000 | 排队超时丢弃阈值 |
| 群冷场检测 | 5 min | 无消息判定冷场 |
| 追问窗口 | 2 min | 追问识别窗口 |
| 职业抽卡防刷 | 30 s | 同群最小间隔 |

---

## 7. 部署与运维

### 7.1 一键部署

```bash
chmod +x deploy.sh && ./deploy.sh
```

脚本执行流程：
1. Maven 打包 (skip tests)
2. 上传 JAR 至远程服务器 (`/opt/qq-bot/`)
3. 停止旧实例 (screen)
4. 替换 JAR (保留最近 3 个备份)
5. 加载 `.env` 环境变量并启动 screen 会话

### 7.2 远程服务器结构

```
/opt/qq-bot/
├── untitled-1.0-SNAPSHOT.jar      # 当前运行 JAR
├── untitled-1.0-SNAPSHOT.jar.bak.* # 历史备份 (保留 3 个)
├── .env                            # 环境变量 (API Keys, DB 密码等)
└── qq-bot.log                      # 运行日志
```

### 7.3 运维命令

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

### 7.4 数据库表

核心业务表：

| 表名 | 用途 |
|------|------|
| `user_profiles` | 用户画像 (兴趣标签、分析时间) |
| `user_affinity` | 用户好感度 (0–100，含变化日志) |
| `group_mood` | 分群情绪值 |
| `group_message_stats` | 群消息统计 (发言数) |
| `long_term_memory` | 长期记忆与定时事件 |
| `keyword_knowledge` | 关键词知识库 |
| `merchant_cache` | 远行商人商品缓存 |
| `merchant_subscription` | 远行商人订阅记录 |
| `users` | 用户昵称缓存 |
| `messages` | 原始消息归档 |

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
src/main/java/com/start/
├── Main.java              # 入口 + WebSocket 生命周期
├── config/
│   ├── BotConfig.java     # 配置加载 (含环境变量替换)
│   └── DatabaseConfig.java # 数据源配置
├── handler/               # 消息处理器 (责任链节点)
├── agent/                 # AI 工具实现 (Tool 接口)
├── service/               # 核心业务服务
├── repository/            # 数据访问层 (BaseRepository)
├── model/                 # 数据模型
├── vision/                # 图像渲染 (Java2D)
└── util/                  # 工具类
```

### 8.4 调试

```bash
# 日志级别 (src/main/resources/logback.xml)
# com.start 包默认 DEBUG 级别
# 查看完整 WebSocket 事件与 AI 调用链路

# 本地调试时可将 NapCat 指向本地 WebSocket
bot.ws-url=ws://127.0.0.1:3001
```

---

## 许可证

本项目采用 **MIT License** 开源。使用者应遵守 QQ 平台及 OneBot 协议的相关规定。

Copyright (c) 2025 糖果熊 (CandyBear)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

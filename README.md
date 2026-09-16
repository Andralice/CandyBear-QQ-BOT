# 糖果熊 QQ Bot

`untitled/` 是本项目当前的实际 Maven 工程。它是一个基于 Java 17 的独立 QQ 机器人进程：通过 NapCat 提供的 OneBot v11 WebSocket 接收事件，通过 OneBot API 发送消息，使用 MySQL 保存消息、证据、记忆和运行状态，并按配置调用 OpenAI 兼容或 Anthropic Messages 兼容的大模型。

当前入口是 `com.start.Main`，不是 Spring Boot 应用。构建结果是可执行的 shaded JAR。

## 能做什么

- 群聊和私聊消息接收、解析、白名单控制和回复发送。
- `#ai`、`!ai`、`！ai` 前缀和 QQ `@` 触发的主模型对话；群聊还会经过对话事件识别、群聊节奏和主动插话策略。
- 会话历史、群公共上下文、用户画像、长期记忆、会话 belief、Thread 场景和 Working Memory 的组合式上下文。
- 按本轮意图加载工具和规则，而不是每次都把所有可选能力完整暴露给模型。工具加载不会绕过授权检查。
- 图片下载、Vision 描述、可选的主模型多模态输入、链接预览、文件和转发证据分析。
- 表情包自动收集、关键词校正、内置 face 兜底和 Dashboard 审阅。
- 天气、网页搜索/抓取、知识库、提醒和周期任务、语音、戳一戳、群/私聊/文件发送等工具。
- 幸运值、职业、PK、排行榜、三角洲行动、洛克王国蛋组/商人等游戏和群互动功能。
- 可选的 Agnes 图片/视频生成和火山引擎语音合成。
- 糖果熊人生引擎、群情绪、异常日志监控、决策追踪和内嵌 Web Dashboard。

## 运行链路

```text
NapCat / OneBot v11
        |
        | WebSocket 事件
        v
com.start.Main
        |
        +--> HandlerRegistry：按注册顺序匹配，第一个命中的 Handler 获得处理权
        |       |
        |       +--> 直接 Handler：运维、运势、笑话、提醒、游戏、商人等
        |       |
        |       +--> AIHandler：缓冲消息、识别事件、组织群聊和 AI 生成
        |
        +--> GroupSerialExecutor：按会话/群串行执行生成任务
        |
        +--> BaiLianService
        |       +--> ProfileProvider / Knowledge / Mood / Thread / Memory 上下文
        |       +--> PromptBuilder + RuleSet + CapabilityTools
        |       +--> LlmProtocol：OpenAI 或 Anthropic 请求/响应格式
        |       +--> 工具调用循环，最多 20 轮
        |
        +--> OneBot 发送回执
        |       +--> 发送成功后持久化机器人回复
        |       +--> ConversationRuntime 发布追踪、指标和表情包事件
        |
        +--> MySQL：消息、媒体证据、画像、记忆、任务和审计数据
```

启动顺序由 `Main.main()` 和 `BotBootstrap` 控制：

1. `BotBootstrap.validateConfiguration()` 先检查启动必填项。
2. `Main` 初始化 HikariCP，并由 `DatabaseConfig.ensureTables()` 执行幂等建表/加列。
3. `BotBootstrap.wireServices()` 装配 OneBot、AI、工具、运行时事件总线和后台服务。
4. 连接 NapCat WebSocket，然后启动主动插话、证据分析、保留清理、提醒、周期任务、异常监控、人生引擎和 Dashboard。

## 触发和分发

`HandlerRegistry` 当前按以下顺序注册 Handler：

1. `ShellHandler`
2. `LuckHandler`
3. `JokeHandler`
4. `ReminderHandler`
5. `SanjiaoHandler`
6. `DailyProfessionHandler`
7. `DailyCpHandler`
8. `RankHandler`
9. `EggGroupSearchHandler`
10. `TravelingMerchantHandler`
11. `ProfessionPKHandler`
12. `AIHandler`

因此，直接命令会先于 AI 处理。当前代码中的几个明确入口如下：

- 管理员私聊使用 `>` 前缀执行受策略限制的运维命令，使用 `确认执行 <token>` 确认待执行命令。
- 管理员私聊使用 `/remind on|off|user|at|daily` 管理提醒。
- 精确文本 `幸运值`、`运势`、`今日魔咒`、`魔咒` 进入运势 Handler。
- 精确文本 `讲个笑话`、`来个笑话`、`/joke` 进入笑话 Handler。
- 私聊的普通非空消息进入 AI Handler；以其他 `!` 开头的命令会让给其他命令路径。
- 未被前序 Handler 消费的群消息最终进入 AIHandler，但是否真正调用模型由显式 `@`/前缀、对话解释结果、主动插话策略和冷却状态共同决定。

## AI 上下文和工具

主模型请求由 `BaiLianService`、`PromptBuilder` 和 `CapabilityTools` 共同构造，主要来源包括：

- 当前时间、当前用户昵称/QQ、群号、真实 `@` 用户和群成员 QQ 映射。
- 当前会话最近消息；初始请求最多带历史快照的最后 4 条消息，并带 `[MM-dd HH:mm]` 时间戳供模型判断时序。
- 当前群公共消息窗口、群聊节奏、糖果熊情绪、群聊认知结果和主动插话视角。
- 与当前用户和群范围匹配的证据画像、长期记忆、知识库命中、会话 belief、Thread 场景和 Working Memory。
- 当前消息引用的原文、图片的 Vision 描述、链接预览、待处理文件和本轮能力线索。
- 基础规则集、事实边界、回复约束、工具规范和按需加载的能力规则。

工具分为普通工具、可按需加载的能力工具和管理员工具。管理员专属工具只有固定所有者 QQ `3524398813` 会收到，包含 Shell、自进化、重启、运行时配置、白名单、读写代码和审计相关能力。工具审计记录写入 `tool_audit_logs`。

`BAILIAN_PROTOCOL`/`bailian.protocol` 支持 `openai` 和 `anthropic`。主模型候选池开启后，首次请求可以在候选线路之间故障转移；一旦本轮已经执行工具，后续整理会固定当前线路，避免跨模型重放有副作用的工具。

图片有两条彼此独立的路径：

- `vision.enabled=true` 时，独立 Vision 服务负责描述图片并触发表情包收集事件。
- `bailian.multimodal.enabled` 默认 `false`。开启后，当前图片还会以 `image_url` 传给主模型；它不会替代独立的 Vision/表情包路径。

群聊并行认知由 `group-cognition.mode` 控制：`off`、`shadow`、`assist`、`enforce`。默认是 `off`；`shadow` 只记录分析结果，`assist` 把视角注入已经发生的回复，`enforce` 才允许认知结果参与普通触发决策。

## 项目结构

```text
untitled/
├─ pom.xml
├─ src/main/java/com/start/
│  ├─ Main.java                 OneBot WebSocket 主入口和发送回执
│  ├─ BotBootstrap.java         服务装配、启动自检和后台任务
│  ├─ config/                   配置与数据库连接池
│  ├─ handler/                  OneBot 消息 Handler 和命令分发
│  ├─ agent/                    LLM 工具实现及管理员/社交工具
│  ├─ service/                  AI、对话、证据、记忆、游戏和外部服务
│  ├─ repository/               MySQL 持久化访问
│  ├─ runtime/                  对话事件总线、会话 DTO、追踪和 Dashboard
│  ├─ thread/                   群聊 Thread、场景和注意力计算
│  ├─ memory/                   长期记忆解释、时间和证据渲染
│  ├─ model/                    持久化模型和运行时数据对象
│  ├─ vision/                   图片下载、卡片和结果渲染
│  └─ util/                     环境变量、限流、重试和消息辅助函数
├─ src/main/resources/
│  ├─ application.properties.example
│  ├─ assets/                   背景图和字体
│  ├─ pets/                     宠物数据
│  ├─ stickers/                 表情包资源
│  └─ *.py                     截图和语音辅助脚本
├─ src/test/java/               JUnit 5 单元测试和可选数据库集成测试
├─ deploy.sh                    构建、上传并重启远端 JAR 的环境脚本
└─ target/                      Maven 构建产物，不纳入版本控制
```

## 环境要求

- Java 17。
- Maven；项目没有 Maven Wrapper。
- NapCat，并启用 OneBot v11 WebSocket 服务。
- 可访问的 MySQL 8.x 数据库。代码默认连接 `localhost:3307/candybear_db`，不是常见的 `3306`；请显式配置实际端口。
- 一个主模型 API Key。主模型需要提供项目使用的 OpenAI Chat Completions 或 Anthropic Messages 兼容接口。
- 如果启用图片、语音、网页、商人或媒体能力，还需要对应的外部服务和凭据。

## 配置

`BotConfig` 从 classpath 的 `application.properties` 读取小写属性键。该文件不在 Git 中，打包前必须创建：

```powershell
Copy-Item untitled/src/main/resources/application.properties.example `
  untitled/src/main/resources/application.properties
```

但现有 `.example` 文件同时承担环境变量清单的作用，不能把其中的大写 `KEY=value` 行直接当作 `BotConfig` 的小写属性键。推荐创建如下形式的实际配置文件，用 `${ENV_NAME:default}` 让 `EnvResolver` 从环境变量取值：

```properties
bot.qq=${BOT_QQ}
bot.name=糖果熊

ws.url=${NAPCT_WS_URL:ws://127.0.0.1:5700}
onebot.http-base-url=${ONEBOT_HTTP_BASE_URL:http://127.0.0.1:5700}
onebot.access-token=${ONEBOT_ACCESS_TOKEN:}
allowed.groups=${ALLOWED_GROUPS}

private.whitelist.enabled=${PRIVATE_WHITELIST_ENABLED:false}
allowed.private.users=${ALLOWED_PRIVATE_USERS:}
private.blacklist=${PRIVATE_BLACKLIST:}

bailian.api-key=${BAILIAN_API_KEY}
bailian.base-url=${BAILIAN_BASE_URL:https://api.deepseek.com/v1/chat/completions}
bailian.protocol=${BAILIAN_PROTOCOL:openai}
bailian.chat-model=${BAILIAN_CHAT_MODEL:deepseek-v4-pro}
bailian.multimodal.enabled=${BAILIAN_MULTIMODAL_ENABLED:false}

database.url=${DB_URL:jdbc:mysql://127.0.0.1:3307/candybear_db}
database.user=${DB_USER:candybear}
database.password=${DB_PASSWORD}
```

启动自检会拒绝以下缺失项：`bot.qq`、`bailian.api-key`、`ws.url`、非空的 `allowed.groups`；开启私聊白名单时还必须有 `allowed.private.users`。`application.properties` 缺失则会在配置加载阶段直接失败。

管理员配置不是任意可改的角色配置。代码固定所有者管理员 QQ 为 `3524398813`，`admin.qq` 配置成其他号码会被忽略。

### 常用可选配置

| 领域 | 主要属性 | 默认/说明 |
| --- | --- | --- |
| 主模型候选池 | `bailian.pool.enabled`、`bailian.pool.candidates`、`bailian.pool.probe-interval-ms`、`bailian.pool.probe-timeout-ms` | 默认关闭；候选格式为 `id\|base_url\|model\|protocol\|api_key_env`，多条用分号分隔 |
| 群聊认知 | `group-cognition.mode`、`group-cognition.groups`、`group-cognition.*` | 默认 `off`；未指定独立 endpoint 时继承主模型 endpoint/protocol |
| Vision | `vision.enabled`、`vision.api-key`、`vision.base-url`、`vision.model` | 默认开启；默认复用主模型 Key/地址，模型默认 `qwen-vl-max` |
| 主模型看图 | `bailian.multimodal.enabled` | 默认关闭；开启后当前原图进入主模型请求 |
| Agnes 媒体 | `agnes.enabled`、`agnes.api-key`、`agnes.*` | 默认关闭；代码会先按配置检查免费定价，再提供图片/视频工具 |
| 语音 | `tts.*`、`volc.tts.*` | 默认使用配置的 TTS 服务；火山 TTS 默认关闭 |
| Dashboard | 环境变量 `DASHBOARD_HOST`、`DASHBOARD_PORT`、`DASHBOARD_TOKEN`，或 JVM 系统属性 `dashboard.host` 等 | 默认监听 `127.0.0.1:8765`；非回环地址必须配置 Token |
| 网页搜索 | `web.search.url`、`web.search.backend` | 由 `WebSearchTool` 使用 |
| 审计模型 | `audit.api-key`、`audit.base-url`、`audit.model`、`audit.timeout-ms` | 用于部分审计/监控路径，不等同于主聊天模型 |
| Working Memory | `working_memory.default_expire_hours` | 默认 24 小时 |

Dashboard 的配置由 `WebDashboardListener` 直接读取环境变量或 JVM 系统属性，不读取 `BotConfig` 的 classpath properties。对外监听时必须设置 `DASHBOARD_TOKEN`。

## 构建和运行

在工程目录执行：

```powershell
cd D:\解压\A-QQ-bot-based-on-Java-and-NapCat\untitled
mvn test
mvn package -DskipTests
java -jar target/untitled-1.0-SNAPSHOT.jar
```

Linux/macOS 使用同样的 Maven 命令；Windows 也可以把 `mvn` 替换为 `mvn.cmd`。

`Main.main()` 会先做配置自检，然后初始化数据库、连接 NapCat WebSocket、启动后台任务并保持 JVM 运行。正常关闭时会停止证据 worker、对话执行器、Dashboard、WebSocket 和数据库连接池。

`deploy.sh` 不是通用部署脚本：它包含固定的远端地址 `/opt/qq-bot`、`screen` 会话名和 SSH 部署流程，会执行 `mvn clean test package`、停止远程旧进程、替换 JAR 并重启。运行前必须检查脚本中的目标主机、SSH 密钥和远端配置；密钥应放在环境变量或远端 `.env`，不要写入代码库。

## Dashboard

默认地址为 `http://127.0.0.1:8765/`。面板展示决策轨迹、群指标、工具调用排行和表情包审阅；决策记录保存在进程内存中，最多保留 300 条。

可用 HTTP 路径：

- `GET /`：内嵌 Dashboard 页面。
- `GET /api/system`：运行时间、消息/回复/静默/错误计数、堆内存、WebSocket 健康、迁移结果、调度器指标和工具排行。
- `GET /api/decisions?limit=100`：最近决策记录。
- `GET /api/groups`：群消息、回复、静默、错误、Token 和延迟汇总。
- `GET /api/stickers`：表情包列表，可用 `keyword` 筛选。
- `GET /api/stickers/image?id=...`：图片预览。
- `PATCH` 或 `PUT /api/stickers/<id>`：更新关键词。
- `DELETE /api/stickers/<id>`：删除表情包及其本地图片。

启用 Token 后，可通过 `X-Dashboard-Token` 请求头或 URL 查询参数 `?token=...` 鉴权。不要把 Dashboard 绑定到公网地址而不设置 Token。

## 数据和持久化

数据库初始化由 `DatabaseConfig` 完成，当前源码定义了消息、消息部件、媒体产物、转发边、对话事件、模型分析任务、画像候选/事实/证据/冲突、群氛围、长期记忆、知识库、群情绪、人生引擎、提醒、周期任务、职业/PK、配置和工具审计等表。

消息链路保留 OneBot 原始事件和有序消息部件；图片、文件和转发内容先作为证据或分析任务处理，画像流水线再生成候选事实和带来源的事实。机器人回复只有在 OneBot 发送得到成功回执后才写入回复记录。

## 测试

默认测试使用 JUnit 5：

```powershell
cd D:\解压\A-QQ-bot-based-on-Java-and-NapCat\untitled
mvn test
mvn -Dtest=PromptBuilderGroundingTest test
```

带 MySQL 的集成测试显式设置 `RUN_DB_TESTS=true` 后再单独运行，例如：

```powershell
$env:RUN_DB_TESTS = "true"
mvn -Dtest=EvidenceProfileIntegrationTest test
```

默认测试不等同于真实 NapCat、OneBot、图片 Vision 或生产模型的端到端验证；这些外部链路仍需要在隔离环境中单独验证。

## 安全边界

- `application.properties`、`.env`、日志和数据库凭据不应提交到 Git。
- 群聊由 `allowed.groups` 控制；私聊可以通过 `private.whitelist.enabled` 和 `allowed.private.users` 限制。
- 运维 Shell、代码读写、自进化、重启和白名单工具只向固定管理员加载，并仍受工具/命令策略约束。
- 工具调用、发送结果、媒体处理和画像事实应以实际返回状态为准；模型文本不能替代 OneBot 回执或数据库证据。
- 生产部署前应核对 JAR、环境变量、NapCat 连接、数据库状态和 Dashboard 鉴权，不要仅凭本地构建成功判断运行链路可用。

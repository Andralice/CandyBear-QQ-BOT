---
description: 糖果熊QQ机器人项目开发规范
globs: **/*.java, **/*.properties
alwaysApply: true
---

# 糖果熊项目开发规范

## 项目上下文
- 无框架纯 Java 17 项目，手动 DI（Main 构造函数装配所有服务）
- Maven + Shade Plugin（fat JAR），无 Spring/DI 框架
- WebSocket client 连接 NapCat OneBot v11 协议
- MySQL 8.0+ / HikariCP 连接池 / 手写 JDBC
- SLF4J + Logback 日志
- 架构：责任链 Handler 模式 + Repository 模式 + Tool 接口

## 代码风格

### 命名
- 类名：大驼峰（PascalCase），如 `DailyProfessionHandler`
- 方法/变量：小驼峰（camelCase），如 `getCombatPower`
- 常量：全大写下划线分隔（UPPER_SNAKE_CASE），如 `MAX_QUEUE_MS`
- 包名：全小写，按层级：`com.start.handler`, `com.start.service`, `com.start.repository`, `com.start.model`, `com.start.agent`, `com.start.util`
- 数据库列名：snake_case（如 `combat_power`, `user_id`）

### 缩进与格式
- 使用 4 个空格缩进，不使用 Tab
- 每行不超过 160 字符（可适当放宽）
- import 使用显式导入，禁止通配符 `import java.time.*`

### 注释
- 类用简洁 Javadoc：`/** 一句话描述 */`
- 方法用单行：`/** 一句话描述 */`
- 内部逻辑用单行 `//` 注释说明 WHY，不说 WHAT
- 禁止在文件首行写 `// filename.java` 这类文件路径注释

### 日志
- Logger 变量名统一使用 `logger`，不使用 `log`
- Logger 声明使用自己的 class：`LoggerFactory.getLogger(MyClass.class)`
- 禁止使用 `LoggerFactory.getLogger(Main.class)` 在其他类中

## 架构约束

### Handler 层（`handler/`）
- 实现 `MessageHandler` 接口（`match` + `handle`）
- 在 `HandlerRegistry` 构造函数中按优先级注册，首匹配即处理
- Handler 不直接操作数据库，通过 Service 或 Repository 访问
- `AIHandler` 作为兜底 Handler，排在注册链最后

### Service 层（`service/`）
- 包含业务逻辑，不含 WebSocket/HTTP 协议细节
- 通过构造函数接收依赖（手动 DI）
- 单例服务使用 `getInstance()` 静态方法（如 `ReminderService`, `ImageRenderer`）

### Repository 层（`repository/`）
- 两种模式任选其一，但同一项目内尽量保持一致：
  - **推荐**：直接使用 `DataSource` + try-with-resources（如 `UserProfessionRepository`）
  - 旧模式：继承 `BaseRepository` 使用 `safeExecute` 包装器（逐步迁移到新模式）
- 表初始化在构造函数或 `initTables()` 中完成
- SQL 手写，参数用 `?` 占位符 + `PreparedStatement`

### Agent/Tool 层（`agent/`）
- 实现 `Tool` 接口：`getName()`, `getDescription()`, `getParameters()`, `execute(Map)`
- `getParameters()` 返回 JSON Schema 的 `Map<String, Object>` 表示
- 子包：`agent/evo/` 自我进化工具，`agent/social/` 社交互动工具

### Model 层（`model/`）
- 纯 POJO，字段私有 + public getter/setter
- 数据库时间字段用 `LocalDateTime` / `LocalDate`

## 依赖注入（手动 DI）
- `Main` 构造函数调用 `BotBootstrap.wireServices(this)` 装配所有服务
- 新增服务在 `BotBootstrap` 中创建实例并注入
- 避免在业务代码中 `new` 创建 Service/Repository（应通过构造函数传入或 DI 装配）

## 安全约束
- 敏感配置（API Key、Token）**一律**用环境变量 `${ENV_VAR:default}` 格式
- 不硬编码任何密钥到 `application.properties`
- `CommandPolicy` 的白名单/黑名单/确认名单必须保持更新
- `SelfEvolveTool` 内置硬阻断：禁止修改 `BotConfig.java`、`CommandPolicy.java`、`*.properties`、`*.env`

## 线程安全
- 共享状态使用 `ConcurrentHashMap`
- 异步 AI 调用使用 `GroupSerialExecutor`（群内串行）
- OneBot API 异步转同步使用 `CompletableFuture` + `ConcurrentHashMap` pending queue

## 示例

### 正确的 Handler 示例
```java
package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 处理「你好」问候
 */
public class HelloHandler implements MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(HelloHandler.class);

    @Override
    public boolean match(JsonNode msg) {
        String text = com.start.util.MessageUtil.extractPlainText(msg.path("message"));
        return "你好".equals(text.trim());
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        bot.sendReply(msg, "你好！我是糖果熊~");
    }
}
```

### 正确的 Repository 示例
```java
package com.start.repository;

import javax.sql.DataSource;
import java.sql.*;

public class MyRepository {
    private final DataSource dataSource;

    public MyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public MyModel findById(long id) throws SQLException {
        String sql = "SELECT * FROM my_table WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        }
        return null;
    }
}
```

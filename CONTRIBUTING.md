# 贡献指南

感谢你对糖果熊的关注！欢迎参与贡献。

## 准备工作

1. Fork 本仓库
2. 克隆你的 Fork 到本地
3. 按照 [README](./README.md#5-快速开始) 配置开发环境

```bash
git clone https://github.com/YOUR_USERNAME/A-QQ-bot-based-on-Java-and-NapCat.git
cd A-QQ-bot-based-on-Java-and-NapCat/untitled
```

## 开发规范

### 分支命名

- `feature/xxx` — 新功能
- `fix/xxx` — Bug 修复
- `refactor/xxx` — 代码重构
- `docs/xxx` — 文档更新

### 代码风格

- 使用 Java 17 语法
- 文件编码统一 UTF-8
- 日志使用 SLF4J，不直接使用 `System.out`
- 敏感信息（API Key、Token）一律通过 `${ENV_VAR:default}` 注入，不硬编码

### 架构约定

项目采用手动 DI + 责任链模式：

- **新增 Handler**：实现 `MessageHandler` 接口，在 `HandlerRegistry` 构造器中按优先级注册，AIHandler 必须排最后
- **新增 Tool**：实现 `Tool` 接口（`getName()`, `getDescription()`, `getParameters()`, `execute()`），在 `BaiLianService` 构造函数中注册
- **数据库操作**：继承 `BaseRepository`，手写 SQL

详见 [README 开发指南](./README.md#8-开发指南)。

### Commit 信息

推荐使用中文 Commit，格式：

```
类型: 简短描述

详细说明（可选）
```

类型示例：`feat:`, `fix:`, `refactor:`, `docs:`, `chore:`

## Pull Request 流程

1. 提交前确保 `mvn clean package` 构建通过
2. 推送到你的 Fork，发起 PR 到 `main` 分支
3. 填写 PR 模板中的信息
4. 等待 Review 和 CI 检查

## 沟通渠道

- [GitHub Issues](https://github.com/Andralice/A-QQ-bot-based-on-Java-and-NapCat/issues)

---

再次感谢你的贡献！

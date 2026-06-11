# 安全策略

## 报告漏洞

如果你发现安全漏洞，**请不要在公开 Issue 中提交它**。

请通过以下方式私下报告：

1. 在 GitHub 上创建一个 **非公开** 的安全通告（Security Advisory）
   - 访问 [Security 标签页](https://github.com/Andralice/A-QQ-bot-based-on-Java-and-NapCat/security/advisories/new)
2. 或者发送邮件至项目维护者

请在报告中包含以下信息：

- 漏洞的具体描述
- 复现步骤
- 受影响版本
- 可能的修复建议（如有）

我们会在 48 小时内确认收到报告，并在 30 天内公布修复方案。

## 支持版本

| 版本 | 支持状态 |
|------|----------|
| 1.0-SNAPSHOT | 当前支持 |

## 安全最佳实践

部署本项目时请注意：

- **不要将 `application.properties` 提交到公开仓库** — 该文件已被 `.gitignore` 排除
- **所有敏感信息通过环境变量注入** — 使用 `${ENV_VAR:default}` 格式
- **OneBot access_token 请使用强随机字符串**
- **数据库密码避免弱密码，建议使用专用账户**
- **LLM API Key 请设置额度告警，避免泄露后被恶意调用**

## 依赖安全

本项目核心依赖由 Maven 管理，建议定期运行：

```bash
mvn versions:display-dependency-updates
```

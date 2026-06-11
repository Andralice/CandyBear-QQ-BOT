# Changelog

本文档记录糖果熊 (CandyBear) 项目的所有重要变更。

## [1.0-SNAPSHOT] — 开发中

### 新增
- 双模型 AI 对话引擎（glm-5.1 日常聊天 + gemini-3-flash Agent 工具调用）
- 24 个 AI 可调用工具（天气、搜索、记忆、提醒、排行榜、语音、Shell 等）
- 责任链模式消息分发（12 个 Handler）
- 用户画像与好感度系统
- 分群情绪系统（0-100 心情值）
- 每日 CP 配对、职业/命格抽卡、幸运值等群互动玩法
- 洛克王国宠物数据库查询（蛋组搜索、进化链）
- 远行商人商品查询与订阅通知
- 三角洲行动网页截图
- 短期记忆与长期记忆系统
- TTS 语音合成
- 知识库 CRUD（关键词问答）
- 定时提醒（周期/单次/每日/延迟）
- Java2D 图像渲染（职业卡、CP 卡）
- 防刷检测与群聊串行执行器
- 主动插话机制（话题匹配 + 概率 + 冷却）

### 技术栈
- Java 17 + Maven + Shade Plugin
- WebSocket (OneBot v11 / NapCat)
- MySQL + HikariCP + 手写 JDBC
- HanLP 中文 NLP
- Selenium + WebDriverManager
- SLF4J + Logback

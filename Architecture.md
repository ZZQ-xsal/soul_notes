# 心灵札记 (Soul Notes) — 架构文档

## 1. 项目概述

Soul Notes 是一个**可插拔, 高度可配置的心理健康咨询基础平台** (能力点接口化 + 环境变量/配置向导组装), 当前以"高校场景预置包"发行 — 即面向大学生的多模态 AI 心理轻干预系统后端, 提供:

- **多模态输入**: 语音 (本地 Vosk 引擎同步转录) 与文字, 统一进入 `Voice -> Text -> LLM 解析 -> 情感分析` 管线
- **情感分析与可视化**: 实时计算 positive / negative / anxiety 数值, 生成前端"情绪天气预报"数据
- **共情非医学化对话**: AI 以"心声树洞"倾听者角色回应, 禁止医学诊断标签; 提示词可整体替换
- **高危预警 (Red Alert)**: 检测到自伤/自杀倾向时, 在线推送弹窗 + 机构 Webhook 冗余 + 离线热线兜底
- **平台化可插拔点**: ASR 引擎 (`IAsrEngine`) / 预警通知渠道 (`IAlertNotifier`) / 心理知识包 / 提示词 / 身份品牌, 全部经配置替换
- **结构化输出预埋 ("副医生")**: 开关开启后 AI 回复附带结构化标签, 后端拆流, 前端仅见共情文本 (默认关闭); 评估落库并提供咨询员工作台消费端 (风险队列/学生时间线/聚合统计/实时推送)

---

## 2. 技术栈

| 领域             | 技术                                                          |
|------------------|---------------------------------------------------------------|
| 运行时           | Java 25 (GraalVM CE 25), Quarkus 3.36                         |
| 本地语音识别     | Vosk — JDK 25 FFM 直连 libvosk (零 JNI/JNA)                   |
| 持久化           | Hibernate Reactive + Panache, PostgreSQL (reactive-pg-client) |
| 缓存/限流/黑名单 | Redis (quarkus-redis-client)                                  |
| 实时通信         | quarkus-websockets-next (WS) + SSE                            |
| AI               | quarkus-langchain4j-openai (声明式 `@RegisterAiService`)      |
| 认证             | quarkus-smallrye-jwt (HS256 对称密钥)                         |
| JSON             | Jackson (JsonUtils 统一封装)                                  |

---

## 3. 包结构与模块职责

```text
kurvcygnus.soulnotes/
├── Entrance.java                      # Quarkus 应用入口 (品牌 banner + Pre-Launch 编排)
├── config/
│   ├── RedisStartupConfig.java        # 启动时初始化 crisis:hotline, 提供响应式热线读取
│   ├── PromptProvider.java            # 机构提示词覆盖 + 功能契约段合并 (结构化输出管线)
│   ├── ReactiveJsonStringJdbcType.java# JSONB 真类型 JDBC 映射 (双重编码修复)
│   └── prelaunch/                     # Pre-Launch 管线 (CDI 前纯构造运行)
│       ├── IPreLaunchTask.java        # 任务端口 (Result = Issue 列表)
│       ├── ConfigValidationTask.java  # 配置规则矩阵 (AI 密钥 BLOCK / ASR WARN / 阈值域与次序 / prod 必配)
│       ├── DbValidationTask.java      # DB 五态探测 (零写入, UNREACHABLE/AUTH_FAILED/DB_MISSING/SCHEMA_MISSING 均 BLOCK)
│       ├── SetupWizard.java           # --setup 交互向导 (ASR 下载交互 / DB 五态流 / AI 模型拉取步)
│       ├── PropertyMetaParser.java    # application.properties 标签元数据解析 (@group/@name/@input...)
│       ├── IDatabaseGateway.java      # 建库/建表/探测端口 (PgGateway: Pre-Launch 期自建一次性 Vert.x 小池, SQLSTATE 五态映射)
│       ├── DbTarget.java / ProbeResult.java # 五态模型
│       ├── FieldValidator.java        # 就地校验 (scheme/数字/长度)
│       ├── ConfigWriter.java          # 双输出落盘 (config/application.properties + .env, *.bak 备份)
│       └── TerminalIO.java / TerminalRenderer.java / ConfigView.java # 终端交互与渲染
├── exception/                         # 结构化异常体系
│   ├── IStructuredThrowable.java      # tag() + cause() 基础契约
│   ├── StructuredException.java       # 具体运行时异常默认实现
│   ├── IDetailedThrowable.java        # 携带类型化数据 (causeData / asException)
│   ├── ITransactionalThrowable.java   # 可回滚/可恢复异常
│   ├── IBusinessException.java        # 业务异常门面 (含包级私有 Holder/DataHolder 实现)
│   ├── ErrorCode.java                 # 统一错误码 (HTTP 状态 + 业务码)
│   └── GlobalExceptionMapper.java     # StructuredException -> 统一 JSON 响应
├── dto/
│   ├── ApiResponse.java               # 统一响应外壳 {code, message, data}
│   └── PageRequest.java               # 分页参数 (边界 clamp + normalize: @BeanParam 缺席参数收敛默认 第1页/每页20)
├── utils/
│   ├── constants/                     # JwtConstants, ApiEndpointConstants, RedisKeyConstants, AiPromptConstants
│   ├── enums/                         # UserRole, EmotionWeatherType, WarningLevel, VoiceStatus
│   ├── filter/
│   │   └── ChatRateLimitFilter.java   # 响应式 Redis 限流 (@ServerRequestFilter)
│   ├── lint/CallerSensitive.java      # 调用者敏感注解
│   ├── JsonUtils.java                 # Jackson 单例封装
│   ├── PrintUtils.java                # 日志/格式化 (quickFormat)
│   └── TimeUtils.java                 # Asia/Shanghai 时区工具
├── ai/
│   ├── agent/                         # @RegisterAiService 声明式接口
│   │   ├── MoodAnalysisAgent.java     # 情感分析
│   │   ├── WarningDetectionAgent.java # 预警检测 (NONE/YELLOW/RED)
│   │   └── EmpatheticChatAgent.java   # 共情对话 (chatSync + TokenStream)
│   ├── asr/                           # 本地语音识别层 (可插拔引擎 + 运行时管理)
│   │   ├── IAsrEngine.java            # 引擎端口 (Uni<AsrResult> transcribe)
│   │   ├── AsrResult.java             # 转录结果 (文本 + 状态机)
│   │   ├── VoskAsrEngine.java         # Vosk 实现 (worker 池转录 + PCM 解析)
│   │   ├── VoskFFM.java               # JDK 25 FFM 直连绑定 (vosk_api.h 符号表, 零 JNI/JNA)
│   │   ├── AsrRuntimeManager.java     # 运行时目录布局权威 + 模型/动态库自动下载 (IAsrRuntimeControl)
│   │   └── IAsrRuntimeControl.java    # 向导侧控制端口 (ready/下载, 进度回调)
│   ├── dto/
│   │   ├── MoodAnalysisResult.java    # positive/negative/anxiety/weather/summary
│   │   └── WarningDetectionResult.java# warningLevel/reason/suggestedAction
│   ├── ClinicalOutputSplitter.java    # <!--soulnotes {...}--> 拆流器 (宽容正则取末块, 优雅降级)
│   ├── IModelCatalog.java             # AI 模型列表拉取端口 (向导模型选择步)
│   ├── HttpModelCatalog.java          # /models 拉取实现 (endpoint 规范化 + 扩展字段)
│   ├── tool/
│   │   ├── CrisisInterventionTool.java# RED 时返回热线信息
│   │   └── UserContextTool.java       # 近期情绪摘要 (数据库上下文工具)
│   └── retriever/
│       ├── PsychologyTipsRetriever.java # 心理小知识检索 (知识包消费方)
│       └── KnowledgePackLoader.java   # knowledge/{pack}/tips.md 块格式加载 + default 回退
├── domain/
│   ├── auth/
│   │   ├── entity/User.java
│   │   ├── dto/                       # LoginRequest / RegisterRequest / AuthResponse
│   │   ├── resource/AuthResource.java # /auth/register, /login, /logout
│   │   ├── service/                   # AuthService, TokenService
│   │   └── security/JwtAuthenticationMechanism.java
│   ├── diary/
│   │   ├── entity/MoodDiary.java      # JSONB analysis_result
│   │   ├── dto/                       # DiaryCreateRequest / DiaryListQuery / DiaryResponse / EmotionWeatherVo
│   │   ├── resource/DiaryResource.java# CRUD + /weather
│   │   └── service/                   # DiaryService, EmotionAnalysisService, EmotionWeatherService
│   ├── chat/
│   │   ├── entity/AiChatSession.java  # JSONB messages + truncate
│   │   ├── dto/                       # ChatSendRequest / ChatMessageVo / ChatSessionVo
│   │   ├── resource/ChatResource.java # /chat/send, /stream (SSE), /sessions, /sessions/{id}/messages, DELETE /sessions/{id}
│   │   └── service/ChatService.java   # 对话编排 + 预警推送
│   ├── voice/
│   │   ├── dto/                       # VoiceUploadResponse
│   │   ├── resource/VoiceResource.java# /upload (同步本地转录), /files/{id}
│   │   └── service/VoiceStorageService.java # 落盘 (worker 池文件 I/O)
│   ├── clinical/                      # 咨询员工作台 (副医生评估消费端)
│   │   ├── entity/ClinicalAssessment.java  # 评估实体 (JSONB tags 原文, NONE 不落库; 静态分页 Page.of(offset/limit, limit))
│   │   ├── dto/                       # AssessmentVo / StatsSummary
│   │   ├── resource/ClinicalResource.java  # /api/v1/clinical 三端点 (COUNSELOR/ADMIN)
│   │   ├── service/ClinicalAssessmentService.java # 落库/三视图/清理 + 脱敏统一出口 (REST/WS 共用)
│   │   ├── RevealPolicy.java          # 风险分级实名解锁 (未解锁 = UUID 前 8 位稳定短码)
│   │   └── ClinicalRetentionCleaner.java  # 启动时异步保留期清理 (<=0 禁用)
│   └── crisis/
│       └── CrisisResource.java        # GET /crisis/hotline (离线兜底)
└── websocket/
    ├── WebSocketAuthUpgradeCheck.java # HttpUpgradeCheck JWT 认证网关 (/ws/clinical 前缀额外 COUNSELOR/ADMIN 断言 403)
    ├── ChatWebSocket.java             # /ws/chat 流式文本推送
    ├── AlertWebSocket.java            # /ws/alert RED 预警推送 (WebSocketAlertNotifier)
    ├── ClinicalFeedHub.java           # 工作台推送枢纽 (register/unregister 条件移除防重连竞态, broadcast fire-and-forget)
    ├── ClinicalFeedWebSocket.java     # /ws/clinical/feed 工作台实时推送端点
    ├── IAlertNotifier.java            # 预警通知渠道端口 (Uni<Void> notify)
    ├── WebSocketAlertNotifier.java    # 在线前端渠道 (总是启用)
    ├── WebhookAlertNotifier.java      # 机构服务端渠道 (URL 空 = 禁用, fire-and-forget 3s)
    ├── SmsAlertNotifier.java          # 阿里云短信渠道 (五键齐备才启用, 逐号群发值班咨询员)
    ├── AliyunSmsSigner.java           # 阿里云 RPC 签名纯函数 (零 SDK 直连)
    ├── DingTalkAlertNotifier.java     # 钉钉群机器人渠道 (markdown 报文, 可选加签)
    └── WeComAlertNotifier.java        # 企业微信群机器人渠道 (markdown 报文)
```

> **说明**: 早期架构中的 `JwtConfig` / `CorsConfig` / `AiModelConfig` / `RedisConfig` 为无消费方的死代码,
> 已删除 (配置直接通过 `@ConfigProperty` / `application.properties` 注入). `ValidationExceptionMapper` 因未引入 Bean
> Validation 依赖而不存在, 参数校验由 `GlobalExceptionMapper` 兜底或业务层显式校验.

---

## 4. 认证与安全设计

### 4.1 JWT (HS256 对称密钥)

- 签发 (`TokenService.generateToken`): `Jwt.issuer("soul-notes").subject(userId).groups(role).jti(uuid)...signWithSecret(secret)`
  - **必须设置 `jti`**, 否则黑名单 (登出) 无法定位 token
  - **不设置 `upn`**: 资源层用 `getPrincipal().getName()` 解析 userId (即 `sub`, UUID), 若设 upn 会得到用户名导致 `UUID.fromString` 崩溃
- 验签 (`JwtAuthenticationMechanism` / `WebSocketAuthUpgradeCheck`): `jwtParser.verify(token, secret)`
  - 必须显式传入与签发一致的 secret, 否则依赖未配置的 `mp.jwt.verify.*` 公钥会失败
- **配置要求**:
  - `jwt.secret` (生产经 `SOULNOTES_JWT_SECRET` 环境变量注入, ≥32 字节, 未配置时启动 fail-fast)
  - `mp.jwt.verify.issuer` 与 `TokenService` 签发的 iss claim **共用 `SOULNOTES_JWT_ISSUER` 一个键** (默认 `soul-notes`, `JwtConstants` 常量已退役): 单一属性同源喂给签发方与验签方, 更换即双端同步 — 中途更换将使全部已发 Token 立即失效

### 4.2 黑名单登出

- 登出: `POST /auth/logout` -> `invalidateToken` -> `SETEX jwt:blacklist:{jti}` (TTL = token 剩余有效期)
- 校验: 每次认证查询 `isBlacklisted(jti)`, 命中则拒绝
- **jti 提取统一解析 payload 的 `jti` claim**, 与签发时写入的 jti 对齐

### 4.3 角色

- 注册仅允许 `STUDENT` 角色 (`RegisterRequest.role != STUDENT` 返回 400), 防提权
- 资源层用 `@RolesAllowed("STUDENT")`, 从 JWT `groups` 声明解析

### 4.4 密码

- **密码哈希**: 已落地 PBKDF2WithHmacSHA256 (`AuthService.hashPassword`, 210k 迭代, OWASP 推荐值, 存储格式 `pbkdf2$<iterations>$<salt>$<hash>`)
- 原型遗留的 SHA-256 无盐哈希仍可验证 (`AuthService.verifyPassword` 按无前缀回退校验), 建议该批用户登录成功后重哈希迁移
- 已含长度/字符集/特殊字符复杂度校验

---

## 5. 响应式并发约束 (重要实现约定)

Quarkus + Hibernate Reactive 要求所有 DB 操作在**打开 Session 的 Vert.x 事件循环线程**上执行:

- 阻塞 AI 调用必须用 `vertx.executeBlocking(() -> blockingCall(), false)` — 在 worker 执行, 结果在事件循环回调
- **禁止** `Uni.item(supplier).runSubscriptionOn(worker)` 后直接做 DB 操作, 会触发 `HR000068/HR000069` (Session 跨线程)
- 查询型接口需要 `@WithTransaction` 才会打开 Session (如 `EmotionWeatherService.getWeatherData`)
- 流式/后台持久化通过 `Panache.withTransaction` 独立事务完成, 禁止 `await().indefinitely()` 于资源层
- Redis 操作用响应式 API (`ReactiveRedisDataSource`), 限流过滤器为 `@ServerRequestFilter` 返回 `Uni<Response>`, 全程无阻塞

---

## 6. AI 链路设计

| 场景             | Agent                                         | 模式                                                  |
|------------------|-----------------------------------------------|-------------------------------------------------------|
| 创建日记情感分析 | `MoodAnalysisAgent` + `WarningDetectionAgent` | 后台异步 (AI 失败自动降级)                            |
| 对话             | `EmpatheticChatAgent`                         | 同步 `chatSync` + SSE `TokenStream`                   |
| 预警检测         | `WarningDetectionAgent`                       | RED 时推 `AlertWebSocket` + 持久化 `warningTriggered` |

- 系统提示词集中在 `AiPromptConstants` (情感分析 / 预警检测 / 共情对话), 经 `PromptProvider` 支持 `ai.prompt.*` 机构整体覆盖 (留空回退内置)
- 结构化输出管线 ("副医生"预埋): `SOULNOTES_CLINICAL_TAGGING=on` 时共情提示词末尾合并功能契约段 (契约段首行声明优先级最高), 回复末尾的 `<!--soulnotes {...}-->` 块由 `ClinicalOutputSplitter` 拆流 — 落库/返回均为剔除后的正文, 前端仅见文本; 解析失败整条透传 (默认关闭, 控每条消息 token 成本)
- 工具: `CrisisInterventionTool` (RED 热线), `UserContextTool` (近期情绪摘要)
- 心理小知识: `PsychologyTipsRetriever` 消费 `KnowledgePackLoader` 加载的知识包 (`knowledge/{pack}/tips.md`, 缺失回退 `default`, 内置 13 条)
- 模型目录: `HttpModelCatalog` 拉取服务端 `/models` 列表 (向导模型选择步), endpoint 规范化, 扩展字段 (上下文长度/思考能力) 有则显示
- 配置经 `quarkus.langchain4j.openai.*`; AI 不可用时走兜底 (聊天返回"走神"兜底文案, 日记跳过 analysisResult)

---

## 7. 语音链路 (本地 ASR)

1. `POST /api/v1/voice/upload` (multipart) -> 大小 + RIFF/WAVE 头校验 (非 WAV 落盘前拒绝) -> `VoiceStorageService.store` (worker 池文件 I/O) -> `IAsrEngine.transcribe` **同步本地转录** -> 返回 `{audioUrl, fileId, status, transcribedText}`
2. `GET /api/v1/voice/files/{fileId}` 流式返回文件 (fileId 经 UUID 校验防路径穿越)
3. 仅接受 16kHz 单声道 PCM16 WAV (前端 Web Audio 产出约束), 其他格式 `400 VOICE_FORMAT_UNSUPPORTED`; 转录失败不回 5xx (状态机标记后正常响应); 上传独立限流 (`SOULNOTES_RATE_LIMIT_VOICE`, 默认 10 次/分钟)
4. `VoskAsrEngine` 经 `VoskFFM` (JDK 25 FFM 直连, 零 JNI/JNA) 驱动 libvosk; 运行时目录布局由 `AsrRuntimeManager` 权威管理 (`<dir>/lib/` + `<dir>/model/`), 未就绪仅 WARN 不阻断启动, 可经向导自动下载或手动放置
5. 日记语音来源: `DiaryCreateRequest.audioData` (Base64) 解码 -> 落盘 -> 生成 `audioUrl`

---

## 8. WebSocket

- `WebSocketAuthUpgradeCheck`: 升级阶段校验 JWT (header 或 `token` 查询参数) + 黑名单, userId 存入 `UserData`; `/ws/clinical` 前缀端点额外断言 COUNSELOR/ADMIN 角色 (403, 以 `request.path()` 判定), 学生端 `/ws/chat` `/ws/alert` 行为不变
- `ChatWebSocket` (`/ws/chat`): 接收 JSON 消息, 订阅 `ChatService.streamMessage` 逐 token 推送 (逐消息持久化, WS 专用消息上下文)
- `ClinicalFeedWebSocket` (`/ws/clinical/feed`): 咨询员工作台实时推送 — `ClinicalFeedHub` 维护 counselorId → 连接映射, register/unregister 均携带连接实例, 注销为条件移除 (防旧连接 close 回调晚于重连 register 到达的竞态); `broadcast(String)` fire-and-forget (无在线咨询员静默跳过, 单连接失败仅 WARN), `ClinicalAssessmentService` 落库后广播 `NEW_ASSESSMENT`
- RED 预警推送渠道接口化为 `IAlertNotifier` (`ChatService` 只依赖接口, RED 触发五渠道 fan-out): `WebSocketAlertNotifier` (在线前端, `/ws/alert`, 总是启用) / `WebhookAlertNotifier` (机构服务端, URL 空 = 禁用) / `SmsAlertNotifier` (阿里云短信, 五键齐备才启用, 逐号群发值班咨询员) / `DingTalkAlertNotifier` (钉钉群机器人, webhook 空 = 禁用, 可选加签) / `WeComAlertNotifier` (企业微信群机器人, webhook 空 = 禁用), 渠道互为冗余、同构可扩展; 短信与 IM 渠道同为 fire-and-forget 3s (失败仅 WARN), IM 报文仅含学生标识 / 热线 / 截断 120 字事由, 不含 summary 全文

---

## 9. 数据流全景

```text
前端 (Vue)
├─ 日记 CRUD / 天气  ->  DiaryResource -> DiaryService / EmotionWeatherService -> PostgreSQL (analysis_result JSONB)
├─ AI 对话 (SSE/WS)  ->  ChatResource / ChatWebSocket -> ChatService -> EmpatheticChatAgent -> TokenStream (契约开启时经 ClinicalOutputSplitter 拆流)
├─ 预警              ->  WarningDetectionAgent (RED) -> IAlertNotifier 五渠道 fan-out (弹窗 + Webhook + 短信 + 钉钉 + 企微)
├─ 离线兜底           ->  CrisisResource (/crisis/hotline) <- Redis crisis:hotline <- 静态默认值
└─ 语音              ->  VoiceResource -> VoiceStorageService -> IAsrEngine (本地 Vosk 同步转录)
```

---

## 10. 关键设计决策

1. **ID 策略**: `users.id` UUID (应用层生成), `mood_diaries.id` BigInt 自增, `ai_chat_sessions.id` UUID
2. **JSONB**: 存 Java `String`, 经 `JsonUtils` 序列化/反序列化; `analysis_result` 含 positive/negative/anxiety/weather/summary/warningLevel
3. **会话历史**: `AiChatSession.truncate(50)` 限制 JSONB 无限增长
4. **天气映射**: 阈值 (`weather.threshold.*`) 外置可配, 边界统一 `>=`
5. **异常体系**: `IBusinessException.of(ErrorCode, msg, factory, tag)`, tag 用 `WHERE_WHAT_ACTION`, 禁止 `ErrorCode.name()`
6. **离线安全兜底**: 三级 (离线端点 -> Redis 缓存 -> 静态默认值), 热线不硬编码于业务组件

---

## 11. 配置清单

`application.properties` 关键项:

| 项                                                               | 说明                                                                                                                                                                                                                                                                                                                               |
|------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jwt.secret` / `SOULNOTES_JWT_SECRET`                            | 签名密钥 (≥32 字节, 生产必配)                                                                                                                                                                                                                                                                                                      |
| `mp.jwt.verify.issuer` / `SOULNOTES_JWT_ISSUER`                  | 签发者 (签发与验签同键, 默认 `soul-notes`, 更换使已发 Token 全失效)                                                                                                                                                                                                                                                                |
| `app.brand-name` / `SOULNOTES_BRAND_NAME`                        | 品牌名 (向导标题/启动 banner 首行, 不进向导清单)                                                                                                                                                                                                                                                                                   |
| `quarkus.datasource.*`                                           | PostgreSQL, 账号经 `SOULNOTES_DB_USER`/`SOULNOTES_DB_PASSWORD`, 地址经 `SOULNOTES_DB_URL` 覆盖                                                                                                                                                                                                                                     |
| `quarkus.redis.hosts` / `SOULNOTES_REDIS_HOSTS`                  | Redis 地址 (容器/K8s 部署必须覆盖)                                                                                                                                                                                                                                                                                                 |
| `quarkus.langchain4j.openai.*`                                   | AI 端点/模型/密钥 (`ai.openai.*` 占位)                                                                                                                                                                                                                                                                                             |
| `clinical.tagging` / `SOULNOTES_CLINICAL_TAGGING`                | 结构化输出契约开关 (默认 false, 开启后每请求追加契约段 token)                                                                                                                                                                                                                                                                      |
| `clinical.reveal-level` / `SOULNOTES_CLINICAL_REVEAL_LEVEL`      | 工作台实名解锁等级 (RED/YELLOW/NEVER, 非法值回落 RED)                                                                                                                                                                                                                                                                              |
| `clinical.retention-days` / `SOULNOTES_CLINICAL_RETENTION_DAYS`  | 启动时保留期清理窗口 (默认 90, <=0 禁用)                                                                                                                                                                                                                                                                                           |
| `asr.engine` / `asr.runtime.dir` / `asr.lib.url`                 | ASR 引擎 (`SOULNOTES_ASR_ENGINE`, 非 vosk 拒绝启动) / 运行时目录 / libvosk 来源 JAR                                                                                                                                                                                                                                                |
| `knowledge.pack` / `SOULNOTES_KNOWLEDGE_PACK`                    | 心理知识包名 (缺失回退 `default`)                                                                                                                                                                                                                                                                                                  |
| `alert.webhook.url` / `SOULNOTES_ALERT_WEBHOOK_URL`              | RED 预警机构 Webhook (空 = 渠道禁用); token 键同构 (`SOULNOTES_ALERT_WEBHOOK_TOKEN`)                                                                                                                                                                                                                                               |
| `alert.sms.*` / `alert.ding.*` / `alert.wecom.webhook`           | 预警通知短信/IM 九键 (环境变量见 CONFIGURATION.md §5): 短信五键齐备才启用, ding.webhook/ding.secret/wecom.webhook 各自配置即启用                                                                                                                                                                                                   |
| `crisis.hotline.*`                                               | 热线默认值                                                                                                                                                                                                                                                                                                                         |
| `voice.storage.directory` / `SOULNOTES_VOICE_DIR`                | 语音文件存储目录                                                                                                                                                                                                                                                                                                                   |
| `weather.threshold.*`                                            | 天气映射阈值                                                                                                                                                                                                                                                                                                                       |
| `rate.limit.chat.max-per-minute` / `SOULNOTES_RATE_LIMIT_CHAT`   | 聊天限流上限 (默认 20 次/分钟)                                                                                                                                                                                                                                                                                                     |
| `rate.limit.login.max-per-minute` / `SOULNOTES_RATE_LIMIT_LOGIN` | 登录限流上限 (默认 10 次/分钟)                                                                                                                                                                                                                                                                                                     |
| `rate.limit.voice.max-per-minute` / `SOULNOTES_RATE_LIMIT_VOICE` | 语音上传限流上限 (默认 10 次/分钟, 不进向导清单)                                                                                                                                                                                                                                                                                   |
| `quarkus.native.additional-build-args`                           | native 镜像构建参数: 固定默认时区 `Asia/Shanghai` + 七个静态 HttpClient 持有类 (`AsrRuntimeManager`/`WebhookAlertNotifier`/`HttpModelCatalog`/`ClinicalSchemaNormalizer`/`SmsAlertNotifier`/`DingTalkAlertNotifier`/`WeComAlertNotifier`) 强制运行时初始化 (防 image heap 固化) + `--enable-native-access` (Vosk FFM 受限调用声明) |

`application-dev.properties` (仅 dev profile): 本地 JWT 密钥 / DB 口令 / 均支持 `SOULNOTES_JWT_SECRET`/`SOULNOTES_DB_USER`/`SOULNOTES_DB_PASSWORD` 覆盖;
提交仓库时由 Git filter (`devsecrets`) 清洗本地密钥为占位符.

---

## 12. 测试覆盖

- 单元/集成测试 531 个 (`./gradlew :test`), 覆盖: 异常体系 / 工具类 / DTO 边界 / Service 反射逻辑 / Resource 结构 / Agent 签名 / 知识包加载与回退 / 五预警渠道行为 (Webhook 负载与禁用态 / 短信逐号群发与回环验真 / 钉钉企微 markdown 报文与加签 / 阿里云签名纯函数 / 五渠道装配证明) / issuer 一致性 / ASR 运行时下载与引擎 (无动态库真机用例 assumeTrue 跳过) / FFM 接口层 / URL 解析 / DB 五态映射 (fake gateway) / 模型列表解析 / zip 下载解压 (本地 fixture) / 配置管线 (Pre-Launch 校验与向导)
- Mock-LLM 全链路 (OpenAI 兼容零依赖 mock, `src/test/.../support/`): `/chat/send` 与 `/chat/stream` (SSE 分块) / 预警链路 (mock 判 RED → `warning_triggered` 落库) / 工具调用 (`@MemoryId` UUID 透传与工具结果回流) / `/ws/chat` WebSocket 流式 / JSONB 原生查询断言 (`jsonb_typeof`) / 结构化输出契约拆流 (on/off/坏格式三态) / 副医生评估落库全链路 (RED 实名解锁 / YELLOW 掩码脱敏 / NONE 不落库)
- 咨询员工作台单元层: `RevealPolicy` 解锁矩阵 (RED/YELLOW/NEVER, 非法值回落 RED, 短码跨调用稳定) / `ClinicalAssessmentService` 落库与脱敏视图 / `ClinicalResource` 角色与参数校验 (`@BeanParam` 缺席分页收敛默认 第1页/每页20) / `ClinicalFeedWebSocket` 生命周期与网关角色断言 / `ClinicalRetentionCleaner` (<=0 禁用短路, 正数清理)
- 语音链路以 `FixedAsrEngine` 固定转录文本注入, 不依赖真实模型与动态库

---

## 13. 已知边界与限制

- **AI 密钥为硬门槛**: `ai.openai.api-key=placeholder` 或为空时启动校验 BLOCK 拒绝启动 (不分 profile) — 必须经 Setup 向导或环境变量提供有效密钥
- **密码哈希**: 已落地 PBKDF2WithHmacSHA256 (210k 迭代, OWASP 推荐值, 存储格式 `pbkdf2$<iterations>$<salt>$<hash>`); 原型遗留的 SHA-256 无盐哈希仍可验证, 建议该批用户登录成功后重哈希迁移
- **ASR 为可插拔能力**: 运行时未就绪仅 WARN 不阻断 (文字链路与离线热线兜底完整可用); `asr.engine` 配置非 `vosk` 值则引擎 Bean 构造即拒绝启动; JVM 模式建议注入 `--enable-native-access=ALL-UNNAMED` 消除 FFM 受限调用告警 (未注入仅告警不影响功能)
- **`UserContextTool`** 在无 Hibernate 上下文的工具线程执行时降级返回默认文案
- **结构化输出**: 剥离是主机制, HTML 注释隐形仅是兜底 — 前端若以纯文本渲染, 透传的注释块会以原文可见; 开启时拆流成功且非 NONE 的评估异步落库并推送工作台 (best-effort, 失败仅 WARN 不影响对话)
- **限流依赖 Redis**: 聊天 (默认 20) / 登录 (默认 10) / 语音上传 (默认 10) 限流均可经 `SOULNOTES_RATE_LIMIT_*` 环境变量覆盖; Redis 不可用时过滤器降级放行 (fail-open)
- **集成测试依赖本机基础设施**: `@QuarkusTest` 需本机 PostgreSQL (5432) 与 Redis (6379) 在跑, CI 无库环境需后续以 Testcontainers 补齐

---

## 14. 部署与配置

部署形态 (打包运行 / JVM 镜像 / docker-compose / Kubernetes / Native 镜像), 环境变量总表 (51 项 `SOULNOTES_*`), 配置向导与启动前校验, 以及机构集成 (本地 ASR / 预警渠道矩阵 / 知识包 / 结构化输出) 的**单一权威参考是 [CONFIGURATION.md](./CONFIGURATION.md)** — 本文档不再重复维护, 防双源漂移.

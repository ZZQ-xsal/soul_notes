<div align="center">

# 心灵札记 · Soul Notes

**面向高校与机构的多模态 AI 心理健康轻干预平台**

*让每一次倾诉被温柔接住, 让每一次危机不被错过.*

[![CI](https://github.com/KurvCygnus/soul_notes/actions/workflows/ci.yml/badge.svg)](https://github.com/KurvCygnus/soul_notes/actions/workflows/ci.yml)
[![Native Build](https://github.com/KurvCygnus/soul_notes/actions/workflows/native-build.yml/badge.svg)](https://github.com/KurvCygnus/soul_notes/actions/workflows/native-build.yml)

</div>

---

## 为什么是心灵札记

传统心理支持体系有三道墙: **预约门槛** (排队数周的咨询室), **表达门槛** (面对面的开口成本), **覆盖缺口** (大量情绪困扰够不上"疾病"却真实存在). 心灵札记把 AI 安放在这三道墙的缝隙里 — 一个随时的, 匿名的, 温暖的倾诉入口; 同时用工程化的安全网保证: 当倾诉中浮现真正的危险信号时, 机器不缺位, 热线必达.

它不试图替代心理咨询师 — 它是咨询室之外的第一层守护, 和通往专业帮助的那座桥.

## 核心能力

### 🎙 多模态倾诉入口

语音与文字双通道, 统一进入 `语音 → 文本 → LLM 解析 → 情感分析` 管线. 语音识别完全**本地完成** (Vosk 经 JDK 25 FFM 直连, 零 JNI/JNA) — 倾诉内容不出部署环境, 这是心理场景的隐私底线.

### 🌤 情绪天气预报

实时量化每条日记与对话的正向 / 负向 / 焦虑三维指标, 映射为晴天 / 阴天 / 雨天 / 风暴四态 — 前端可视化情绪走势, 让用户"看见"自己的情绪周期, 也让机构端可感知群体状态.

### 💬 共情倾听, 拒绝贴标签

AI 以"心声树洞"倾听者角色回应: 温暖, 不评判, **严格规避医学诊断用语**. 提示词体系整体可替换 — 机构可以灌注自己的话术与风格, 平台不做约束.

### 🚨 红色预警: 五通道矩阵 + 离线兜底

安全机制是这个平台的**第一性设计**:

- **检测**: 用户消息与日记内容经独立预警分级 (NONE / YELLOW / RED), 不依赖对话模型的自觉 — 聊天与日记两条链路的 RED 同等触发渠道分发
- **五通道矩阵**: RED 时五渠道并发 fan-out (fire-and-forget, 3s 超时, 永不阻塞主链路; 触达频度受日记创建节奏影响, 建议机构侧评估), 各渠道配置即启用互为冗余 — WebSocket 实时弹窗 (总是启用, 直达心理援助热线) / 机构 Webhook / 阿里云短信 (逐号群发值班咨询员, 学生离线时干预者仍被触达) / 钉钉与企业微信群机器人 (报文仅提醒不存档, 预警明细在工作台)
- **离线兜底**: AI 服务或网络整体不可用时, 热线获取降级为 `专用端点 → Redis 缓存 → 静态默认值` 三级链 — **系统瘫痪到什么程度, 热线就在什么程度上仍可达**

### 🧩 平台, 而非单品

五个能力点全部接口化, 经配置自由组装 — 部署的是"心理健康咨询基础平台", 高校场景只是预置包:

| 可插拔点       | 机制                                          |
| -------------- | --------------------------------------------- |
| 语音识别引擎   | `IAsrEngine` 端口, 配置切换                   |
| 预警通知渠道   | `IAlertNotifier` 端口, 内置五渠道 + 同构扩展  |
| 心理知识包     | classpath 整包替换, 缺失自动回退内置包        |
| 提示词体系     | 三条系统提示词均可整体覆盖                    |
| 身份品牌       | 品牌名一行配置, 面向不同机构白标              |

另有**咨询员工作台 (后端已就绪)**: AI 以"副医生"角色在共情回复之外产出结构化心理标签 (风险等级/标签/摘要), 自动落库并提供咨询员消费 API — 风险队列, 学生时间线, 聚合统计与实时推送; 身份按风险分级解锁 (默认仅 RED 实名), 聊天正文永不暴露. 形成 "AI 初筛 → 人类专家介入" 的完整闭环; 结构定义仍支持机构以自然语言描述, 启动时经 LLM 归一化为严格 JSON Schema 并按指纹缓存.

## 开箱即用的部署体验

**`--setup` 交互式向导** (pnpm 风格): 折叠清单导航, 就地校验重问, 密钥全程掩码; 数据库条目保存后**真连接探测**, 不存在自动建库, 缺表自动建表; AI 端点保存后**实时拉取模型列表**供选择; ASR 未就绪现场询问并自动下载模型与动态库. 产物双落盘 (`application.properties` + `.env`), 已有文件自动备份.

**fail-fast 启动校验**: 每次启动前运行配置规则矩阵 — scheme 格式, 密钥强度, prod 必配项, 情绪阈值序, 数据库五态 — 有 BLOCK 拒绝启动, 无 TTY 环境直接退出不挂起. 配置错误在部署现场暴露, 而不是在用户对话中.

**企业就绪细节**: JWT 认证 (HS256, 黑名单登出), PBKDF2WithHmacSHA256 密码哈希 (210k 轮, OWASP 推荐), 三级限流 (聊天/登录/语音), CORS 白名单, 健康探针 (`/q/health/live` / `ready`), 12-factor 环境变量配置 (51 项 `SOULNOTES_*` 键).

## 技术基座

| 领域         | 选型                                                                  |
| ------------ | --------------------------------------------------------------------- |
| 运行时       | Java 25 (GraalVM CE 25) + Quarkus 3.36, 全响应式 (Mutiny / Hibernate Reactive) |
| 原生编译     | GraalVM Native Image — 116MB 单文件 Linux 二进制, 无 JVM 运行时, CI 每次发版自动构建 |
| 本地语音识别 | Vosk, JDK 25 FFM 直连 libvosk (零 JNI/JNA, JVM 与 Native 双侧可用)     |
| AI 集成      | quarkus-langchain4j-openai, 兼容任意 OpenAI 协议端点 (官方/中转/自建网关) |
| 数据         | PostgreSQL (JSONB 情绪指标) + Redis (缓存/限流/热线兜底)               |
| 实时通信     | WebSocket (预警推送) + SSE (AI 流式打字机输出)                         |
| 质量门禁     | 531 个测试 (含 Mock-LLM 全链路集成), GitHub Actions 四工作流全自动化   |

## 快速开始

**开发模式** (需 JDK 25, PostgreSQL 16, Redis 7):

```bash
docker compose up -d postgres redis   # 基础服务
./gradlew quarkusDev                  # dev profile 自带本地默认配置
```

**生产部署** (三步, 无需手工整理环境变量):

```bash
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar --setup   # 向导: 配置 + 建库 + 模型下载
java -jar build/quarkus-app/quarkus-run.jar
```

容器编排 (docker-compose / Kubernetes) 与原生二进制的完整指引见 [CONFIGURATION.md §6](./CONFIGURATION.md#6-部署).

## 文档

| 文档                                          | 受众与内容                                       |
| --------------------------------------------- | ------------------------------------------------ |
| [CONFIGURATION.md](./CONFIGURATION.md)        | 部署者/运维者 — 环境变量总表, 配置向导, 部署形态, 机构集成 (预警渠道 / 知识包 / 提示词 / 副医生) |
| [Architecture.md](./Architecture.md)          | 开发者 — 包结构, 安全设计, 响应式约束, AI 链路, 数据流全景 |

## 路线图

- 心理知识包 RAG 化: 向量检索升级 (当前为关键词检索, 接口已预留)
- 更多 ASR 引擎选项 (引擎端口已抽象)
- CI 基础设施 Testcontainers 化 (当前集成测试依赖真实 PostgreSQL/Redis)

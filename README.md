# Soul Notes 后端 (心声树洞)

## 1. 项目简介

Soul Notes 是一个面向大学生的多模态 AI 心理轻干预系统后端, 以"心声树洞"倾听者角色与用户共情对话, 实时感知情绪状态并提供可视化数据, 同时内建高危预警与离线热线兜底机制.

核心能力:

- 多模态输入: 语音 (ASR 转录) 与文字, 统一进入 `Voice -> Text -> LLM 解析 -> 情感分析` 管线
- 情感分析与可视化: 实时计算 positive / negative / anxiety 数值, 生成前端"情绪天气预报"数据
- 共情非医学化对话: AI 以倾听者角色回应, 禁止医学诊断标签
- 高危预警 (Red Alert): 检测到自伤/自杀倾向时在线弹窗推送心理中心热线; AI 或网络不可用时由离线兜底 (CrisisResource) 保证热线可达

## 2. 技术栈

| 领域             | 技术                                                                  |
|------------------|-----------------------------------------------------------------------|
| 运行时           | Java 25 (GraalVM), Quarkus 3.36                                       |
| 持久化           | Hibernate Reactive + Panache, PostgreSQL (quarkus-reactive-pg-client) |
| 缓存/限流/黑名单 | Redis (quarkus-redis-client)                                          |
| 实时通信         | quarkus-websockets-next (WS) + SSE                                    |
| AI               | quarkus-langchain4j-openai (声明式 @RegisterAiService)                |
| 认证             | quarkus-smallrye-jwt (HS256 对称密钥)                                 |
| 健康检查         | quarkus-smallrye-health (/q/health/live, /q/health/ready)             |
| JSON             | Jackson (JsonUtils 统一封装)                                          |

## 3. 本地开发

### 前置要求

- JDK 25
- PostgreSQL 16
- Redis 7
- (可选) Docker / docker-compose: 用于一键启动基础服务或完整编排

基础服务可通过 docker-compose 单独启动 (仅启动 PostgreSQL 与 Redis):

```bash
docker compose up -d postgres redis
```

初始化数据库: 依次执行 `sql_scripts/*_init.sql` 建表, 再导入 `sql_scripts/*_mock_data.sql` (演示数据, 见"演示账号").

### 启动开发模式

```bash
chmod +x gradlew      # 非 Windows 用户; Windows 请使用 gradlew.bat
./gradlew quarkusDev
```

- 开发模式默认加载 `application-dev.properties`, 本地数据库口令与 JWT 密钥均有默认值, 可直接启动
- Dev UI: http://localhost:8080/q/dev/
- 需要覆盖配置时, 在启动前导出环境变量即可 (见"配置表")

## 4. 测试

```bash
chmod +x gradlew      # 非 Windows 用户; Windows 请使用 gradlew.bat
./gradlew :test
```

227 个单元测试, 覆盖异常体系 / 工具类 / DTO 边界 / Service 反射逻辑 / Resource 结构 / Agent 签名 / Retriever / 配置管线 (Pre-Launch 校验与向导).

## 5. 部署

### 5.1 打包与运行

```bash
chmod +x gradlew      # 非 Windows 用户; Windows 请使用 gradlew.bat
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar
```

注意: 生产环境 (prod profile) 不加载 `application-dev.properties`, 必须通过环境变量注入 `SOULNOTES_DB_USER` / `SOULNOTES_DB_PASSWORD` / `SOULNOTES_JWT_SECRET` 等 (见"配置表"), 或在首次部署时运行 `java -jar build/quarkus-app/quarkus-run.jar --setup` 交互式生成配置 (见 §7.2).

### 5.2 JVM 镜像

```bash
chmod +x gradlew      # 非 Windows 用户; Windows 请使用 gradlew.bat
./gradlew build
docker build -f src/main/docker/Dockerfile.jvm -t soulnotes-backend .
```

`Dockerfile.jvm` 基于 UBI 9 的 OpenJDK 25 运行时基座, 分层复制 `build/quarkus-app` 产物.

### 5.3 docker-compose

前置: 先执行 `./gradlew build` (`Dockerfile.jvm` 依赖 `build/quarkus-app` 产物), 然后一键编排:

```bash
docker compose up -d --build
```

`docker-compose.yml` 编排 PostgreSQL + Redis + 后端 (JVM 模式), 后端环境变量均为 12-factor 覆盖项, 语音文件挂载命名卷 `voice_uploads`.

### 5.4 Kubernetes

```bash
kubectl apply -f k8s/
```

- `k8s/` 包含 ConfigMap / Secret / Deployment / Service / PostgreSQL / Redis / PVC, 按依赖顺序一次应用
- Deployment 镜像默认 `soulnotes-backend:latest`, 部署前需构建并推送至集群可访问的镜像仓库 (替换 `backend-deployment.yaml` 的 `image`)
- 存活探针 `/q/health/live`, 就绪探针 `/q/health/ready` (由 quarkus-smallrye-health 提供)
- 语音文件通过 PVC `soulnotes-voice-pvc` 挂载至 `/data/voice_uploads` (`SOULNOTES_VOICE_DIR`)

### 5.5 Native 镜像

无需本地安装 GraalVM, native 编译在容器内完成:

```bash
chmod +x gradlew      # 非 Windows 用户; Windows 请使用 gradlew.bat
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
docker build -f src/main/docker/Dockerfile.native -t soulnotes-backend-native .
```

`Dockerfile.native` 基于 ubi9-minimal, 无 JVM, 启动更快、内存占用更低. 镜像通过 `quarkus.native.additional-build-args=-Duser.timezone=Asia/Shanghai` 固定默认时区 (GraalVM 21 起 native 默认内置完整 tzdb).

## 6. 演示账号

导入 `sql_scripts/users_mock_data.sql` 后可用 (mock 数据, 统一密码):

| 用户名   | 密码            | 角色      |
|----------|-----------------|-----------|
| alice    | Soulnotes123!   | STUDENT   |
| bob      | Soulnotes123!   | STUDENT   |
| charlie  | Soulnotes123!   | COUNSELOR |
| diana    | Soulnotes123!   | ADMIN     |
| eve      | Soulnotes123!   | STUDENT   |

注意: mock 数据为固定盐 PBKDF2WithHmacSHA256 (210000 轮) 哈希, 仅用于演示, 生产账号使用随机盐. 当前 AI 密钥为占位符 (`placeholder`), 所有 LLM 调用失败并走降级逻辑.

## 7. 配置表

### 7.1 环境变量总表

以下环境变量对应 `src/main/resources/application.properties` 的 `${VAR:default}` 占位, 未配置时使用默认值; 全部 30 项均可经 `--setup` 向导交互式配置 (见 §7.2):

| 环境变量                              | 用途                                                   | 默认值                                    |
| ------------------------------------- | ------------------------------------------------------ | ----------------------------------------- |
| `SOULNOTES_DB_URL`                    | PostgreSQL 响应式连接地址 (必配)                       | `postgresql://localhost:5432/soulnotes`   |
| `SOULNOTES_DB_USER`                   | PostgreSQL 用户名 (必配; dev profile 默认 kurv)        | 无                                        |
| `SOULNOTES_DB_PASSWORD`               | PostgreSQL 密码 (必配, 严禁入库; dev 有本地默认)       | 无                                        |
| `SOULNOTES_REDIS_HOSTS`               | Redis 地址, 容器/K8s 部署必配                          | `redis://localhost:6379`                  |
| `SOULNOTES_JWT_SECRET`                | JWT HS256 签名密钥, 至少 32 字节, 未配置时启动 fail-fast (必配; dev 有本地占位密钥) | 无        |
| `SOULNOTES_JWT_TTL`                   | Token 有效期 (秒)                                      | `604800`                                  |
| `SOULNOTES_AI_ENDPOINT`               | OpenAI 兼容接口根地址, 可指向中转或自建网关 (必配)     | `https://api.openai.com/v1`               |
| `SOULNOTES_AI_MODEL`                  | 对话与情绪分析使用的模型 (必配)                        | `gpt-4o-mini`                             |
| `SOULNOTES_AI_API_KEY`                | AI API 密钥 (必配; 默认为占位符, LLM 走降级逻辑)       | `placeholder`                             |
| `SOULNOTES_AI_TEMPERATURE`            | 生成温度, 越低越稳定; 心理倾听场景建议 0.2-0.7         | `0.7`                                     |
| `SOULNOTES_AI_MAX_TOKENS`             | 单次回复 Token 上限                                    | `1024`                                    |
| `SOULNOTES_AI_TIMEOUT`                | AI 请求超时 (Quarkus Duration 格式, 如 30s / 1m)       | `30s`                                     |
| `SOULNOTES_CRISIS_HOTLINE_NAME`       | 热线名称, 展示于 RED 预警弹窗与离线兜底横幅            | `全国心理援助热线`                        |
| `SOULNOTES_CRISIS_HOTLINE_PRIMARY`    | RED 预警主热线                                         | `400-161-9995`                            |
| `SOULNOTES_CRISIS_HOTLINE_BACKUP`     | RED 预警备用热线                                       | `12355`                                   |
| `SOULNOTES_CORS_ORIGINS`              | CORS 白名单, 多个来源用逗号分隔                        | `http://localhost:5173`                   |
| `SOULNOTES_RATE_LIMIT_CHAT`           | 聊天限流上限 (次/分钟)                                 | `20`                                      |
| `SOULNOTES_RATE_LIMIT_LOGIN`          | 登录限流上限 (次/分钟)                                 | `10`                                      |
| `SOULNOTES_VOICE_DIR`                 | 语音文件存储目录, 容器部署建议挂载 PVC                 | `voice_uploads`                           |
| `SOULNOTES_VOICE_MAX_BYTES`           | 语音单文件大小上限 (字节)                              | `10485760`                                |
| `SOULNOTES_WEATHER_STORM`             | 风暴阈值 (焦虑或负向均值 >= 阈值)                      | `0.8`                                     |
| `SOULNOTES_WEATHER_RAINY`             | 雨天阈值 (负向均值 >= 阈值)                            | `0.6`                                     |
| `SOULNOTES_WEATHER_OVERCAST`          | 阴天阈值 (负向均值 >= 阈值)                            | `0.4`                                     |
| `SOULNOTES_WEATHER_SUNNY`             | 晴天阈值 (正向均值 >= 阈值)                            | `0.6`                                     |
| `SOULNOTES_CHAT_HISTORY_MAX`          | 对话历史滚动上限 (条)                                  | `50`                                      |
| `SOULNOTES_MOOD_RECENT_DAYS`          | 用户上下文工具回溯近期日记/情绪记录的天数              | `7`                                       |
| `SOULNOTES_PROMPT_EMPATHETIC_CHAT`    | 共情倾听系统提示词覆盖, 留空使用内置默认               | 空                                        |
| `SOULNOTES_PROMPT_WARNING_DETECTION`  | 预警分级提示词覆盖, 留空使用内置默认 (覆盖时机构自担分级标准漂移风险) | 空                         |
| `SOULNOTES_PROMPT_MOOD_ANALYSIS`      | 情绪分析提示词覆盖, 留空使用内置默认                   | 空                                        |

其他说明:

- AI 三项 (`ai.openai.*`) 经 LangChain4j 桥接键 (`quarkus.langchain4j.openai.*`) 引用展开值, 单独配置桥接键不生效
- `mp.jwt.verify.issuer` 固定为 `soul-notes`, 必须与 TokenService 签发的 issuer 一致, 不可更改

### 7.2 交互式配置 (--setup)

无需手工整理环境变量, 打包产物自带 pnpm 风格的配置向导:

```bash
java -jar build/quarkus-app/quarkus-run.jar --setup
```

- **两种模式**: `1. 简单配置 (仅必填项)` / `2. 全面配置 (全部 30 项)`, 回车默认简单配置
- **键位说明**: 折叠清单按 `序号` 跳转 / `Enter` 顺序遍历 (保存即推进下一项); 展开态输入 `esc` 放弃本次修改; 折叠态输入 `q` 进入配置摘要
- **就地校验**: 非法输入 (URL scheme / 非数字 / 长度不足) 红字重问; 必填项留空不折叠重问; JWT 密钥留空自动生成 64 字符随机密钥
- **密钥掩码**: secret/generate 类字段的任何回显与摘要一律显示 `******`, 明文仅存在于落盘文件
- **双输出**: `config/application.properties` (键 = 配置键, 分组注释 + 对齐) 与 `.env` (键 = 环境变量名, 严格 `envName=value`, 供 `source` / docker compose / kubectl 消费); 已存在的文件自动备份为 `*.bak`; 仅显式输入的项落盘, 其余保持内置默认
- **摘要确认**: `[Y/n]`, 否决返回编辑态且已输入值保留
- **完成屏**: `1. 启动应用` (重跑启动前校验, 无 BLOCK 方可放行) / `2. 退出` (退出码 0); 中途 EOF 取消不写任何文件, 以退出码 1 结束 (Ctrl+C 由 JVM 信号机制直接终止, 退出码通常为 130, 同样不会落盘)

<!-- 向导截图占位: 待补充终端交互截图 -->

### 7.3 启动前校验

每次启动 (dev/prod) 前自动运行配置规则矩阵, 报告格式为 BLOCK 在前 (`❌ [环境变量] 原因`)、WARN 在后 (`⚠ [环境变量] 原因`):

**BLOCK 规则 (阻止启动)**:

- DB / Redis 地址 scheme: 必须以 `postgresql://` / `redis://` 或 `rediss://` 开头
- AI 服务端点 scheme: 必须以 `https://` 或 `http://` 开头
- prod 必配项缺席: `SOULNOTES_DB_USER` / `SOULNOTES_DB_PASSWORD` / `SOULNOTES_JWT_SECRET` (dev 由 `application-dev.properties` 放宽)
- JWT 密钥长度 >= 32 字节: 显式弱值不分 profile 一律 BLOCK
- AI 密钥未配置 (为空或为占位符 placeholder): 请经 Setup 向导或环境变量提供 (不分 profile, 无 dev 放宽)
- 情绪天气阈值: 每项处于 [0,1] 且 `storm > rainy > overcast` 严格递减 (否则雨天/阴天分支不可达)

**WARN 规则 (仅警告, 不阻断)**:

- ASR 运行时未就绪 (缺本地模型或动态库): 语音转写暂不可用, 可经 Setup 向导下载或手动放置
- prod 使用默认 CORS 白名单: 请按部署环境收紧
- dev 使用内置默认/弱 JWT 密钥: 勿用于生产环境

**退出码与 TTY 行为**:

- 退出码 `0`: 校验通过 (可能附警告清单) 或向导内用户主动退出; 退出码 `1`: 存在 BLOCK, 或向导取消/写盘失败
- 有 TTY 且存在 BLOCK: 打印报告后提议 "是否进入配置向导修复? [Y/n]", 回车进入向导
- 无 TTY (CI / 管道): 打印报告后直接以退出码 1 结束, 不会挂起

### 7.4 配置文件自描述

向导条目 (分组名 / 显示名 / 说明 / 输入类型) 的单一来源是 `src/main/resources/application.properties` 本身 — 每个配置项正上方以 `# @tag arg` 注释声明元数据, 由 PropertyMetaParser 运行时解析:

| 注释标签     | 作用                                                             |
| ------------ | ---------------------------------------------------------------- |
| `@group`     | 分组名 (向导展示顺序即文件顺序)                                  |
| `@name`      | 向导中的显示名                                                   |
| `@explain`   | 展开态的说明文字, 可重复, 一行一条                               |
| `@input`     | 输入类型: `url` / `text` / `secret` / `number` / `int` / `generate` (int 拒绝小数, 供整型配置键使用) |
| `@scheme`    | url 类输入的格式提示, 多 scheme 用 `\|` 分隔 (如 `redis://\|rediss://`) |
| `@min-length`| 最小长度 (secret/generate 类校验下限, 如 JWT 32)                 |
| `@required`  | 标记必填项 (prod 缺席时 BLOCK)                                   |

带标签且值行为 `${ENV:default}` 形式的键才会进入向导与校验, 无标签的框架键不参与. **新增配置项只需改 `application.properties` 一处** (加标签 + `${SOULNOTES_*:default}` 占位), 向导与启动前校验即自动跟上, 无需改动任何 Java 代码; 上文环境变量表为手工维护, 新增项需同步更新.

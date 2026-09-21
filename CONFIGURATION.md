# 心灵札记 (Soul Notes) — 配置与运维参考

本文是部署者与运维者的单一权威参考: 环境变量总表, `--setup` 配置向导, 启动前校验, 部署形态与机构集成 (本地语音识别 / Webhook 预警 / 知识包 / 结构化输出). 架构与代码导读见 [Architecture.md](./Architecture.md), 产品概览见 [README.md](./README.md).

---

## 目录

- [1. 配置总览](#1-配置总览)
- [2. 交互式配置向导 (--setup)](#2-交互式配置向导---setup)
- [3. 启动前校验](#3-启动前校验)
- [4. 配置文件自描述](#4-配置文件自描述)
- [5. 环境变量总表](#5-环境变量总表)
- [6. 部署](#6-部署)
- [7. 本地语音识别 (ASR)](#7-本地语音识别-asr)
- [8. 机构对接 (Webhook 预警通道)](#8-机构对接-webhook-预警通道)
- [9. 知识包自定义 (心理小知识)](#9-知识包自定义-心理小知识)
- [10. 结构化输出 (临床标签预埋, "副医生")](#10-结构化输出-临床标签预埋-副医生)
- [11. 演示账号](#11-演示账号)

---

## 1. 配置总览

全部配置遵循 12-factor: 每个业务键都是 `application.properties` 中的 `${SOULNOTES_*:default}` 占位 — 环境变量优先, 未配置时回落内置默认, 无需触碰任何文件即可完成覆盖. 42 个 `SOULNOTES_*` 键中 40 项可经 `--setup` 向导交互式配置, 其余 2 项 (品牌名 / 语音限流) 属部署微调, 保持内置默认即可.

AI 三项 (`ai.openai.*`) 经 LangChain4j 桥接键 (`quarkus.langchain4j.openai.*`) 引用展开值, 单独配置桥接键不生效. `mp.jwt.verify.issuer` 与 TokenService 签发的 iss claim 共用 `SOULNOTES_JWT_ISSUER` 一个键 (双端天然一致), 中途更换将使全部已发 Token 立即失效.

## 2. 交互式配置向导 (--setup)

无需手工整理环境变量, 打包产物自带 pnpm 风格的配置向导:

```bash
java -jar build/quarkus-app/quarkus-run.jar --setup
```

- **两种模式**: `1. 简单配置 (仅必填项)` / `2. 全面配置 (全部 40 项)`, 回车默认简单配置
- **键位说明**: 折叠清单按 `序号` 跳转 / `Enter` 顺序遍历 (保存即推进下一项); 展开态输入 `esc` 放弃本次修改; 折叠态输入 `q` 进入配置摘要
- **就地校验**: 非法输入 (URL scheme / 非数字 / 长度不足) 红字重问; 必填项留空不折叠重问; JWT 密钥留空自动生成 64 字符随机密钥
- **ASR 运行时交互**: 保存 `SOULNOTES_ASR_ENGINE` / `SOULNOTES_ASR_RUNTIME_DIR` 后自动就绪检查, 未就绪现场询问"是否立即下载", 接受后按 [§7](#7-本地语音识别-asr) 的来源拉取模型 zip 与 libvosk (进度行内回显); 下载失败不中断向导, 可稍后手动放置或重试
- **数据库五态流**: 数据库组条目保存后即时探测: 可连通且 schema 完整 → 直通; 连接参数错误 → 就地红字重问; 库不存在 → 自动连 postgres 默认库建库; 表缺失 → 询问后执行幂等初始化脚本 (含 platform_schema_version 版本表)
- **AI 模型拉取步**: AI 接口地址与密钥保存且值变化时, 自动拉取服务端 `/models` 列表 (10s 超时), 展示模型清单 (含上下文长度/思考能力等扩展字段, 服务端提供则显示) 供序号选择; endpoint 同步规范化 (如补全 `/v1`); 401 或拉取失败降级为手动输入模型名
- **密钥掩码**: secret/generate 类字段的任何回显与摘要一律显示 `******`, 明文仅存在于落盘文件
- **双输出**: `config/application.properties` (键 = 配置键, 分组注释 + 对齐) 与 `.env` (键 = 环境变量名, 严格 `envName=value`, 供 `source` / docker compose / kubectl 消费); 已存在的文件自动备份为 `*.bak`; 仅显式输入的项落盘, 其余保持内置默认
- **摘要确认**: `[Y/n]`, 否决返回编辑态且已输入值保留
- **完成屏**: `1. 启动应用` (重跑启动前校验, 无 BLOCK 方可放行) / `2. 退出` (退出码 0); 中途 EOF 取消不写任何文件, 以退出码 1 结束 (Ctrl+C 由 JVM 信号机制直接终止, 退出码通常为 130, 同样不会落盘)

<!-- 向导截图占位: 待补充终端交互截图 -->

## 3. 启动前校验

每次启动 (dev/prod) 前自动运行配置规则矩阵, 报告格式为 BLOCK 在前 (`❌ [环境变量] 原因`), WARN 在后 (`⚠ [环境变量] 原因`):

**BLOCK 规则 (阻止启动)**:

- DB / Redis 地址 scheme: 必须以 `postgresql://` / `redis://` 或 `rediss://` 开头
- AI 服务端点 scheme: 必须以 `https://` 或 `http://` 开头
- prod 必配项缺席: `SOULNOTES_DB_USER` / `SOULNOTES_DB_PASSWORD` / `SOULNOTES_JWT_SECRET` (dev 由 `application-dev.properties` 放宽)
- JWT 密钥长度 >= 32 字节: 显式弱值不分 profile 一律 BLOCK
- AI 密钥未配置 (为空或为占位符 placeholder): 请经 Setup 向导或环境变量提供 (不分 profile, 无 dev 放宽)
- 情绪天气阈值: 每项处于 [0,1] 且 `storm > rainy > overcast` 严格递减 (否则雨天/阴天分支不可达)
- 数据库可达性五态: 探测结果为不可达 / 认证失败 / 库不存在 / schema 缺失均 BLOCK (配置向导可自动建库建表, 见 §2)

**WARN 规则 (仅警告, 不阻断)**:

- ASR 运行时未就绪 (缺本地模型或动态库): 语音转写暂不可用, 可经 Setup 向导下载或手动放置
- prod 使用默认 CORS 白名单: 请按部署环境收紧
- dev 使用内置默认/弱 JWT 密钥: 勿用于生产环境

**退出码与 TTY 行为**:

- 退出码 `0`: 校验通过 (可能附警告清单) 或向导内用户主动退出; 退出码 `1`: 存在 BLOCK, 或向导取消/写盘失败
- 有 TTY 且存在 BLOCK: 打印报告后提议 "是否进入配置向导修复? [Y/n]", 回车进入向导
- 无 TTY (CI / 管道): 打印报告后直接以退出码 1 结束, 不会挂起

## 4. 配置文件自描述

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

带标签且值行为 `${ENV:default}` 形式的键才会进入向导与校验, 无标签的框架键不参与. **新增配置项只需改 `application.properties` 一处** (加标签 + `${SOULNOTES_*:default}` 占位), 向导与启动前校验即自动跟上, 无需改动任何 Java 代码; §5 环境变量表为手工维护, 新增项需同步更新.

## 5. 环境变量总表

以下环境变量对应 `src/main/resources/application.properties` 的 `${VAR:default}` 占位, 未配置时使用默认值:

| 环境变量                              | 用途                                                   | 默认值                                    |
| ------------------------------------- | ------------------------------------------------------ | ----------------------------------------- |
| `SOULNOTES_DB_URL`                    | PostgreSQL 响应式连接地址 (必配)                       | `postgresql://localhost:5432/soulnotes`   |
| `SOULNOTES_DB_USER`                   | PostgreSQL 用户名 (必配; dev profile 默认 kurv)        | 无                                        |
| `SOULNOTES_DB_PASSWORD`               | PostgreSQL 密码 (必配, 严禁入库; dev 有本地默认)       | 无                                        |
| `SOULNOTES_REDIS_HOSTS`               | Redis 地址, 容器/K8s 部署必配                          | `redis://localhost:6379`                  |
| `SOULNOTES_JWT_SECRET`                | JWT HS256 签名密钥, 至少 32 字节, 未配置时启动 fail-fast (必配; dev 有本地占位密钥) | 无        |
| `SOULNOTES_JWT_TTL`                   | Token 有效期 (秒)                                      | `604800`                                  |
| `SOULNOTES_JWT_ISSUER`                | JWT 签发者 (签发与验签同键; 更换后全部已发 Token 失效) | `soul-notes`                              |
| `SOULNOTES_BRAND_NAME`                | 品牌名 (向导标题/启动 banner 首行)                     | `Soul Notes`                              |
| `SOULNOTES_AI_ENDPOINT`               | OpenAI 兼容接口根地址, 可指向中转或自建网关 (必配)     | `https://api.openai.com/v1`               |
| `SOULNOTES_AI_MODEL`                  | 对话与情绪分析使用的模型 (必配; 向导内可拉取列表选择)  | `gpt-4o-mini`                             |
| `SOULNOTES_AI_API_KEY`                | AI API 密钥 (必配; 默认占位符会被启动校验 BLOCK)       | `placeholder`                             |
| `SOULNOTES_AI_TEMPERATURE`            | 生成温度, 越低越稳定; 心理倾听场景建议 0.2-0.7         | `0.7`                                     |
| `SOULNOTES_AI_MAX_TOKENS`             | 单次回复 Token 上限                                    | `1024`                                    |
| `SOULNOTES_AI_TIMEOUT`                | AI 请求超时 (Quarkus Duration 格式, 如 30s / 1m)       | `30s`                                     |
| `SOULNOTES_CLINICAL_TAGGING`          | 结构化输出契约开关 (on/true 开启, 默认关闭; 见 §10)    | `false`                                   |
| `SOULNOTES_PROMPT_CLINICAL_SCHEMA`    | 副医生结构定义 (自然语言, 启动时经 LLM 归一化并缓存; 见 §10.1) | 空 (内置 canonical 结构)          |
| `SOULNOTES_CLINICAL_REVEAL_LEVEL`     | 工作台实名解锁等级 (RED/YELLOW/NEVER, 非法值回落 RED)  | `RED`                                     |
| `SOULNOTES_CLINICAL_RETENTION_DAYS`   | 临床评估保留天数 (启动时清理, <=0 禁用)                | `90`                                      |
| `SOULNOTES_ASR_ENGINE`                | ASR 引擎, 当前仅 `vosk` 可选, 其他值拒绝启动           | `vosk`                                    |
| `SOULNOTES_ASR_RUNTIME_DIR`           | ASR 运行时目录 (lib/ + model/, 见 §7)                  | `asr-model`                               |
| `SOULNOTES_ASR_LIB_URL`               | libvosk 来源 JAR 地址 (非动态库直链; 留空用内置默认)   | 空 (aliyun 镜像 vosk-0.3.45 JAR)          |
| `SOULNOTES_CRISIS_HOTLINE_NAME`       | 热线名称, 展示于 RED 预警弹窗与离线兜底横幅            | `全国心理援助热线`                        |
| `SOULNOTES_CRISIS_HOTLINE_PRIMARY`    | RED 预警主热线                                         | `400-161-9995`                            |
| `SOULNOTES_CRISIS_HOTLINE_BACKUP`     | RED 预警备用热线                                       | `12355`                                   |
| `SOULNOTES_CORS_ORIGINS`              | CORS 白名单, 多个来源用逗号分隔                        | `http://localhost:5173`                   |
| `SOULNOTES_ALERT_WEBHOOK_URL`         | RED 预警机构 Webhook 地址 (空 = 渠道禁用, 见 §8)       | 空                                        |
| `SOULNOTES_ALERT_WEBHOOK_TOKEN`       | Webhook 鉴权令牌 (非空时携带 `Authorization: Bearer`)  | 空                                        |
| `SOULNOTES_KNOWLEDGE_PACK`            | 心理知识包名 (对应 classpath `knowledge/{包名}/`, 见 §9) | `default`                              |
| `SOULNOTES_RATE_LIMIT_CHAT`           | 聊天限流上限 (次/分钟)                                 | `20`                                      |
| `SOULNOTES_RATE_LIMIT_LOGIN`          | 登录限流上限 (次/分钟)                                 | `10`                                      |
| `SOULNOTES_RATE_LIMIT_VOICE`          | 语音上传限流上限 (次/分钟, 本地转录单请求成本高)       | `10`                                      |
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

## 6. 部署

### 6.1 打包与运行

```bash
chmod +x gradlew      # 非 Windows 用户; Windows 请使用 gradlew.bat
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar
```

生产环境 (prod profile) 不加载 `application-dev.properties`, 必须通过环境变量注入 `SOULNOTES_DB_USER` / `SOULNOTES_DB_PASSWORD` / `SOULNOTES_JWT_SECRET` 等 (见 §5), 或在首次部署时运行 `java -jar build/quarkus-app/quarkus-run.jar --setup` 交互式生成配置 (见 §2). JVM 模式下 ASR 依赖 FFM 受限 native 调用, 建议注入 `JDK_JAVA_OPTIONS=--enable-native-access=ALL-UNNAMED` 以消除 JDK 24+ 的默认告警 (未注入仅告警, 不影响功能).

### 6.2 JVM 镜像

```bash
chmod +x gradlew      # 非 Windows 用户; Windows 请使用 gradlew.bat
./gradlew build
docker build -f src/main/docker/Dockerfile.jvm -t soulnotes-backend .
```

`Dockerfile.jvm` 基于 UBI 9 的 OpenJDK 25 运行时基座, 分层复制 `build/quarkus-app` 产物.

### 6.3 docker-compose

前置: 先执行 `./gradlew build` (`Dockerfile.jvm` 依赖 `build/quarkus-app` 产物), 然后一键编排:

```bash
docker compose up -d --build
```

`docker-compose.yml` 编排 PostgreSQL + Redis + 后端 (JVM 模式), 后端环境变量均为 12-factor 覆盖项, 语音文件挂载命名卷 `voice_uploads`, ASR 运行时挂载命名卷 `asr_model` (挂载点与 `SOULNOTES_ASR_RUNTIME_DIR=/data/asr-model` 对齐). PostgreSQL 数据持久化于命名卷 `pgdata`, 首次启动为空库 — 需先完成一次初始化 (二选一):

```bash
# 方式 A: 进入配置向导 (TTY 交互, 可同时完成密钥/ASR 下载/建库建表, 产物 .env 会被 compose 自动读取)
docker compose run --rm backend java -jar /deployments/quarkus-run.jar --setup

# 方式 B: 手动向空库灌入建表脚本
cat sql_scripts/*_init.sql | docker compose exec -T postgres psql -U kurv -d soulnotes
```

注意: `SOULNOTES_AI_API_KEY` 在 compose 中默认取宿主环境/`.env` 的值 (缺省为占位符 `placeholder`), 启动前校验会对占位符 BLOCK 拒绝启动 — 部署前必须提供有效密钥. compose 内已注入 `JAVA_OPTS_APPEND=--enable-native-access=ALL-UNNAMED` (ASR FFM 受限调用放行).

### 6.4 Kubernetes

```bash
kubectl apply -f k8s/
```

- `k8s/` 包含 ConfigMap / Secret / Deployment / Service / PostgreSQL / Redis / PVC, 按依赖顺序一次应用
- Deployment 镜像默认 `soulnotes-backend:latest`, 部署前需构建并推送至集群可访问的镜像仓库 (替换 `backend-deployment.yaml` 的 `image`)
- 存活探针 `/q/health/live`, 就绪探针 `/q/health/ready` (由 quarkus-smallrye-health 提供)
- 语音文件通过 PVC `soulnotes-voice-pvc` 挂载至 `/data/voice_uploads` (`SOULNOTES_VOICE_DIR`)
- ASR 运行时通过 PVC `soulnotes-asr-pvc` 挂载至 `/data/asr-model` (`SOULNOTES_ASR_RUNTIME_DIR`), 模型/动态库可预先灌入该卷或首启后经向导下载
- Deployment 注入 `JAVA_OPTS_APPEND=--enable-native-access=ALL-UNNAMED` (JVM 模式 ASR FFM 受限调用放行)
- PostgreSQL 数据持久化于 PVC `soulnotes-postgres-pvc`; 空库首启会被启动校验 BLOCK (schema 未就绪), 需执行一次 §6.3 的初始化或向导建表

### 6.5 Native 镜像

无需本地安装 GraalVM, native 编译在容器内完成:

```bash
chmod +x gradlew      # 非 Windows 用户; Windows 请使用 gradlew.bat
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
docker build -f src/main/docker/Dockerfile.native -t soulnotes-backend-native .
```

`Dockerfile.native` 基于 ubi9-minimal, 无 JVM, 启动更快、内存占用更低. 也可直接下载 Release 附着的 Linux 原生二进制 (CI 在每次 `v*` tag 推送时自动构建并归档). 镜像经 `quarkus.native.additional-build-args` 固定默认时区 (GraalVM 21 起 native 默认内置完整 tzdb) 并声明 Vosk FFM 的受限 native 访问. 运行时仍需按 §7 挂载/准备 `asr-model/` 目录.

### 6.6 演示数据初始化

依次执行 `sql_scripts/*_init.sql` 建表, 再导入 `sql_scripts/*_mock_data.sql` (演示数据); 也可交由 `--setup` 向导自动建库建表 (见 §2). 演示账号见 §11.

## 7. 本地语音识别 (ASR)

语音转录在本地完成 (Vosk, 经 JDK 25 FFM 直连 libvosk, 零 JNI/JNA), 不依赖任何外部转写服务 — 语音内容不出部署环境.

### 7.1 运行时目录

运行时目录 (默认 `asr-model/`, 经 `SOULNOTES_ASR_RUNTIME_DIR` 调整) 的布局:

```text
asr-model/
├── lib/     libvosk.(dll|so|dylib) + Windows MinGW 伴生 DLL (libstdc++-6 等, 须同目录)
└── model/   <模型目录>/ (含 am/ 或 conf/ 子目录即视为完整)
```

### 7.2 自动下载 (--setup 向导)

向导在保存 ASR 条目后检测运行时, 未就绪时询问是否立即下载 (§2), 下载来源:

- 模型: alphacephei 官方 small 中文模型 `vosk-model-small-cn-0.22` (~42MB, 16kHz), 解压至 `model/` 之下
- 动态库: Maven Central 官方 `vosk-0.3.45` JAR (默认 aliyun 镜像), 按平台 entry 提取 libvosk 与 Windows 伴生 DLL; `SOULNOTES_ASR_LIB_URL` 可整体覆盖 JAR 地址 (如指向含 arm 构建的更新版本)

下载失败不阻塞流程, 可随时手动放置后重启.

### 7.3 手动放置

- 模型: 从 https://alphacephei.com/vosk/models 下载 zip, 解压使 `model/<模型目录>/` 下存在 `am/` 与 `conf/`
- 动态库: 从 vosk 官方 release zip 或 `vosk-0.3.45.jar` (win32-x86-64 entry) 提取, 全部 DLL 置于 `lib/` (Windows 版 libvosk.dll 为 MinGW 构建, `libstdc++-6.dll` 等伴生 DLL 必须同目录)

### 7.4 上传接口与引擎接口

- `POST /api/v1/voice/upload` (multipart): 大小与 RIFF/WAVE 头校验 → 落盘 → 本地引擎**同步转录**, 响应直接携带 `transcribedText`; 仅接受 16kHz 单声道 PCM16 WAV (前端 Web Audio 产出约束), 其他格式返回 `400 VOICE_FORMAT_UNSUPPORTED`; 转录失败不回 5xx (状态机标记后正常响应)
- 语音上传独立限流: `SOULNOTES_RATE_LIMIT_VOICE` (默认 10 次/分钟, 本地转录单请求成本高于聊天)
- 引擎可插拔: 转录入口统一为 `IAsrEngine` (`Uni<AsrResult> transcribe(path)`), 当前仅内置 `vosk` 实现, `SOULNOTES_ASR_ENGINE` 配置其他值将拒绝启动
- 运行时未就绪仅 WARN 不阻断启动: 文字链路与离线热线兜底完整可用 (AI 密钥缺失则 BLOCK, 两者不对称是有意的)

## 8. 机构对接 (Webhook 预警通道)

RED 预警通知渠道接口化为 `IAlertNotifier`, 内置两个互为冗余的实现:

- `WebSocketAlertNotifier` (总是启用): 面向在线前端, 实时弹窗推送热线
- `WebhookAlertNotifier` (可选): 面向机构服务端, 配置 `SOULNOTES_ALERT_WEBHOOK_URL` 后启用 (空 = 禁用)

Webhook 行为契约:

- RED 预警时 `POST` JSON 负载: `{type: "RED_ALERT", userId, level: "RED", reason, hotline}`
- `SOULNOTES_ALERT_WEBHOOK_TOKEN` 非空时请求携带 `Authorization: Bearer <token>`, 空 = 不带鉴权头
- fire-and-forget: 3s 超时, 网络失败/非 2xx 一律仅记 WARN 日志, 绝不阻塞或影响 WS 主预警链路
- 未来短信/IM 等渠道与 `IAlertNotifier` 同构接入, 零调用方改动

## 9. 知识包自定义 (心理小知识)

"心理小知识"检索的知识库支持机构整包替换:

- 位置: classpath 下 `knowledge/{包名}/tips.md`, 经 `SOULNOTES_KNOWLEDGE_PACK` 选择 (默认 `default`)
- 块格式 (钉死):

  ```markdown
  ### 压力管理
  keywords: stress,anxiety,sleep
  适度的压力能提升专注力与效率...
  ```

- `### 标题` 行开块, **紧随标题的 `keywords:` 行**为关键词标签 (块必需); 缺失 keywords 行的块整体跳过并记 WARN; keywords 值为空合法 (永不命中检索, 仍可随机推送)
- 文件必须为 **UTF-8 无 BOM** 编码 (BOM 会污染首个标题行导致解析失败)
- 包缺失或解析为空时自动回退内置 `default` 包
- 自定义包随应用打包 (或自建镜像时放入 `src/main/resources/knowledge/{包名}/`), native 镜像已配置显式包含 `knowledge/**`

## 10. 结构化输出 (临床标签预埋, "副医生")

面向"咨询员工作台"的预埋能力: AI 从"主医生"重定位为"副医生", 在共情回复之外产出结构化心理/人格标签供人类专家参考. 产出管线, 落库与咨询员消费端均已落地 (消费端见 §10.2).

- **开关**: `SOULNOTES_CLINICAL_TAGGING=on/true` 开启, **默认关闭** — 契约段随每条消息发送, 每请求新增数百 token, 默认关闭以控成本
- **提示词组合 (钉死)**: 机构自定义提示词在前, 功能契约段在后, 且契约段首行声明"以下输出契约优先级最高" (防机构提示词无意中破坏输出格式)
- **输出契约 (钉死)**: 回复正文之后必须以 HTML 注释块结束:

  ```text
  <!--soulnotes {"tags": ["..."], "riskLevel": "NONE|YELLOW|RED", "summary": "..."}-->
  ```

  选 HTML 注释而非 code fence: 注释在 markdown/HTML 渲染下天然不渲染 — 即使剥离失败透传, 前端用户也不可见; 标识符 `soulnotes` 固定 (解析器只认自家标记), schema 宽松 (未知字段忽略)
- **拆流**: 后端以宽容正则 (容忍 `<!---` / 空白 / `--!>` 变体) 取回复中最后一个块, Jackson 解析成功 → 正文剔除该块后返回前端 (`ChatMessageVo` 不变, 前端零改动, 仅见共情文本), 结构化结果当前仅 DEBUG 日志; 解析失败/无块 → 整条回复按纯文本透传 (优雅降级)
- **边界**: 剥离是主机制, 注释隐形仅是兜底 — 前端若以纯文本 (非 markdown) 渲染, 透传的注释会以原文可见, 两者缺一不可

### 10.1 结构定义配置化 (自然语言 → LLM 归一化 → 指纹缓存)

契约中的 "JSON 结构描述" 可由机构自定义 (`SOULNOTES_PROMPT_CLINICAL_SCHEMA`, 自然语言描述字段与语义), 因自然语言结构不稳定, 内置 **LLM 归一化 + SHA-256 指纹缓存** 层:

- **契约拆分**: 契约 = **固定壳** (优先级声明 + `<!--soulnotes {...}-->` 包装格式 + 三条硬性要求) + **结构定义节** (可配置). `soulnotes` 标识符与包装格式由系统固定 (拆流器正则强耦合), 勿在自定义描述中更改包装方式
- **默认免归一 (canonical)**: 内置 tags/riskLevel/summary 字段说明即为默认结构, 未配置或留空时直接使用, **零 LLM 调用** — 回滚到默认 = 永久稳定
- **归一化**: 自定义描述在启动时异步送 LLM 转换为严格 JSON Schema (draft 2020-12 形态: 顶层 object + properties/required), 产物经形态校验 (合法 JSON 对象且含 properties), 不合法重试一次
- **指纹缓存**: 按 `SHA-256(有效结构描述)` 多条目缓存, 持久化到工作目录 `config/clinical-schema-cache.json` (`{"entries":[{promptHash, schema, normalizedAt, model}]}`, 按归一时间保留最近 5 条) + 内存 L1. 自定义 A → 默认 → 回到 A 时命中 A 缓存, 回滚语义为缓存命中 + 行为一致
- **降级矩阵** (绝不让不稳定结构上线):

  | 场景 | 行为 |
  |------|------|
  | 默认结构 (未配置/留空) | 免归一直用, 零 LLM 调用 |
  | 自定义 + 归一化成功 | 契约下发归一化 JSON Schema, 落盘缓存 |
  | 自定义 + LLM 失败但缓存命中 | 使用缓存 |
  | 自定义 + LLM 失败且无缓存 | WARN + 结构化输出增强暂禁 (仅发基础提示词), 等下次启动重试 |

- **`config/clinical-schema-cache.json`**: 本地实例运行时产物, 已被 `.gitignore` 锚定排除; 文件损坏时自动忽略并从头累积, 不影响启动

### 10.2 咨询员消费端 (工作台 API)

拆流产出的结构化评估已落库 (`clinical_assessments` 表, riskLevel 仅 YELLOW/RED — NONE 不落库, best-effort 写入不阻断对话) 并提供咨询员消费端: `GET /api/v1/clinical/assessments` (风险队列) / `GET /api/v1/clinical/students/{userId}/assessments` (学生时间线) / `GET /api/v1/clinical/stats/summary` (聚合统计) / `WS /ws/clinical/feed` (实时推送). REST 与 WS 同门槛: COUNSELOR/ADMIN 角色 (WS 于升级握手期断言, 学生端 `/ws/chat` `/ws/alert` 行为不变); 列表端点分页参数缺席时按契约默认第 1 页 / 每页 20 条. 身份默认脱敏为 8 位稳定短码, 达到 `SOULNOTES_CLINICAL_REVEAL_LEVEL` (默认 RED) 的记录解锁实名; 聊天正文永不暴露. 评估流与预警链路 (`WarningDetectionAgent` → 弹窗/Webhook) 双源不混流: 工作台仅消费副医生评估表, 预警语义不变, 副医生 RED 仅作标记不触发预警动作.

## 11. 演示账号

导入 `sql_scripts/users_mock_data.sql` 后可用 (mock 数据, 统一密码):

| 用户名   | 密码            | 角色      |
|----------|-----------------|-----------|
| alice    | Soulnotes123!   | STUDENT   |
| bob      | Soulnotes123!   | STUDENT   |
| charlie  | Soulnotes123!   | COUNSELOR |
| diana    | Soulnotes123!   | ADMIN     |
| eve      | Soulnotes123!   | STUDENT   |

注意: mock 数据为固定盐 PBKDF2WithHmacSHA256 (210000 轮) 哈希, 仅用于演示, 生产账号使用随机盐. 当前 AI 密钥为占位符 (`placeholder`), 启动前校验会 BLOCK 拒绝启动, 需配置有效密钥.

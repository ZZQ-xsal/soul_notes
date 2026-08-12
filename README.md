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
| 运行时           | Java 21 (GraalVM), Quarkus 3.36                                       |
| 持久化           | Hibernate Reactive + Panache, PostgreSQL (quarkus-reactive-pg-client) |
| 缓存/限流/黑名单 | Redis (quarkus-redis-client)                                          |
| 实时通信         | quarkus-websockets-next (WS) + SSE                                    |
| AI               | quarkus-langchain4j-openai (声明式 @RegisterAiService)                |
| 认证             | quarkus-smallrye-jwt (HS256 对称密钥)                                 |
| 健康检查         | quarkus-smallrye-health (/q/health/live, /q/health/ready)             |
| JSON             | Jackson (JsonUtils 统一封装)                                          |

## 3. 本地开发

### 前置要求

- JDK 21
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

约 172 个单元测试, 覆盖异常体系 / 工具类 / DTO 边界 / Service 反射逻辑 / Resource 结构 / Agent 签名 / Retriever.

## 5. 部署

### 5.1 打包与运行

```bash
chmod +x gradlew      # 非 Windows 用户; Windows 请使用 gradlew.bat
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar
```

注意: 生产环境 (prod profile) 不加载 `application-dev.properties`, 必须通过环境变量注入 `USER_NAME` / `USER_PASSWORD` / `HASH_KEY` 等 (见"配置表").

### 5.2 JVM 镜像

```bash
chmod +x gradlew      # 非 Windows 用户; Windows 请使用 gradlew.bat
./gradlew build
docker build -f src/main/docker/Dockerfile.jvm -t soulnotes-backend .
```

`Dockerfile.jvm` 基于 UBI 9 的 OpenJDK 21 运行时基座, 分层复制 `build/quarkus-app` 产物.

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
- 语音文件通过 PVC `soulnotes-voice-pvc` 挂载至 `/data/voice_uploads` (`VOICE_STORAGE_DIR`)

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

以下环境变量对应 `application.properties` 的 `${VAR:default}` 占位, 未配置时使用默认值:

| 环境变量                      | 用途                                               | 默认值                                           |
|-------------------------------|----------------------------------------------------|--------------------------------------------------|
| `USER_NAME` / `USER_PASSWORD` | PostgreSQL 账号口令                                | 无 (必配; dev profile 默认 kurv / CHANGE_ME_DB_PASSWORD) |
| `URL`                         | PostgreSQL 响应式连接地址                          | `postgresql://localhost:5432/soulnotes`          |
| `REDIS_HOSTS`                 | Redis 地址, 容器/K8s 部署必配                      | `redis://localhost:6379`                         |
| `HASH_KEY`                    | JWT 签名密钥, 至少 32 字节, 未配置时启动 fail-fast | 无 (必配; dev profile 有本地占位密钥)            |
| `ORIGINS`                     | CORS 白名单                                        | `http://localhost:5173`                          |
| `RATE_LIMIT_CHAT`             | 聊天限流上限 (次/分钟)                             | `20`                                             |
| `RATE_LIMIT_LOGIN`            | 登录限流上限 (次/分钟)                             | `10`                                             |
| `CRISIS_HOTLINE_PRIMARY`      | RED 预警主热线                                     | `400-161-9995`                                   |
| `CRISIS_HOTLINE_BACKUP`       | RED 预警备用热线                                   | `12355`                                          |
| `CRISIS_HOTLINE_NAME`         | 热线名称                                           | `全国心理援助热线`                               |
| `VOICE_STORAGE_DIR`           | 语音文件存储目录                                   | `voice_uploads`                                  |
| `ASR_CALLBACK_API_KEY`        | ASR 回调密钥, 配置后强制校验 `X-API-Key` 请求头    | 空                                               |

其他说明:

- AI 模型配置项 `ai.openai.*` (api-key / endpoint / model-name): 默认占位符 `placeholder`, 真实接入需配置有效密钥
- `mp.jwt.verify.issuer` 固定为 `soul-notes`, 必须与 TokenService 签发的 issuer 一致, 不可更改
- `jwt.ttl-seconds` 默认 604800 秒 (7 天)

# ASR Spike Notes: FFM 直连 Vosk 可行性验证

- 日期: 2026-09-14 (执行于 2026-09-16)
- 分支: `feature/platform-asr`
- 结论: **GO** - JDK 25 FFM 直连 `libvosk.dll` 全链路 (加载/建模/流式喂块/取结果) 在 JVM 与 GraalVM Native Image 双侧验证通过, 无需 JNI/JNA/官方 vosk-java 绑定依赖.

## 1. 环境

| 项 | 值 |
|---|---|
| JDK | GraalVM CE 25.0.2 (`C:\Users\Lenovo\.jdks\graalvm-ce-25.0.2`), `java`/`javac` 均出自其 `bin/` |
| native-image | 25.0.2 (GraalVM CE 25.0.2+10.1); Windows 下为 `bin/native-image.cmd`, Git Bash 须经 `cmd //c` 调用 |
| C 工具链 | MSVC `cl.exe 19.44.35228` (native-image 自动探测, 无需手动 vcvars) |
| 平台 | Windows x86-64 (amd64) |

## 2. 资产来源

| 资产 | 来源 | 落位 |
|---|---|---|
| `libvosk.dll` | Maven Central `com.alphacephei:vosk:0.3.45` 官方 JAR 内提取 (win-x86_64), 版本 0.3.45 | `spike/lib/libvosk.dll` |
| 识别模型 | alphacephei 官方 `vosk-model-small-cn-0.22` (Chinese Vosk model for mobile, CER 23.54% speechio_02), 官方 zip 解压 | `spike/model/vosk-model-small-cn-0.22/` (含 `am/ conf/ graph/ ivector/`) |
| 测试音频 | 16kHz 单声道 PCM16 WAV, 3.00s (静音) | `spike/test_16k_mono.wav` |
| 头文件对照 | alphacephei 官方 `vosk_api.h` | `spike/vosk_api.h` |

### 2.1 下载 URL 与放置路径 (精确清单, 供复现)

| 资产 | 直链 URL | 放置路径 |
|---|---|---|
| libvosk (win64) | `https://github.com/alphacep/vosk-api/releases/download/v0.3.45/vosk-win64-0.3.45.zip` (releases 页: `https://github.com/alphacep/vosk-api/releases`) | zip 内全部 `*.dll` 解压至 `spike/lib/` |
| 识别模型 | `https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip` (模型列表: `https://alphacephei.com/vosk/models`) | 解压至 `spike/model/`, 以含 `am/` + `conf/` 的目录为准 |
| vosk_api.h | `https://github.com/alphacep/vosk-api/blob/master/src/vosk_api.h` (本机 raw 域 DNS 不通时走 jsDelivr: `https://cdn.jsdelivr.net/gh/alphacep/vosk-api@master/src/vosk_api.h`) | `spike/vosk_api.h` |
| libvosk 备选源 | Maven Central: `https://repo1.maven.org/maven2/com/alphacephei/vosk/0.3.45/` (从 `vosk-0.3.45.jar` 内提取 win-x86_64 dll) | 同上 |

//! 注意: `libvosk.dll` 是 MinGW 构建, `libstdc++-6.dll` / `libgcc_s_seh-1.dll` / `libwinpthread-1.dll` 必须与其同目录 (vosk-win64 zip 内已带); 真实大小 lib zip 14,882,445 B / 模型 zip 43,898,754 B, 下载后用 `unzip -t` 验完整. //? 早期残留的 9B "zip" 与 4.1MB 截断模型 zip / 404 HTML 冒充的 vosk_api.h 均已识别并替换.

`spike/` 整目录不入库 (二进制 + 模型资产, 已追加进 `.gitignore`).

## 3. 函数签名表 (vosk_api.h 钉死 snake_case 符号 vs FFM Layout)

绑定方式: `SymbolLookup.libraryLookup(Path.of(libPath), arena)` 按符号名直查 + `Linker.nativeLinker().downcallHandle(symbol, descriptor)`.

| C 符号 | C 签名 (vosk_api.h) | FFM FunctionDescriptor |
|---|---|---|
| `vosk_set_log_level` | `void f(int log_level)` | `ofVoid(JAVA_INT)` |
| `vosk_model_new` | `VoskModel *f(const char *model_path)` | `of(ADDRESS, ADDRESS)` |
| `vosk_model_free` | `void f(VoskModel *model)` | `ofVoid(ADDRESS)` |
| `vosk_recognizer_new` | `VoskRecognizer *f(VoskModel *model, float sample_rate)` | `of(ADDRESS, ADDRESS, JAVA_FLOAT)` |
| `vosk_recognizer_accept_waveform` | `int f(VoskRecognizer *rec, const char *data, int length)` | `of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)` |
| `vosk_recognizer_final_result` | `const char *f(VoskRecognizer *rec)` | `of(ADDRESS.withTargetLayout(sequenceLayout(64K, JAVA_BYTE)), ADDRESS)` |
| `vosk_recognizer_free` | `void f(VoskRecognizer *rec)` | `ofVoid(ADDRESS)` |

## 4. 运行记录 (cwd = spike/)

### 4.1 JVM 侧

```bash
~/.jdks/graalvm-ce-25.0.2/bin/java --enable-native-access=ALL-UNNAMED -cp . Spike
```

输出摘要: Vosk 加载模型 LOG 若干 (Kaldi 层日志, `vosk_set_log_level(0)` 不影响其输出) → `Model loaded` → `Recognizer created (sample_rate=16000.0)` → `WAV PCM payload: 96000 bytes (3.00 s)` → `All chunks fed` → `Resources freed` → `FinalResult: {"text" : ""}` → `OK`, EXIT=0.

静音音频得到空文本 JSON, 属合法成功输出.

### 4.2 Tracing Agent 采集

```bash
~/.jdks/graalvm-ce-25.0.2/bin/java -agentlib:native-image-agent=config-output-dir=meta --enable-native-access=ALL-UNNAMED -cp . Spike
```

产出 `meta/reachability-metadata.json` (GraalVM 25 起合并为单文件元数据).

### 4.3 Native 编译

```bash
cmd //c 'C:\Users\Lenovo\.jdks\graalvm-ce-25.0.2\bin\native-image.cmd --enable-native-access=ALL-UNNAMED -H:ConfigurationFileDirectories=meta -o spike-asr Spike'
```

一次成功: 23.6s, 产物 `spike-asr.exe` 16.49MB; 构建报告 `5 downcalls and 0 upcalls registered for foreign access`, FFM 下调用被 native-image 原生支持, 未触发 build-time 初始化固化问题.

### 4.4 Native 复验

```bash
./spike-asr.exe
```

输出与 JVM 侧逐行一致, `FinalResult: {"text" : ""}`, EXIT=0.

## 5. 坑清单

1. **FFM 下调用返回指针默认 0 长度段** (首跑踩中): `final_result` 返回的 `const char*` 若声明为裸 `ADDRESS`, 得到的 MemorySegment `byteSize=0`, `getString(0)` 直接抛 `IndexOutOfBoundsException: No null terminator found`. 修复: 返回布局改用 `ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(64K, JAVA_BYTE))` 开读取窗口.
2. **Windows 版 native-image 是 `.cmd` 包装器**: Git Bash 无法直接执行 `bin/native-image`, 须经 `cmd //c` 调用; MSVC 工具链被自动探测, 无需 vcvars.
3. **`vosk_set_log_level(0)` 压不掉 Kaldi LOG 行**: 模型加载期 `LOG (VoskAPI:...)` 走 Kaldi 自有通道直接写 stderr, 生产接入时若要安静日志需重定向或容忍之.
4. **(已证伪, 见 §4.5)** "downcall handle 若在类静态初始化中创建, native-image 可能将其 build-time 初始化后固化失效" — 对 GraalVM 25 不成立: 只要 `libraryLookup` 的路径字符串与 `FunctionDescriptor` 是编译期常量, 静态常量绑定可正常 AOT 注册并运行. 实例构造器绑定同样可行, 两种形态均经实测.

## 4.5 独立复验 (第二执行通道, 静态常量绑定形态)

另一执行实例独立下载运行时并重写绑定后全量复验, 结论与主通道一致 (GO), 且补证:

- 绑定形态: `SymbolLookup.libraryLookup("lib/libvosk.dll", Arena.ofAuto())` (路径为编译期常量) + 全部 `downcallHandle` 静态内联字段, `final_result` 仍用 `withTargetLayout(64KB)` 窗口.
- 运行时获取: alphacephei/GitHub 对单连接限速 ~15KB/s, 44MB 模型串行需 1 小时 — 改并行 HTTP Range 分块 (8 线程 ~2min), 脚本留存 `spike/pdl.py`.
- JDK 25 FFM 终版 API 差异: `Arena.allocateArray(Layout, byte[])` 不存在 → `allocateFrom(Layout, byte[])`; `MemorySegment.slice(...)` 静态方法不存在 → 实例方法 `asSlice(offset, length)`.
- JVM: `java --enable-native-access=ALL-UNNAMED -cp . Spike` → `FinalResult: {"text" : ""}`, exit 0.
- Agent: `meta/reachability-metadata.json` (934B, 纯 FFM 场景 agent 几乎无可采 — downcall 为静态注册).
- native-image: `--enable-native-access=ALL-UNNAMED -H:ConfigurationFileDirectories=meta -cp . -o spike_native Spike` → 24.0s 一次通过, `spike_native.exe` 16.4MB, 无告警; 二进制复验 exit 0.

//! Task 4+ 生产网关建议采用静态常量绑定形态: 描述符常量化 + 类加载时一次性绑定, 是 GraalVM 文档推荐且实测 AOT 安全的写法; lib 路径因此需为编译期常量 (相对 cwd), model 路径等运行时参数不受影响.

## 6. 参数草案 (供 Task 4+ 生产接入)

- 采样率: `16000.0f` (与前端语音管道约定一致).
- 流式分块: 8000 字节 (PCM16 即 0.25s) 一喂, 与 websocket 推流节奏同构; brief 原建议 4096 字节亦可, 仅粒度差异.
- 识别结果取用: `final_result` 返回串所有权仍属 recognizer, 必须立即拷贝为 Java String 再做后续调用/释放.
- 红线联动: FinalResult 文本进情感分析前, 本地兜底热线逻辑不得依赖 ASR 成功 (Offline Safety Net), ASR 失败时按空文本走降级分支.

## 7. 裁决依据 (Spec §4.1 通过门)

JVM 过 + native 过 = **GO**. 后续 ASR 任务按 FFM 直连方案推进, 不引入任何新依赖.

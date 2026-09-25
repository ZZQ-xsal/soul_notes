# Soul Notes 心声树洞 — 前端

面向大学生的多模态 AI 心理轻干预系统的前端 (React 19 + TypeScript + Vite)。

## 技术栈

| 领域       | 技术                                     |
|------------|------------------------------------------|
| 框架       | React 19 + TypeScript (strict)           |
| 构建       | Vite 6 (开发端口 5173, 与后端 CORS 白名单默认值一致) |
| 路由       | react-router-dom 7                       |
| 状态       | React Context + localStorage 持久化 (无额外状态库) |
| 图表       | 手写 SVG (零图表库依赖, 调色板通过 CVD/对比度校验) |
| 样式       | 原生 CSS 设计令牌, 明暗双主题 (prefers-color-scheme) |

## 启动

前置: 后端已按仓库根目录 README 启动 (`./gradlew quarkusDev`, http://localhost:8080)。

```bash
npm install
npm run dev      # http://localhost:5173
```

开发期 `/api` 与 `/ws` 由 Vite 代理到 `http://localhost:8080`, 无需改后端 CORS。

其他脚本:

```bash
npm run build    # tsc 类型检查 + 产物构建 (dist/)
npm run preview  # 本地预览构建产物
```

## 页面与功能

| 路由      | 功能                                                                 |
|-----------|----------------------------------------------------------------------|
| `/login` / `/register` | 登录注册 (演示账号一键填充, 密码规则与后端一致)           |
| `/diaries` | 日记列表 (分页 + 日期过滤) / 新建 (文字 + 语音录制) / 详情 (AI 情绪分析) / 删除 |
| `/weather` | 情绪天气预报: 日期范围过滤, 天气日卡, 积极/消极/焦虑趋势图, 数据表 |
| `/chat`   | 树洞对话: 会话列表, SSE 流式逐字回复 (失败降级非流式)               |
| `/crisis` | 求助资源 (公开可达): 热线信息 + 本地缓存离线兜底                     |

全局能力:

- **RED 高危预警**: 登录后连接 `/ws/alert` (token 经查询参数, 自动重连), 服务端推送高危信号时全屏弹窗展示热线; 日记分析结果为 RED 时本地也会触发弹窗 (双重兜底)。
- **离线热线兜底**: `/crisis/hotline` 结果缓存至 localStorage, 网络/服务不可用时使用缓存或内置默认值 (400-161-9995 / 12355)。
- **日记草稿**: 文字正文输入停顿 800ms 自动落 localStorage (按 userId 分键), 关弹窗/刷新/切页不丢, 下次打开编辑器自动回填并提示恢复时间; 提交成功后清除 (语音与录音不存草稿 — 体积超出 localStorage 配额)。
- **预约入口**: 机构的心理咨询预约地址 `appointmentUrl` 随 `/crisis/hotline` 与 RED 推送 (`/ws/alert`) 一并下发, 展示于求助资源页与 RED 弹窗; 空串 = 机构未配置 (无静态默认值), 入口整体隐藏。

## 与后端契约相关的已知边界

1. **会话历史**: 经 `GET /chat/sessions/{id}/messages` 拉取并回放 (该 DTO 只有 role/content, 无时间戳); 删除会话走 `DELETE /chat/sessions/{id}`。历史气泡不带时间, 仅在线新消息带。
2. **流式接口不返回 sessionId**: 新会话首轮回复后, 前端经会话列表回查最近会话实现"续聊" (启发式, 见 `ChatView.tsx`)。
3. **日记列表无总条数**: 后端 `GET /diaries` 只返回数组, 分页的"下一页"以本页是否满页推断。
4. **AI 占位符**: 后端 `ai.openai.api-key=placeholder` 时所有 LLM 调用走降级 (日记无 analysisResult, 聊天返回兜底文案), 前端已按此展示提示。
5. **语音分析链路**: 录音/选文件后前端经 Web Audio 转码为 16kHz 单声道 PCM16 WAV (后端仅接受该格式), 上传 `/voice/upload` 同步本地转录; 转写文本回填编辑器 (可修改) 并与语音一并提交 (VOICE + content); 转录失败 (`status=FAILED` / 上传异常 / 静音) 时回落纯语音提交, 行为与旧版一致。

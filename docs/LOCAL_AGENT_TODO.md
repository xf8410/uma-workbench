# 本地智能体待办（模型可选）

目标：软件不依赖收费 API 也能完成确定性审计；模型只负责可选的规划、摘要和工具选择，不能替代证据解析。

## 架构

1. **确定性工具层**
   - IL2CPP、ELF、SQLite、Archive、Session 解析
   - 文件检索、哈希、证据关联和结果验证
2. **本地智能体控制器**
   - 任务拆分、工具注册、权限审批
   - checkpoint、失败重试、预算、结果验证
   - 无模型时按确定性工作流运行
3. **可插拔模型层**
   - `NoModelProvider`
   - 手机本地小模型（摘要、分类、有限工具选择）
   - 用户电脑上的局域网本地模型
   - 云模型仅作为可选 Provider，不作为运行前提

## 实施顺序

- [x] 定义与厂商无关的 `ModelProvider` / `AgentProvider`
- [x] 增加 `NoModelProvider` 和确定性任务规划器（DeterministicAuditOrchestrator）
- [x] 工具能力清单、输入 Schema 和风险等级
- [x] 高风险工具逐次确认（ApprovableToolExecutor 已实现）
- [x] 执行预算映射：快速 / 标准 / 深入 / 极限
- [x] Room 持久化 Agent Run、Tool Call、审批和 checkpoint
- [x] AgentMode 权限矩阵 + 模式切换确认 + 系统提示注入
- [x] 群聊/单聊统一接入审批门与模式权限
- [x] 本地小模型运行时可插拔，不将超大模型权重打包进 APK
- [x] 局域网自托管模型连接（不要求厂商 API）
- [x] 模型输出必须由确定性工具或证据引用验证

## 非目标

- 不声称能够把 Kimi K3、GLM-5.2 等超大模型的“智商”抽离后嵌入 APK。
- 不把任何云 API 设为软件核心功能的前置条件。
- 不允许未经确认的远程写入、发布、删除或凭据读取。

## 摆设接线（2026-08-28）

- [x] ModelOutputVerifier 接入聊天链路：每次回复完成后自动验证，徽章随消息持久化展示
- [x] ExecutionBudget/ExecutionTier 接入聊天链路：输入区档位选择（快速/标准/深入/极限）映射 Agent 循环上限
- [x] DeterministicAuditOrchestrator + audit 包确定性分析器接入：新增「确定性审计」tab，来源分析 + 证据落库，零模型调用

## 本机小模型运行时（2026-08-31）

- [x] LocalSmallModelRuntime 插件契约 + 注册表：未来 JNI 内嵌引擎实现接口即可挂入，UI 与解析层只依赖注册表
- [x] 内置本机回环桥插件：llama.cpp server / Ollama on Termux / MLC 等跑在本机时经 127.0.0.1 接入，权重不进 APK
- [x] 独立配置存储 + ViewModel + 配置区 + 聊天页开关；解析优先级：本机小模型 > 局域网 > 云目录
- [x] Manifest 放开明文 HTTP（Android 9+ 默认禁止，否则局域网/回环在真机上是摆设）；公网强制 HTTPS 由 LanModelEndpoint.validate() 应用层执行

## 安卓16 / ColorOS 16 兼容（2026-09-01）

- [x] compileSdk/targetSdk 升至 API 36（AGP 8.7.3→8.9.2，CI Gradle 8.9→8.11.1）
- [x] Edge-to-Edge 强制适配：enableEdgeToEdge + 根布局 safeDrawing 避让，深色系统栏配浅色图标，修内容顶进系统栏
- [x] 修复桌宠前台服务在 Android 14+ 崩溃：补 FOREGROUND_SERVICE_SPECIAL_USE 权限 + API 34+ 显式三参 startForeground
- [x] POST_NOTIFICATIONS 运行时申请：桌宠通知在 Android 13+/ColorOS 上真正可见
- [x] 启动主题 windowBackground 配深色 #1E1E2E，消除启动白屏闪烁
- [x] 原生库 16KB 内存页对齐验证：libandroidx.graphics.path.so / libdatastore_shared_counter.so 的 PT_LOAD align 均为 0x4000，无需改动

## 桌宠接线 + ColorOS 悬浮窗守卫（2026-09-01）

- [x] 桌宠服务原为孤儿代码（全仓库无启动入口）：AI 配置页新增「桌面宠物」设置区，开关启停服务并持久化
- [x] showPet() 悬浮窗权限守卫：无权限优雅 stopSelf，不再 BadTokenException 崩溃
- [x] 无悬浮窗权限时 UI 引导跳系统授权页，从授权页返回自动刷新状态
- [x] versionCode 5 / versionName 1.3.1

## 悬浮胶囊顶栏（Agora 风格）+ 并行分支合并（2026-09-02）

- [x] 合并并行会话 OpenRouter 免费模型自动发现（fce760d..08da315）进 main，AiConfigurationScreen 冲突按并集解决：OpenRouter + LAN + 本机小模型 + 桌宠四个设置区全保留
- [x] 顶栏改 Agora 风格悬浮胶囊：safeDrawing 之外再下沉 16dp，48dp 高圆角胶囊（bgSurface），工作区名超长省略号，状态徽标不再挤压
- [x] 底部标签栏同风格胶囊化：28dp→44dp 触控面积，底部留白不贴手势条
- [x] versionCode 6 / versionName 1.4.0

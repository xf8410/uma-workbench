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
- [ ] 本地小模型运行时可插拔，不将超大模型权重打包进 APK
- [ ] 局域网自托管模型连接（不要求厂商 API）
- [x] 模型输出必须由确定性工具或证据引用验证

## 非目标

- 不声称能够把 Kimi K3、GLM-5.2 等超大模型的“智商”抽离后嵌入 APK。
- 不把任何云 API 设为软件核心功能的前置条件。
- 不允许未经确认的远程写入、发布、删除或凭据读取。

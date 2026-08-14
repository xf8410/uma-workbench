# 实现状态

## 已写入代码

- Android Compose 工程骨架
- Room 本地数据库、消息/任务/来源/证据/同步模型
- 本地优先消息入队和会话隔离字段
- 网络 ONLINE/SWITCHING/OFFLINE 监测
- WorkManager 唯一任务、网络约束和指数退避
- 有预算的审计契约和可恢复阶段状态机
- Content URI 分块读取和 SHA-256 指纹
- 文件选择器接入，来源入库，SHA-256 重复检测和审计队列
- SO ELF 头部和 SQLite 3 头部有界分析器
- IL2CPP global-metadata 魔数、版本、区段描述符和完整区段范围校验
- IL2CPP 所有非空区段的 256 KiB 分块扫描、逐块 SHA-256、Room 分批落库、分页查询和 checkpoint 恢复
- IL2CPP `string`/`stringLiteralData` 分块文本索引
- ZIP/TAR 魔数识别、流式清单、展开字节统计和路径逃逸检测（不执行、不落盘解压）
- ZIP/TAR 每 200 条分批 Room 索引、格式化 checkpoint 恢复、分页查询和幂等重放
- Session JSONL 原始行无损保存、每 500 条 checkpoint 恢复和分页查询
- Session 嵌套对象/数组有界标量规范化、字段路径索引、字段值关联查询和跨来源全局时间线
- 模型可选的本地智能体三层架构列入待办；云 API 不作为核心功能前置条件
- 未支持来源使用 `UNSUPPORTED` 状态，不再伪装成 `COMPLETE`
- GitHub 与 Agent/Memory 契约、脱敏诊断、Android CI

## 尚未声称完成

- GitHub API 客户端、分页缓存、设置页面和二次确认流程
- 原始归档保存、7z 和嵌套归档索引
- 完整 SO ELF Section/符号/Build ID 解析
- IL2CPP type/method/field/image/assembly 版本化实体布局和关联索引
- SQLite schema、Master/MetaMD5 解析器
- Session 非 JSONL 容器格式和语义级跨来源关联规则
- 多仓库去重扫描和证据关联
- 真正的可插拔本地 Agent/Model Provider
- MemoryLoader 的 Room 实现与强制会话加载
- SyncWorker 的真实幂等传输
- 桌宠资源、动画和生命周期处理
- JNI/Rust 实现

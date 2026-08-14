# 风险登记表

## P0 稳定性

- 对话串会话、重复/乱序/空白回复：所有事件必须带 conversation_id、run_id、sequence、idempotency_key。
- 存储丢失：事务、迁移、崩溃恢复、追加事件和 checkpoint。
- UI 阻塞：大文件、网络、解压、解析和图片处理只能在后台。
- Android 进程回收：任务状态和进度写入数据库，WorkManager 可恢复。
- Wi-Fi 自动切换：离线队列、指数退避、幂等同步、非侵入状态条。

## P0 Native/运行时

- Rust panic 不得跨 JNI/FFI；统一 catch_unwind 和结构化错误。
- JNIEnv 不跨线程保存；线程 attach/detach；不传局部引用。
- 不保存 IL2CPP 托管对象裸指针；对象数据只做短生命周期快照。
- Hook 必须有线程局部重入保护、熔断和版本指纹。
- 不假设偏移、密钥或 metadata 跨版本稳定。
- 显式处理 ABI、结构布局、端序、编码和对齐。
- W^X/mprotect 失败必须安全降级，不循环重试。

## P1 数据正确性

- SO 与 global-metadata.dat 必须做哈希/版本匹配。
- SQLite 读取考虑 WAL/SHM/journal，并使用一致性快照。
- Master、DB、端点和多语言表按稳定 ID 关联，不按行号。
- MD5 只作指纹；同时保存 SHA-256、大小和版本。
- 旧仓库、Fork、镜像和克隆必须区分；相同 Blob 可复用但独有差异不能跳过。
- 事实、推测、线索和已推翻结论分开保存。

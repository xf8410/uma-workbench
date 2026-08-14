# UMA Workbench 架构契约

## 总体链路

```text
Compose UI -> UseCase -> Repository -> Local/Remote DataSource
                                      -> WorkManager workers
                                      -> optional Rust native worker
```

UI 只订阅持久化状态，不直接调用网络、文件解析器、数据库 SQL 或 JNI。

## 模块职责

- `ui`：页面、状态展示、用户操作；禁止重 IO。
- `domain`：业务用例、策略、状态机和证据规则。
- `data`：Room、文件仓库、网络 API、缓存和迁移。
- `worker`：可恢复的审计、索引、上传、同步和解析任务。
- `native`：最小 JNI 边界、错误转换、线程绑定和生命周期管理。
- `rust`：二进制/协议/分块解析；不持有 Activity、JNIEnv 或 UI 引用。
- `pet`：桌宠显示和动画；只订阅任务状态，不执行任务。

## 主控与子 Agent

主控只调度和汇总摘要。每个仓库、二进制、数据库和关联阶段都是独立 ChildTask。
子任务拥有时间、文件、内存、输出和并发预算；结果写入本地审计库后，主控只读取摘要和证据引用。

## 数据分层

1. 原始层：完整文件、归档和不可变字节。
2. 索引层：哈希、文件清单、字符串命中、类/方法/字段、Schema、端点候选。
3. 语义层：中文名称、玩家解释、置信度、来源和冲突。
4. 结论层：已确认、候选、过时、失效、待验证。

## 任务状态

`QUEUED -> RUNNING -> PAUSED/RETRY_WAIT -> COMPLETED/FAILED/CANCELLED`。
每个阶段必须有 checkpoint；页面关闭、进程回收、网络切换都不能丢失已完成结果。

# 实施状态

## 已完成底座

- Android/Compose 工程与 CI；
- Room 数据库和显式迁移；
- WorkManager 可恢复任务；
- ZIP/TAR 归档索引；
- Session JSONL 无损原文、时间线和字段索引；
- IL2CPP metadata 有界索引；
- GitHub 操作策略契约；
- 诊断报告基础结构。

## 本批完成：安全操作但禁止脱敏

- 诊断消息不再使用正则替换 Token/Cookie/Authorization/password；
- 诊断字段由 `redactedMessage` 改为 `message`；
- 新增无损诊断回归测试；
- 建立 `LOSSLESS_DATA_POLICY.md`，明确所有研究数据禁止自动/默认脱敏；
- 建立 `SAFE_OPERATIONS.md`，把安全边界放在确认、权限、目的地、哈希、事务和回滚上；
- 更新风险登记表，区分应用自身长期凭据与用户研究数据；
- 保留 GitHub 远程写确认、未知插件警告和危险操作审批。

## 下一批安全功能

1. 通用 `OperationIntent` 与一次性确认票据；
2. 上传预览：仓库、可见性、分支、路径、文件数、总字节数；
3. 删除/覆盖/清空/安装/部署统一确认 UI；
4. 原始文件与派生文件 SHA-256 验证；
5. 未知插件身份、ABI、来源和哈希展示；
6. 审计事件持久化；
7. 确认票据防重放和过期。

## 未完成

- 完整 Trae 式工作区 UI；
- 本地 Agent Runtime；
- Hachimi/hlpatch 连接器；
- Hachimi Edge 与 UmaPatcher Edge 源码魔改；
- 完整 APK/IL2CPP 语义解析；
- 正式 Release 流水线。

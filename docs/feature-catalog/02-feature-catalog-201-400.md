# 功能目录 201–400

状态缩写：`C`=CONFIRMED，`D`=DERIVED，`P`=PROPOSED。

## 索引、搜索与比较 201–240

201 D 增量索引；202 D 索引断点恢复；203 D 索引进度；204 D 索引取消；205 D 索引重建；206 D 解析器版本触发失效；207 D 文件哈希触发失效；208 D 局部索引更新；209 D 索引完整性检查；210 D 索引统计。
211 D 全局搜索；212 D 当前项目搜索；213 D 当前文件搜索；214 D 当前类搜索；215 D 搜索结果分组；216 D 搜索结果排序；217 D 搜索结果去重；218 D 命中上下文片段；219 D 搜索结果收藏；220 D 搜索结果导出。
221 D 两版本比较；222 D 多版本时间线；223 D 类新增/删除；224 D 方法签名变化；225 D 字段偏移变化；226 D 字符串变化；227 D APK 文件变化；228 D 配置变化；229 D 兼容性风险报告；230 D 变更可信度标记。
231 D 证据引用；232 D 文件引用；233 D 偏移引用；234 D JSONPath 引用；235 D 类引用；236 D 方法引用；237 D 字段引用；238 D Session 引用；239 D 引用重放；240 D 失效引用诊断。

## Agent 对话 241–280

241 C 工作区绑定对话；242 C 当前对象自动引用；243 D `@file`；244 D `@class`；245 D `@method`；246 D `@field`；247 D `@session`；248 D `@snapshot`；249 D `@protocol`；250 D `@report`。
251 D Ask 模式；252 D Investigate 模式；253 D Act 模式；254 D Observe 模式；255 D 模式权限说明；256 D 模式切换确认；257 D 只读默认；258 D 写操作审批；259 D 远程操作审批；260 D 高风险读取审批。
261 D 流式回复；262 D 停止生成；263 D 继续生成；264 D 重试；265 D 编辑后重发；266 D 对话分支；267 D 分支命名；268 D 分支比较；269 D 从 checkpoint 新会话；270 D 会话归档。
271 D 代码块复制；272 D 文件引用跳转；273 D 表格结果；274 D 可折叠推理摘要；275 D 工具步骤时间线；276 D 错误消息分类；277 D Token 用量；278 D 成本估算；279 D 模型切换；280 D 本地模型入口。

## 子 Agent 与任务编排 281–320

281 C 主 Agent 编排；282 C 子 Agent 独立上下文；283 C 子 Agent 仅回传摘要；284 C 证据引用回传；285 D IL2CPP Agent；286 D 协议 Agent；287 D 状态 Agent；288 D 事件 Agent；289 D 代码 Agent；290 D 归档 Agent。
291 D 临时子 Agent；292 D 长期角色身份；293 D 定期重建上下文；294 D 子 Agent 工具白名单；295 D 子 Agent 输出 Schema；296 D 最大输出 Token；297 D 子 Agent 超时；298 D 子 Agent 取消；299 D 子 Agent 重试；300 D 子 Agent 失败降级。
301 D 并行子任务；302 D 串行依赖；303 D DAG 任务；304 D 任务暂停；305 D 任务继续；306 D 任务跳过；307 D 任务重新执行；308 D 手工完成；309 D 任务审批；310 D 任务优先级。
311 D 任务卡片；312 D 运行状态；313 D 工具调用计数；314 D 上下文占用；315 D Token 用量；316 D 成本；317 D 产物列表；318 D 证据列表；319 D 阻塞原因；320 D 推荐下一步。

## 上下文、知识与 Artifact 321–360

321 C 原始数据不常驻上下文；322 C 对话不是数据库；323 C 原始层；324 C 结构化知识层；325 C 短期上下文层；326 D 检索层；327 D 上下文预算；328 D 主上下文 50–65% 目标；329 D 工具输出压缩展示；330 D 自动摘要。
331 D checkpoint；332 D checkpoint 恢复；333 D checkpoint Diff；334 D 已确认结论；335 D 暂定结论；336 D unknown；337 D 置信度；338 D 证据数量；339 D 游戏版本；340 D 解析器版本。
341 C Artifact 独立保存；342 D Markdown 报告；343 D JSON 报告；344 D CSV 报告；345 D Diff 报告；346 D 兼容性报告；347 D Hook 报告；348 D 插件报告；349 D APK 报告；350 D IL2CPP 报告。
351 D Artifact 版本；352 D Artifact 标签；353 D Artifact 收藏；354 D Artifact 搜索；355 D Artifact 引用；356 D Artifact 导出；357 D Artifact 删除确认；358 D Artifact 只读锁定；359 D Artifact 来源链；360 D Artifact 完整性哈希。

## SO / hlpatch 连接 361–400

361 C 连接 `127.0.0.1:18765`；362 C `/health`；363 C `/status`；364 C 连接状态机；365 D DISCONNECTED；366 D CONNECTING；367 D READY；368 D DEGRADED；369 D OVERLOADED；370 D INCOMPATIBLE。
371 D 自动发现；372 D 手工地址；373 D 连接超时；374 D 请求超时；375 D 单并发；376 D 请求队列；377 D 退避；378 D 熔断；379 D 冷却恢复；380 D 本地缓存。
381 C 类名搜索；382 C 字段读取；383 C 方法读取；384 C 方法名搜索；385 D 结果 limit；386 D cursor；387 D truncated；388 D nextCursor；389 D 搜索耗时；390 D 索引就绪状态。
391 D Hook 诊断；392 D 训练摘要；393 D Snapshot；394 D Changes；395 D 事件选项；396 D 事件观测；397 D 协议元数据；398 D Session 列表/导出；399 D 状态来源时间戳；400 C 禁止普通流程默认全量类扫描。

# JNI/Rust FFI 规则

- Rust FFI 函数只能返回拥有型、可释放、版本明确的数据或结构化错误。
- `catch_unwind` 不得让 panic 越过 FFI。
- Rust 不保存 `JNIEnv`、局部 jobject、Activity、View 或短生命周期字符串指针。
- 后台线程通过 JavaVM attach/detach；优先写持久化事件而非主动回调 UI。
- `unsafe` 限制在小模块，必须有不变量说明和测试。
- 二进制结构使用显式解析、长度检查、端序转换和版本校验，不直接指针强转。
- Native 任务取消、超时、内存上限和错误必须能映射到 Kotlin 状态。
- 运行时观测默认只读；任何修改行为都必须显式授权、版本匹配并安全降级。

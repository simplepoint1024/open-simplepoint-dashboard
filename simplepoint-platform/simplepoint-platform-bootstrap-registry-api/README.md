# Bootstrap 本地安装契约

纯 Java 21，无生产运行依赖。提供 `BootstrapLegacyTransfer` 的不可变 DTO 及
`BootstrapLegacyTransferPort` 的预览、原子接管、只读完成查询能力。

DTO 从 registry 实现工程移入此工程，完整类名和数据形状不变；应用发行包必须同时包含新 API JAR，
不得在旧实现 JAR 中保留重复类。没有新逻辑模块、迁移、持久表或运行时 RPC。

该端口面向受信安装宿主，不是认证凭据，也不自动对外导出。registry 拥有方通过本地绑定转交原事务服务；
安装编排不依赖实现、Spring/JPA、消息客户端或全局容器查找。
`findCompletion` 不得执行写入，返回空不能证明旧执行者已退出。

`verifyBootstrapRegistryApiDependencies` 拒绝所有生产运行依赖，并已进入根模块边界门禁。

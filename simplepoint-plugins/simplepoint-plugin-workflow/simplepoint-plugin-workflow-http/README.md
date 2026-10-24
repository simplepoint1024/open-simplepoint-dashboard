# 工作流 HTTP 适配模块

本工程持有工作流浏览器 REST 入口、错误协议、菜单/权限声明及工作流翻译。领域实现、Flowable、事务、投影和恢复任务继续由 `simplepoint-plugin-workflow-implementation` 拥有。

- 原 `simplepoint-service-workflow` 依赖本工程，沿用网关去前缀后的内部路径。
- 组合宿主选择 `workflow-http`，自动包含本地 `workflow`，以 `/workflow` 挂载同一组控制器。
- 仅选择领域 `workflow` 不暴露这些 HTTP 控制器。
- 本模块不拥有 SQL schema，不开启第二个工作流引擎，不依赖服务启动工程。
- 历史资源代码、资源拥有方 `workflow` 和翻译 namespace 保持；资源随工程移动，不复制到宿主。

详细接入和当前限制见[公共 HTTP 适配器](../../../simplepoint-platform/simplepoint-platform-http-servlet/README.md)。
资源清单和翻译已迁至 `META-INF/simplepoint/modules/workflow-http/`，通过声明初始化器注册，资源拥有方保持 `workflow`。
模块客户端分别声明消费资源注册和国际化注册契约，安装器需为 `workflow-http` 配置自己的凭据及提供方政策，不能借用领域或宿主凭据。
旧微服务继续兼容新声明路径。聚合前端打包与正式宿主安装仍未完成，因此本模块不是可独立运行的完整宿主。

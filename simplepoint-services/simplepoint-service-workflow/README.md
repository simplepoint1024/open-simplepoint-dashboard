# 流程中心服务

`simplepoint-service-workflow` 是独立的流程定义、版本、实例和任务服务，网关前缀为
`/workflow`，内嵌 Flowable 8 BPMN 引擎。Flowable 表只由本服务访问；平台业务版本保存在
`simpoint_workflow_*` 表中。

服务前端由 `simplepoint-react/modules/simplepoint-workflow` 构建并以
`/workflow/mf/mf-manifest.json` 发布。资源与菜单清单位于
`src/main/resources/META-INF/simplepoint/resources/workflow.json`。

本地构建：

```bash
./gradlew :simplepoint-services:simplepoint-service-workflow:installDist
cd simplepoint-react && pnpm build:workflow
```

完整的设计、安全边界和验收门禁见 `doc/design/workflow_center.md`。

流程变量与表单集成采用 `WorkflowFormContract` v1：变量声明指定类型、填写阶段、必填及默认值；
用户和角色分别保存用户 ID 与角色 authority。发起与办理接口按阶段校验变量，系统变量不能由调用方覆盖。
`GET /definitions/{id}/start-form?version=...` 与 `GET /tasks/{id}/form` 返回有权限、固定版本的运行时契约。
任务表单取实例启动时的版本，不读取当前草稿或最新发布版本。

外部表单绑定固定 `provider/formId/version/variableMappings`，分别支持发起和 BPMN 任务元素。
前端通过 `registerWorkflowFormAdapter` 注册可信提供方，服务端通过 `WorkflowFormProvider` Bean 校验提交。
绑定信息不能触发任意远程脚本或模块加载；任一侧未接入的提供方均不能提交。当前不提供虚假的表单引擎入口。

新发布版本的校验和覆盖 BPMN、基础信息及表单契约，历史复制恢复完整快照。
数据库升级为加列迁移，保留原版本校验和；旧版本没有的基础信息不会伪造成历史快照。
旧版仅 XML 的定义在下一次保存/发布时升级为完整快照，已运行实例仍绑定原来的版本。

表达式运行时显式设置空 Bean 白名单，流程作者不能通过 JUEL 访问应用的 Spring Bean。
这是对 [Flowable Spring 集成默认行为](https://www.flowable.com/open-source/docs/bpmn/ch05-Spring/) 的显式收紧，
并由 `WorkflowExpressionIsolationTest` 使用真实 Flowable 引擎验证，而不是仅校验前端输入文本。

# 工作流领域模块

工作流逻辑身份为 `workflow`。平台流程定义、版本和实例索引由本模块持有；Flowable
原生运行数据必须与工作流平台数据绑定到同一存储和事务管理器。

## 引擎装配

模块使用 `flowable-spring`，不依赖 Flowable Boot 自动配置。选中工作流模块后，
`ModularWorkflowEngineConfiguration` 从 `ModuleStorageBindings` 获取工作流的数据源及事务管理器，
通过 `WorkflowEngineFactory` 创建配置，再由 `WorkflowEngineServices` 暴露引擎和服务。
其他模块的数据源、`@Primary` 声明不参与工作流存储选择。

原独立服务通过 `LegacyWorkflowEngineConfiguration` 调用相同工厂，兼容原单数据源宿主。
这不代表模块化安装器或正式 standalone 宿主已经完成。

| 配置 | 默认值 | 约束 |
| --- | --- | --- |
| `flowable.database-schema-update` | `false` | 模块化运行期只允许 `false`；原独立服务保留显式 `true` 初始化兼容。禁止 `create-drop` |
| `flowable.database-schema` | 数据源当前 schema | 显式值必须等于数据源当前 schema，不能重定向到其他存储 |
| `flowable.check-process-definitions` | `false` | 必须为 `false`；流程通过平台发布服务部署，不自动扫描 classpath BPMN |
| `flowable.history-level` | `audit` | 只允许 `audit` 或 `full`，保持流程历史与平台查询所需数据 |
| `flowable.async-executor-activate` | `true` | 控制本宿主异步作业执行器；关闭宿主时引擎停止其执行器 |

以上是平台明确支持的配置项。移除 Flowable Boot starter 后，其他供应方 Boot 属性不再自动绑定；
新增需求须通过领域适配层显式支持并验证，不能把宿主全局配置隐式传入引擎。

## 扩展与事务

可信扩展通过 `WorkflowEngineCustomizer` Bean 注册，按 Spring 顺序执行。
工厂在扩展后验证数据源、事务管理器、schema、更新模式和历史级别，
并重新应用空 Bean 白名单、精确表达式工厂和平台活动行为工厂。
流程作者不能注册此扩展；流程表达式不能访问应用的 Spring Bean。
原事务内流程信号监听器仍由 `WorkflowEngineConfiguration` 注册。

工作流 JPA 操作与 Flowable 使用同一事务管理器，共同提交或回滚。
`ProcessEngineFactoryBean` 随上下文关闭引擎；不得另建全局引擎或使用宿主主数据源回退。
依赖门禁禁止本模块和原独立服务重新引入 Flowable Boot 自动配置。

## 安装与当前边界

- 模块自有十二张平台表通过 `@ModuleMigrations` 和 `db/manifest.properties` 安装。
  V2 增加名称投影状态和分代名称行，原 V1 保持不变。
- 通过 `@FlowableProcessSchema` 自动带入独立原生表贡献；统一计划安装 45 张原生表，
  无需启动临时引擎。模块化运行期关闭原生 DDL，缺失原生 schema 会启动失败。
  详见[原生表迁移说明](../simplepoint-plugin-workflow-migrations/README.md)；旧库接管和正式宿主安装编排仍待完成。
- 运行期引擎的版本检查不能替代完整物理结构核对或旧库接管。
- 详情的按 ID 用户/角色名称通过 `saas.audit-identities:2` 在身份模块读取；原有两个目录实体已移除。
  事项/任务复合读模型仍通过 `WorkflowReadSql.MEMBERS` 关联身份模块表，记录于组合计划 G22；
  部分节点详情也使用该复合视图，因此还不能宣称所有工作流查询支持独立身份库。

## 名称投影重建组件

`WorkflowMemberLabelProjection.advance(tenant)` 复用身份端 `saas.member-label-directory:1`，
按有界批次建立工作流自己拥有的名称投影。活动代与重建代分离，页面与续页位置同事务提交；
持久租约、代次和已观察源版本防止旧 worker 或倒退版本覆盖结果。重建入口必须在工作流事务外调用，
不能把调用者事务挂起后长期保留其连接与锁。

| `simplepoint.workflow.member-labels` 配置 | 默认值 |
| --- | --- |
| `pages-per-run` | 8，每页最多 200 条 |
| `pages-per-build` | 500 |
| `concurrent-runs` | 2，每个实例 |
| `run-timeout` | 10s，步骤间预算，进行中的 RPC 受平台传输超时控制 |
| `lease-duration` | 60s，采用数据库时钟 |

默认值自动绑定，非法或无上限的配置会被拒绝。源不可用保留已提交页面；进程退出可在租约到期后恢复。
`READY` 只表示完整投影已存在，查询仍须核对源版本；它不是授权或业务就绪信号。
本组件尚未接入列表查询、后台调度或 MQ 事件，不能用旧活动代绕过读取当前性检查。
详细协议和剩余工作见[名称投影设计](../../../doc/design/workflow_member_label_projection.md)。

## 引擎验证记录

真实 PostgreSQL 测试覆盖有/无外部主数据源的绑定、JPA 与原生流程共同回滚、
流程部署/启动/完成、缺失原生表时不建表及后台线程停止。
原服务兼容测试使用真实 H2 引擎。验证范围见
[工作流存储验证记录](../../../doc/verification/20261021-composition-workflow-storage.json)，
整体剩余工作见[组合计划](../../../doc/design/modular_service_composition_plan.md)。

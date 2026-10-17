# DNA / 表单 / Workflow 正式使用闭环验收

日期：2026-10-14。范围：单数据源表单业务、一级明细子表、版本化审批及失败恢复。

## 交付结果

基础业务闭环已部署到当前环境 `20261014-engine5`：设计/发布表单 → DNA 建表与主子记录事务 → 选择流程和映射变量 → 持久化提交意图及不可变快照 → 启动/领取/审批 → 驳回重提、撤回、终止 → 状态同步和历史查看。

九个服务统一使用本次发布标签：authorization、common、auditing、dna、ai、app、workflow、mcp-gateway、host。逐一核对了实际 JAR SHA-256、镜像标签、源码快照标识、容器健康和 Consul 检查；710 个部署源码文件仍与不可变发布快照一致。部署目录保留迁移、升级前数据库备份和九个回滚镜像。

这份报告声明的是下列已实现、已验证的能力，不代表后续高级功能或任意规模负载已经完成验收。

## 可直接查看的数据

登录示例组织后打开下列页面；普通用户仍须获得对应页面授权。

| 样例 | 实际覆盖内容 | 入口 |
| --- | --- | --- |
| 采购审批 | 网格、物理明细子表、Unicode、INT64/DECIMAL 精度、并发重放、快照、版本演进、驳回重提、撤回/终止 | [采购运行页](https://home.simplepoint.online:8000/#/app/runtime/65a1aff5-dbc7-430c-a5c4-9d70d2304e13/1d862d97-3fd4-4ecf-8a8c-bbb2af51fc18) |
| 客户档案 | 更新已有记录、乐观锁拒绝、当次版本快照、审批完成 | [客户档案运行页](https://home.simplepoint.online:8000/#/app/runtime/4d98a944-7c8e-43da-a19e-5d1b830d2028/174fb073-0b40-455c-8f0c-4cba7f0ed43d) |
| 巡检 | `9007199254740993.01` 与 `9007199254740993.00` 分别进入异常/常规复核，审批完成 | [巡检运行页](https://home.simplepoint.online:8000/#/app/runtime/2e9d5475-c954-47c4-a191-10855ccce8c5/b998740f-17fa-42ab-8a90-17a9560b45b3) |

在页面“提交记录”查看自己的业务快照和流程进度；“我的事项 / 我的待办”查看流程历史。采购入口固定到用于验证重提的表单 v1；同一应用另一个页面已升级到 v2，用于验证历史版本不被新发布覆盖。

[机器可核对的验收数据](form-workflow-20261014-data.json)包含应用、页面、表单、提交、记录、流程及关联数量，没有账号密码、令牌或真实客户数据。12 条核心提交的数据库状态与 Workflow API 状态一致，均已终结且每条恰有一个业务关联。普通用户权限验证的数据独立保留。

## 验证结果

| 门禁 | 结果与依据 |
| --- | --- |
| 后端全仓与规范 | Gradle 全仓 test、APP/Workflow/DNA 实现 Checkstyle 通过；523 个测试套件、3599 个用例、0 失败、0 错误；32 个环境型用例未在默认任务启用，实际数据库专项另列 |
| 实际数据库 | PostgreSQL 17 / MySQL 8.4 主子事务、精确快照、重放、回滚通过；Flowable 操作回执、绑定边界、精确表达式在独立 PostgreSQL 实际运行通过 |
| 前端 | 8 模块 TypeScript、全部生产构建、共享 FormRuntime/SForm 100 项用例、i18n 和微前端入口体积门禁通过；此前设计器、运行场景、个人事项浏览器回归通过 |
| 正式浏览器 | 真实提交主表及明细、精确数值、双击只发一次请求、标准提交历史、只读快照、待办审批完成；正常链路无脚本异常和失败 HTTP 响应；另主动丢弃已提交响应，验证冻结原输入、相同请求重试及唯一流程 |
| 绑定设计与流程入口 | 实际流程设计器读取发布版本/场景/类型兼容映射；流程中心通过 APP 适配器打开同一版本化业务表单 |
| 并发与历史 | 相同请求并发/重放只有一个提交和流程；变更原请求返回 409；源记录修改/删除不改变审批快照；新版本发布后重提保留原版本，明细未修改时保留原集合 |
| 流程操作 | 审批、驳回、重提、撤回、取消可重放；真实候选人领取后读取业务快照并完成审批 |
| 权限 | 普通用户未授权页面拒绝发现/发起；授予后成功；撤销后快照与审批拒绝且任务仍可继续；跨用户、真实跨租户上下文拒绝；提交目录按调用人过滤 |
| 故障恢复 | 暂停 Workflow，在 DNA 提交后 SIGKILL APP；原请求返回 500，租约到期后恢复同一记录和唯一流程，再次提交返回相同身份；绕过 APP 直接启动返回 403；恢复任务最终审批完成 |
| 发布 | 存量业务命名空间通过授权治理 API 补齐快照基础表；迁移、机器令牌刷新、九服务实际产物、健康与 Consul 核对通过 |

默认测试中的环境用例跳过不等于通过；本次启用的 PostgreSQL / MySQL 专项与真实环境证据独立保留。浏览器 fixture 用于可控错误交互，正式 HTTPS 浏览器用于证明真实部署，二者不互相替代。

## 验收中发现并修复的问题

- HTTP 参数缺少显式名称导致实际控制器 500：补齐名称，并增加真实 MVC 参数契约测试。
- 精确数值在快照/JSON 边界被浮点化：DNA 的 INT64/DECIMAL 使用字符串线路协议，快照解析保持 BigDecimal，权限投影后再次执行精确输出。
- Flowable 表达式把大整数阈值经过 double 转换：保留标准表达式语法及受控函数，仅替换精确常量解析/BigDecimal 转换；实际相等与相差 0.01 的分支测试通过。
- 原 APP 意图尚未启动时被直接 Workflow 调用抢先创建：仅允许已验证的业务服务调用作用域启动 APP 绑定流程，普通入口拒绝绕过。
- 快照呈现仍有编辑入口、字段标签缺失：共享 SForm 显示字段保留标签及精确文本，主子表只读模式剔除写入字段和明细操作。
- 一次空闲上游连接提前关闭：网关增加 10 秒空闲连接回收及 5 秒检查，不对写请求增加自动重放；空闲 15/25/65 秒后的实际读取均返回 200。

失败的精度/阈值验收证据保留，相关中断的合成流程通过正常取消接口关闭；没有改写历史数据来掩盖失败。

## 证据位置与复测

- 计划：[第 43 节](../design/application_form_engine_plan.md)。使用、升级、容量与恢复：[运维指南](form-workflow-operations.md)。
- 可重复运行的正式 HTTPS 验证器：[脚本与说明](../../scripts/verification/README.md)。每次创建独立客户/巡检样例，已受理请求内部仍以固定幂等身份重放。
- 受限现场证据：`.simplepoint/deploy203/release-20261014-engine1/` 的 `live-engine.json`、`live-scenarios.json`、`live-permissions.json`、`live-recovery.json`、`live-claim.json`、`live-browser.json`、`live-browser-retry.json`、`live-binding-browser.json`、`live-idle.json`、`final-database.json`、`backend-tests-final.json` 及浏览器截图。
- 最终发布证据：`.simplepoint/deploy203/release-20261014-engine5/` 的源码快照、运行 JAR 哈希、数据库备份、旧镜像引用和 Compose 快照。包含环境信息及备份的目录不得提交到仓库。
- 关键执行日志：`/tmp/sp-engine-closure-tests.log`、`/tmp/sp-engine-workflow-postgres-final.log`、`/tmp/sp-engine-precision-real.log`、`/tmp/sp-engine-closure-typecheck2.log`、`/tmp/sp-engine-closure-build.log`、`/tmp/sp-engine-closure-ui-tests.log`、`/tmp/sp-engine-live-scenarios2.log`、`/tmp/sp-engine-live-browser-final2.log`、`/tmp/sp-engine-live-binding-browser2.log`、`/tmp/sp-engine-live-permissions.log`、`/tmp/sp-engine-live-recovery.log`、`/tmp/sp-engine-live-claim2.log`、`/tmp/sp-engine-live-browser-retry.log`、`/tmp/sp-engine-release5-final-verify.log`、`/tmp/sp-engine-final-database.log`、`/tmp/sp-engine-live-idle.log`。

## 明确边界

本次正式使用范围为单数据源、最多 5 个一级明细集合、每集合 40 行/总计 200 行的表单聚合及版本化审批。审批读取冻结业务事实，节点输入使用流程变量，不支持在审批中任意改写原业务快照。状态同步采用持久化轮询及租约恢复。

跨数据源写入、分片/分批写入仍按用户已确认的后续规划；模板市场、任意脚本计算、通用跨源关系等高级扩展未计入完成。测试证明上述功能与失败路径，未进行面向未知生产峰值的容量承诺。正式业务接入仍按现有页面/字段权限、备份和运行监控要求配置。

# MQ、列表卡片与表单资源生命周期验收

日期：2026-10-15。当前环境版本：`20261015-mq2`。范围：计划第 44 节的三个阶段。

## 交付内容

| 范围 | 实现 |
| --- | --- |
| 平台 MQ 标准 | `simplepoint-events-api/jdbc/rabbit`；业务事务 outbox、持久 inbox、幂等、租约 fencing、回执、退避/DEAD、隔离、审计重放 SPI、指标、服务身份与独立订阅权限 |
| 统一列表/卡片 | Table、SimpleTable、DataTable 共享卡片；图标切换；按容器列数 × 行数计算页大小；首记录锚点换算；用户偏好；共享授权列、筛选、排序、选择与 TableActions |
| 表单资源生命周期 | 原发布机制完成上线；持久 OFFLINING 操作；common 回收授权、DNA 撤销投影，APP 收齐业务回执后 OFFLINE；重试、代次屏障、未完成提交/流程阻断；重新上线保留身份和数据，需要重新授权 |

旧 `simplepoint-data-amqp` 未参与当前构建，缺少业务事务可靠投递契约。本轮建立分层统一封装，没有将原 RPC 语义直接替换成异步事件。强制规范已加入根目录 `AGENTS.md`，详细接入/拓扑/运维契约见 `simplepoint-events/README.md`。

## 验证结果

| 检查 | 结果与证据 |
| --- | --- |
| 全仓后端测试 | `./gradlew test --max-workers=3` 通过，438 个 Gradle 任务；`/tmp/sp-mq2-all-test.log` |
| 前端 | 8 个模块类型检查、全部生产构建、14 个分页/设置模型测试通过；`/tmp/sp-mq2-types.log`、`sp-mq2-ui-build.log`、`sp-mq2-ui-tests.log` |
| 可靠事件 | 隔离 PostgreSQL/RabbitMQ 实测事务回滚、并发去重、租约恢复、确认不确定重发、离线消费、未 ACK 断连重投、路由缺失与身份伪造拒绝；`/tmp/sp-events-real-test.log` |
| 权限与并发 | 数据库测试覆盖历史授权排除；方法权限测试拒绝仅有运行资源权限的调用者；生命周期测试覆盖取消/失败发布、重复回执、跨租户、旧代次、重试、未完成提交；DNA 写锁屏障测试通过 |
| 三引擎基线 | `mq1` 已通过真实发布、多版本、主/明细数据、审批/驳回/重提/撤回/取消及运行流程阻断下线；`/tmp/sp-mq-lifecycle-live-engine.log`。`mq2` 继续使用该数据完成以下最终回归 |
| MQ 停机 | broker 停止超过 20 秒，APP/common/DNA readiness 和 Consul 仍正常；下线请求本地提交、重复 HTTP 只产生一条 outbox，入口立即关闭；恢复后自动收齐回执；`/tmp/sp-mq2-lifecycle-live.log` |
| 正式撤权与重新上线 | 授权有效记录与 DNA 绑定归零；重新上线资源 ID 不变、代次增加；普通用户返回 403；显式重新授权后恢复；旧操作重试不能关闭新代次 |
| 数据保留 | 普通用户的 45 条主记录查询前后相同；逐条 GET 核验 45 条明细、Unicode 文本和精确 int64 均保留；`/tmp/sp-mq2-detail-retention.log` |
| 浏览器卡片 | 列表第三页切卡片仍包含原锚点；1440/1000 宽度换算完整行分页，无水平溢出；切回恢复列表页大小；选择与刷新偏好正常；`/tmp/sp-mq2-cards-browser.log` |
| 浏览器查询/动作 | 筛选与排序跨视图保留；卡片编辑打开统一表单；删除使用标准确认；最后一条筛选结果删除后归零且页码/选择正确；`/tmp/sp-mq2-card-query.log` |
| 浏览器上下线 | 工作台 DataTable 卡片/列表切换；发布记录中平台动作打开生命周期；双重确认、异步完成、原版本重新发布通过；`/tmp/sp-mq2-lifecycle-ui.log` |
| 最终环境 | 9 个服务实际 JAR 哈希/镜像/源代码快照一致，容器和 Consul 全部健康；3 次下线共 21 条 outbox/inbox 工作均 DONE；`/tmp/sp-mq2-final-health.log`、`sp-mq2-final-jobs.log` |

新增事件与生命周期类的限定 Checkstyle 检查通过。全模块 Checkstyle 仍存在此前目录/组织读取、APP 目录等代码的历史问题，**未宣称全仓 Checkstyle 通过**。

## 真正发现并修复的问题

1. MQ 聚合健康导致 Consul 短暂剔除服务，重新上线 RPC 可能失败。服务发现和容器改用包含应用状态与控制数据库的 readiness；MQ 聚合健康仍保留用于监控。故障后发布可从原记录恢复。
2. 历史授权已软删除，但权限引导查询依赖尚未启用的 Hibernate 过滤器。统一授权与范围模板查询现显式排除 `deletedAt`，数据库测试覆盖无请求过滤器的场景。部署完成后执行授权版本刷新，失效旧缓存。
3. 较新的取消/失败发布曾遮蔽仍在服务或已正式下线的代次。生命周期查询现优先实际发布过的代次，已补回归。
4. 真实 RabbitMQ 消息用户身份需读 `receivedUserId`；真实 broker 测试发现后修复，来源校验与隔离测试通过。

## 当前验证数据与体验入口

- 应用：`MQ资源生命周期验收`（名称带创建时间）；在 **应用平台 → 工作台 → 查看 → 设计表单** 查找。
- applicationId：`aa348760-1d29-4742-9ce6-3f3aee91b1cc`
- formId：`7ed6681c-129d-49cd-8e02-51c934ea7bff`
- 主页面 pageId：`f4664c0d-e201-4527-9987-41272ec60ef9`，当前上线代次 4。
- 原版本审批入口：`aa0ebb42-6650-44de-8963-b033df4d51d9`，浏览器下线后已重新上线。
- 主页面直达：`https://home.simplepoint.online:8000/#/app/runtime/aa348760-1d29-4742-9ce6-3f3aee91b1cc/f4664c0d-e201-4527-9987-41272ec60ef9`。数据页右上角图标切换卡片。
- 生命周期入口：设计器 **发布记录 → 选择发布记录 → 资源上下线**。再次上线继续使用设计器的发布功能。

验收专用删除记录已经通过平台删除；45 条主/明细记录保留供查看。测试使用的管理员、租户管理者凭据和会话未写入仓库。`live-lifecycle.json`、`live-cards.json`、`live-card-query.json`、`live-detail-retention.json`、`live-lifecycle-ui.json`、截图与不可变快照保存在私有目录 `.simplepoint/deploy203/release-20261015-mq2/`。隔离测试容器已清理，当前环境 RabbitMQ 持久卷保留。

## 运维边界

- 当前环境是单节点 RabbitMQ、持久卷和 4 个持久 quorum 订阅；已验证进程停机恢复，**尚不具备跨主机高可用**。正式多节点高可用部署另需独立故障域与恢复演练。
- 生产服务不声明拓扑，先用运维权限部署版本化 definitions，再启用对应生产者/订阅者；服务使用隔离账号。MQ 故障期间必须监控积压、DEAD、磁盘/内存、隔离消息，readiness 通过不等于消息通道健康。
- `doc/deployment/sql/20261015-rbac-revoked-grant-version.sql` 必须在所有授权读取服务更新后执行；本次已执行。其作用仅为刷新涉及撤销记录的租户授权版本，不恢复或删除授权历史。
- 已备份控制数据库、旧镜像、配置和源码/JAR 清单。发生正式下线后，不能直接恢复不识别代次/下线屏障的旧写服务；需先封闭入口并处理在途操作，保留增量表和事件身份，按一致快照制定回退。不要把旧版本容器启动成功当作可安全回滚。

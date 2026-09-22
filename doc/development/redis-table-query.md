# Redis 标准表格查询协议

状态：2026-09-19，后台元数据查询、页面筛选/视图、有界详情预览和后端读写字段权限已接通并通过回归，旧同步扫描列表已删除。`/redis/entries` 仅保留写入接口，详情入口仍为 `/redis/entries/detail`。当前成果已随 `20260919-tables1` 发布，见 [发布记录](../release-notes/platform-tables-20260919.md)。创建/更新并发安全和真实集群验收仍待完成，不能据此宣布整个 Redis 页面或全平台任务完成；按用户收尾要求不继续扩展这些剩余项。

## 查询范围与一致性

新入口为 `GET /redis/entries/query` 和 `GET /redis/entries/query/schema`，均沿用管理员或 `redis.entries.view` 的实例级读取权限。不是租户私有数据接口；不得擅自按当前租户筛掉原本允许管理的 Redis 键。

后台 SCAN 完成后形成不可变元数据观察集，再应用平台 `$query`、表头条件、多列排序、分页与 count。查询与 SQL 使用同一套 `QueryCapabilities`、`QueryDocument` 和 `QueryValues`；无 SQL 依赖的 `CollectionQuery`、`QueryValues` 已移至 `simplepoint-core`。分页前处理完整观察集，不把当前页当作全集。

SCAN 可以重复返回键，且并发增删期间不是事务快照；`COUNT` 只是工作量提示，不能作为精确页大小。扫描结果按完整键名去重，保留第一次成功的元数据观察；扫描至取元数据之间已消失的键不计入观察集。界面必须标注“扫描结果”，不能声称结果与实时 Redis 完全一致。[Redis SCAN 文档](https://redis.io/docs/latest/commands/scan/)

每批最多 100 个键，通过管道发送单键元数据脚本，读取 TYPE、TTL 以及 STRLEN/HLEN/LLEN/SCARD/ZCARD/XLEN，不读取值。`ttlSeconds`、`size` 是观察时的值；`observedAt` 是批次开始时间，不是每个命令的精确执行时间。未识别类型保留 `UNKNOWN`，未知大小为 NULL。非 UTF-8 键使该扫描失败，不把有损解码后的名称当成另一个可操作键。

集群模式遍历所有主节点，不扫描副本。槽位覆盖不足、主节点不可用或扫描前后主节点/槽位映射变化时失败；不能把一个节点的结果称为全库结果。此路径已有模拟连接回归，尚未完成真实多节点集群验收。

## 请求和响应

传输参数：

- `page`：从 0 开始；`size`：1–100；`sort`：最多三个字段，可重复参数传递。
- `scanPattern`：Redis glob 扫描范围，缺省 `*`，最多 512 字符。它不是平台文本匹配运算符，也不执行用户脚本。
- `snapshotId`：固定一个观察集版本。翻页、筛选和排序时持续携带，不能静默换成另一个扫描结果。
- `refresh=true`：明确重建扫描；并发携带同一旧版本刷新的请求复用已经启动或完成的新版本。
- `$query` 和列条件：平台统一类型化协议。业务字段 `size` 与分页保留字同名，其筛选必须放入 `$query`，前端统一 `transportFilters` 已负责这一编码。

响应包含 `status`、`snapshotId`、`pattern`、`startedAt`、`completedAt`、`expiresAt`、`observedKeys`、资源预算、受控 `message` 和 `page`。只有 `READY` 携带分页数据；其他状态的 `page` 必须为 NULL，不能转换为空列表成功状态。

| 状态 | 含义与页面动作 |
| --- | --- |
| `BUILDING` | 扫描中，仅展示已观察的键数；当前页激活时低频轮询，保留查询条件。 |
| `READY` | 对同一完整观察集分页、筛选和排序，展示完成/过期时间。 |
| `EXPIRED` | 版本过期、被淘汰或写入后失效；提示刷新，不自动替换正在翻页的结果。 |
| `BUSY` | 另一个扫描占用唯一工作线程；稍后重试，不创建无界任务队列。 |
| `LIMIT_EXCEEDED` | 数量或估算内存超限；缩小键名模式后显式重试，不返回截断的“总数”。 |
| `TIMED_OUT` | 扫描超时；后台中断阻塞读取，缩小模式后重试。 |
| `FAILED` | 连接、ACL、数据格式等扫描错误；重试或检查服务配置，不暴露连接秘密/键值。 |

结果是进程本地缓存；重启、实例切换或淘汰会返回 EXPIRED，客户端必须提供恢复入口。缓存不保存请求授权上下文或键值正文；每次查询重新验证字段权限并执行输出投影。隐藏/脱敏字段禁止筛选与排序，隐藏/脱敏键名直接拒绝列表、schema 和总数。`editable`、`persistent` 分别继承来源 `type`、`ttlSeconds` 的严格字段限制。

成功新增、修改、删除通过 `invalidate()` 使所有观察集失效，运行中的旧扫描不能重新发布为有效版本。尚未改造旧编辑接口的其他行为，本协议不代表它们已经整体验收。

## 资源预算

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `simplepoint.redis.query.max-keys` | 100000 | 一个扫描允许的去重观察记录数。 |
| `simplepoint.redis.query.max-estimated-bytes` | 33554432 | 一个扫描的记录/键名估算内存，非 JVM 堆精确计量。 |
| `simplepoint.redis.query.max-snapshots` | 2 | 每进程最多保留的扫描范围数，LRU 淘汰。 |
| `simplepoint.redis.query.scan-timeout-seconds` | 60 | 扫描预算；独立定时器中断工作线程，并在批次/记录间检查截止时间。 |
| `simplepoint.redis.query.retention-seconds` | 300 | 完成后的结果保留期；失败也保留状态以避免自动重试风暴。 |

同时只允许一个后台扫描。配置仍须结合 Redis 驱动命令超时；如果驱动不响应线程中断，工作槽位不会提前释放并启动并行扫描。SCAN 单次返回量由 Redis 决定，估算预算不等于网络响应缓冲的硬上限。

## 页面接入与验证

页面使用平台 DataTable、TableActions、查询 schema 和用户视图存储。筛选与视图入口仅显示图标；不再保留顶部重复字段检索，独立保留 Redis glob 扫描范围。翻页、表头、高级条件及视图恢复固定同一 `snapshotId`；只有明确刷新或范围切换才重建扫描。未完成、过期、超限或失败不显示分页总数。BUILDING/BUSY 仅在标签页活跃时轮询；旧请求和取消请求不能替换新扫描版本。畸形状态/READY 分页响应作为可恢复错误处理，不能静默补出“成功的空列表”。

按钮由服务端权限过滤后的 schema 提供。值只在查看/编辑时读取；读取失败显示重试入口，返回键名与请求不一致时阻止操作，缺少字段、脱敏值及声明为 sampled/truncated 的预览不允许写回。编辑草稿取消需要确认。后端字段权限和大值读取限制在后续批次补齐，见下；不能只依赖这些前端保护。

键名不再 trim；删除参数采用 MultiValueMap 保留重复参数的原始值，不通过 Spring 的逗号拆分转换为 List。真实隔离 Redis 回归验证：带逗号的一个键不会误删两侧独立键，空格键精确读取/删除，成功写入使旧观察集失效。RedisEntrySummary 暂作为既有字段权限的归属类型保留，不可因旧列表删除而直接删掉或更换该权限标识。

开发 MSW 使用后端实际导出的 schema 和完整元数据查询 fixture，响应符合新状态协议；扫描结果与记录分离，写入后失效。查询不是对已分页结果做本地筛选。字符串大小按 UTF-8 字节计算，测试覆盖 Redis glob 的大小写、空格、转义和字节匹配语义。

最终验证：

- 后端 22 模块、165 测试类共 1273 项零失败/错误/跳过，镜像 `simplepoint-query-verify:redis-ui-regression`，日志 `/tmp/redis-ui-regression.log`；核对 19 个 Redis/审计测试 Java 源文件与验证镜像逐一同哈希。
- Redis HTTP 专项 9 项通过，含真实 schema/按钮权限一致性、旧同步列表返回 405 及精确键名删除；删除旧列表专用的三个单测，CRUD 的 18 项保留通过。API 生产 Checkstyle 及本次实现/控制器定向检查通过，日志 `/tmp/redis-ui-strict3.log`。
- 完整前端门禁通过：179 单测、六工程类型检查、国际化、五应用构建、MF 预算及 56 暴露模块浏览器回归，日志 `/tmp/redis-ui-quality3.log`。
- 对同一生产构建补验页内滚动/可达分页、超过 500 条分页、扫描固定版本、暂停轮询、表头与高级交集、视图保存/恢复、size 与分页参数隔离、EXPIRED/LIMIT_EXCEEDED/TIMED_OUT/FAILED 恢复、详情重试和不完整值禁止编辑，全部通过，日志 `/tmp/redis-ui-browser-final.log`。浏览器 API 使用隔离 fixtures，不宣称验证了真实用户的线上会话。
- 测试 Redis 容器及匿名卷已清理；未访问或更改业务 Redis。

## 有界预览与后端字段权限（2026-09-19）

`RedisEntryPreviewReader` 执行固定单键只读 Lua，将 TYPE/TTL/大小和预览放在同一次脚本内读取，避免详情拼接不同时刻的类型和值。字符串使用 GETRANGE，最多返回 262144 原始字节；Hash/Set 使用一次 HSCAN/SSCAN，最多选择 100 项；List/ZSet 逐项读取最多 100 项，Stream 最多 20 条。集合递归裁剪共用 262144 字节和 400 叶节点预算。代码不再调用 HGETALL/SMEMBERS 或完整 GET，然后在应用端截断。

上限约束脚本返回的数据，不宣称是 Redis 内部的分配/执行时间硬上限。HSCAN/SSCAN 的 COUNT 是提示，紧凑编码可能返回整个小集合；单个巨大集合元素及 XRANGE/HSCAN 内部结果仍可能产生临时开销。JSON 转义或 Base64 会放大应用响应，但在有界原始字节和叶节点数下仍然有界。此限制与 [Redis SCAN 保证](https://redis.io/docs/latest/commands/scan/) 一致，不能把 COUNT 当作精确页数。

详情显式携带 `sampled`、`truncated`、`valueEncoding`。非完整 UTF-8 字节以 Base64 展示，不能编辑；集合值以只读 JSON 展示，并保留截断/采样标记。字符串只有完整、可读、合法 UTF-8 且字段可写才返回 editable=true；页面明确显示编码和二进制提示。编辑命令限制 262144 UTF-8 字节，拒绝有损编码和非法 TTL；超出限制的旧值不允许用小摘要覆盖，批量删除最多 100 个参数。尚未实现并发版本比较，因此这些检查不是乐观锁。

`RedisEntryFieldPolicy` 同时考虑既有 RedisEntrySummary、RedisEntryDetail、RedisValueUpsertCommand 字段限制；详情 `value` 还继承原有 `valuePreview` 限制。隐藏/脱敏值在调用脚本前禁用值读取，详情使用显式 Map 投影，兼容实际 Jackson 3 响应转换器，不依赖序列化插件自动屏蔽。隐藏或脱敏身份拒绝查询/schema/详情/增删改；只读字段禁止写入，schema 同时移除不可用的新增/编辑按钮。类型字段只读不会错误地禁止编辑同一类型的字符串值。详情 JSON 不再重复嵌入可被外层权限隐藏的 size。

派生字段 persistent/editable 的 `@PermissionFields` 补齐 `resource = RedisEntrySummary.class`，避免空归属类型被公共策略判为 HIDDEN，导致存在任意字段策略时误隐藏无关字段。真实 schema/HTTP 回归覆盖只读值、脱敏值、隐藏大小、相关按钮和无关元数据保留。

本批最终后端：22 模块/165 类/1282 项零失败、错误、跳过，镜像 `simplepoint-query-verify:redis-preview-regression`，日志 `/tmp/redis-preview-regression.log`；22 个 Redis Java/Lua 及审计测试源文件与镜像同哈希。Redis HTTP 13 项、管理服务 23 项通过；API 生产和本次实现/控制器定向 Checkstyle 通过，日志 `/tmp/redis-preview4.log`。前端完整门禁（179 单测、类型、国际化、五应用构建、MF 预算、56 暴露模块浏览器回归）通过，日志 `/tmp/redis-preview-ui.log`；同一产物新增二进制只读提示浏览器回归通过，日志 `/tmp/redis-preview-browser.log`。后续 MSW 值大小限制同步并通过 mocks 类型检查，日志 `/tmp/redis-preview-mocks.log`。临时 Redis 容器和匿名卷已删除，业务数据未改动。

## 剩余验收

- 创建使用原子不存在写入，更新加入期望版本/类型/完整值校验与原子比较写入，处理值/类型/到期时间被其他请求改变的情况；当前检查后 SET 仍存在竞争窗口。
- 核验写入成功后的返回契约，避免回读失败被误报为整次写入未发生；补齐并发冲突时保留草稿、明确恢复的浏览器交互。
- 真实多主节点集群路径及拓扑变化验收。
- 整批七服务/Host 部署与发布后验证，部署必须同时切换新页面与新查询接口，不能把旧页面单独连到已删除旧列表的后端。

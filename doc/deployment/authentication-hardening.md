# 登录与认证安全加固

适用范围：本地密码登录、已关联外部身份登录后的本地双因素校验、TOTP 设置、浏览器会话、登录审计。代码和部署准备必须作为同一次升级交付；本说明不代表已通过等保测评。

## 已实现的行为

- 密码验证前按账号 ID 预占共享尝试额度，邮箱/手机号别名共用账号额度。登录入口另按输入标识限制未知账号尝试。默认 5 次，达到上限后锁定 15 分钟；成功验证清零对应额度。Redis 使用 Lua 原子操作，连接故障时拒绝认证，不退回单机计数。首次失败窗口也有 TTL，禁止无限增长的永久计数。
- 密码正确且账号启用 TOTP 时，只持久化无权限、无凭据的 `PendingMfaAuthentication`。挑战有效期 5 分钟；授权、授权确认、账户修改、外部身份关联均被阻断。不存在有效种子的已启用账号不能自动降级为单密码登录。
- 完成挑战时重新读取账号启用/锁定/到期状态及授权版本，验证码在共享存储中一次性消费，挑战本身也只能完成一次。外部身份首次关联在全部因素通过后才执行。仅最终验证成功写入成功审计，再轮换并保存会话 ID；审计本地存储失败不保留已认证会话。
- TOTP 注册有效期 5 分钟，已启用账号不能通过重新注册接口覆盖现有种子，也不能从设置页面再次读取活动种子。关闭 TOTP 必须再次验证当前密码和未使用的动态码。启用/关闭成功后更新授权版本并清除当前签发端会话，要求重新登录。
- 浏览器会话空闲上限 30 分钟、绝对上限 8 小时；升级前的旧会话缺少完成标记，会被要求重新登录。会话 principal 不保存密码哈希或种子。Cookie 默认 Secure、HttpOnly、SameSite=Lax。
- 网关使用服务端会话绑定的 CSRF token，`GET /csrf` 返回掩码令牌且禁止缓存。共享请求客户端为同源写请求和上传附加令牌，退出表单带 `_csrf`；不自动重试业务写请求。签发端使用自己的 CSRF 校验。OAuth token/introspection/revocation/device-authorization 机器端点保留协议兼容，浏览器 consent/device-verification 不豁免。AI 兼容 API、MCP 由各自显式令牌边界鉴权，不因携带网关 Cookie 获得访问权。
- 登录重定向之前必须完成 CSRF 初始化。WebFlux 网关通过同一个配置的 token repository 生成并保存新令牌，保留框架的会话 ID 轮换；Servlet 登录完成处理器解析当前 CSRF 过滤器提供的延迟令牌，再持久化完成状态。不得把首次生成推迟到进入页面后的并发 `/csrf` 请求：前者会删除其他请求仍在使用的 Redis 会话，引发登录跳转或 `Session was invalidated` 500；后者会生成互相覆盖的令牌，引发偶发 403。两端使用 `completedAt.v2` 标记，部署该修复后旧浏览器会话需重新登录一次。令牌掩码、登录时的会话轮换和缺失/错误令牌拒绝行为保持启用。
- 登录成功/失败通过 `security.login-audit.v1` 从 authorization 的 JDBC outbox 进入 auditing 的 inbox；处理事务包含日志写入与 inbox 完成。MQ 停机时保留待投递记录，消费者失败重试、重复投递去重、达到重试上限进入 DEAD。失败日志不再静默丢弃。旧的 `auditing.login-log` RPC 写入口已移除，避免绕过可信生产者和 inbox。事件不携带口令、种子或可用会话 ID；会话只保留 SHA-256 指纹，失败原因使用异常类型，登录前的租户/上下文请求头不作为可信审计范围。

## 上线前准备

1. 备份控制库、授权库、审计库及当前密钥；先在隔离库验证恢复。暂停登录和 TOTP 设置入口，避免种子格式迁移期间读写混用。
2. 在用户表所在库执行 [认证字段迁移](sql/20261020-authentication-hardening.sql)。在 authorization 和 auditing 使用的数据库分别执行 [事件表迁移](../../simplepoint-events/simplepoint-events-jdbc/src/main/resources/META-INF/simplepoint/events/schema-v1.sql)。若共用数据库，表只建一次，事件以 owner 隔离。
3. 在仓库外创建认证密钥文件：

   ```bash
   python3 scripts/security/create-auth-key-ring.py /secure/location/authentication-key-ring.json --key-id k20261020
   ```

   文件包含 `activeKeyId` 和 `keys` 映射，值为随机 32 字节密钥的 Base64 编码。生成器使用 0600 权限、拒绝覆盖、不会打印密钥。将同版本文件只读挂载给 authorization 和执行平台二次验证的 common 服务，配置 `simplepoint.security.authentication.key-ring-file`。可通过全局配置引用 `SIMPLEPOINT_AUTHENTICATION_KEY_RING_FILE`。禁止将文件提交 Git、放入镜像或写入 Consul 明文值。
4. 维护启动 authorization，临时设置 `simplepoint.security.authentication.migrate-secrets=true`。迁移按账号 ID 分批，使用 CAS 更新；已加密数据也可重新加密，所以可重入。正常认证绝不接受明文旧种子。迁移失败必须修复原因并重跑；完成后删除该开关再启动正常实例。此操作需要数据库已扩容且全部旧写实例已停用，不能依靠运行期自动 DDL。
5. 预先创建 RabbitMQ 服务身份 `authorization` 和 `auditing`，使用独立随机凭据。在已有拓扑中合并 [登录审计订阅](rabbitmq/login-audit-v1.json)，不要替换或清空其他订阅。消费者队列必须在启用生产者前存在。两个服务分别设置：

   ```properties
   spring.rabbitmq.host=<broker>
   spring.rabbitmq.virtual-host=/simplepoint
   spring.rabbitmq.username=<authorization 或 auditing，与 simplepoint.events.service 一致>
   spring.rabbitmq.password=<由秘密管理系统注入>
   simplepoint.events.declare-topology=false
   ```

   服务已默认启用持久事件并要求 correlated confirms/returns；缺少表、事件基础设施或错误服务身份会阻止正常启动，不能通过关闭事件来绕过登录审计。默认开发 Compose 没有完整 MQ 环境，需要先合并 [MQ 部署配置](rabbitmq/compose.yaml)。
6. 先启用 auditing 消费者，再部署 authorization、common 和 host 的新发行包。确认双方就绪、持久投递和前端令牌流程正常后，才重新开放登录入口。发行包必须包含重新构建的前端；不得用跳过前端的测试参数打包或发布。按 [平台事件说明](../../simplepoint-events/README.md)将容器和 Consul 的业务就绪检查指向 `/actuator/health/readiness`，并单独监控 broker/DEAD/积压，避免 MQ 故障导致可持久化审计的认证服务退出发现。
7. 正式入口使用可信 HTTPS。仅本机纯 HTTP 调试可显式设置 `SIMPLEPOINT_SESSION_COOKIE_SECURE=false`；正式环境保持 true。Redis 必须有认证、网络隔离和持久化保障；不得按普通缓存任意清理或淘汰 `simplepoint:authentication:v1:*`，否则会提前清空失败计数或重放状态。代理必须清洗外部转发头，应用只信任指定代理，审计客户端 IP 不取未经验证的 `X-Forwarded-For` 首项。

## 密钥轮换与回滚

先把旧、新解密密钥一起分发到所有读取方，再切换 `activeKeyId`。执行同一维护迁移，将存量种子改为新密钥加密；核对无旧版本数据及备份恢复方案后才能移除旧密钥。AES-256-GCM 使用随机 96 位 nonce，并将用途及账号 ID 放入 AAD，其他账号的密文不能直接复制使用。

数据库变更为加字段/扩列，但种子密文不兼容旧二进制。迁移后不能直接回滚旧认证代码；应回滚到支持密文格式的修复版本，或在封闭入口后按一致备份恢复数据库和旧服务。旧种子的明文备份本身属于秘密，必须限制访问并按保留策略销毁。密钥轮换应安排维护窗口并清除旧会话，避免不同版本同时写入。

本轮没有改变已签发 OAuth2 access/refresh token 的整体吊销机制；账户设置变更不能被解释为所有外部客户端令牌即时撤销。对需要强制全设备退出的场景，应同时执行平台现有令牌撤销及客户端授权清理流程。

## 验证与运行证据

回归测试覆盖实际授权服务器过滤链、密码到待验证状态、授权/账户修改阻断、过期挑战、CSRF 及机器端点兼容；独立 Redis 进程验证多实例并发额度与验证码/挑战单次消费；H2 实库验证 outbox 的独立事务、MQ 不可用后的恢复、重复接收、消费者失败回滚及种子迁移；加密测试覆盖篡改、账号移植、缺钥、旧钥读取和轮换。

部署后仍需验证真实代理/浏览器/数据库/RabbitMQ 链路：登录失败锁定和解锁、双因素正确/错误/重放、外部身份关联、设置开关、上传、退出、闲置到期、MQ 停机后的积压与恢复。审计保留、异地备份、防篡改存储及职责分离属于运行环境配置，本次代码不声称代替这些控制。保留策略不得提前删除 inbox 去重证据。

实现依据：[Spring Security WebFlux CSRF](https://docs.spring.io/spring-security/reference/reactive/exploits/csrf.html)、[SecurityContext 持久化和会话管理](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)。

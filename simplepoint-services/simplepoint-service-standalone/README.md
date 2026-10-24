# 单体宿主

一个 Servlet JVM 显式装配平台模块，复用微服务的领域实现、持久化、授权、事件及 HTTP 适配器。宿主不复制业务代码，不启动其他服务进程。发行包同时包含首页和六个前端模块。

```bash
./gradlew :simplepoint-services:simplepoint-service-standalone:installDist
docker build -f docker/runtime/Dockerfile.standalone -t simplepoint-standalone:<release> .
```

发行构建必须包含前端。健康检查之后还要验证实际登录、标准列表、表单/明细/计算、文件、审批、租户与权限，以及所有 federation 清单和声明资源。

## 复用现有环境

本项目正式部署使用原 PostgreSQL 业务库、Redis、RabbitMQ、MinIO、Consul，以及原签名密钥、已有数据对应的加密密钥和 DNA JDBC 驱动文件。原库副本、测试 Redis database、测试 MQ vhost 和测试对象桶仅用于演练。不得把这些测试隔离配置当成正式连接参数。

数据库采用模块迁移与显式历史接管，禁止运行时 ORM DDL。正式切换前按[安装器流程](../../simplepoint-platform/simplepoint-platform-installation-host/README.md)验证并处理旧写入者；旧进程不具备目录锁及部署世代校验时，不能直接与新进程并行修改同一业务库。

凭据由私有挂载配置注入。MFA 密钥环、OAuth 签名、对象存储、外部身份与 AI 加密材料分别按其拥有方复用；不能以“服务启动正常”判断旧数据可解密。运行目录中的驱动和插件需持久化挂载。

外部身份提供商的加密配置使用 `ExternalIdentityProviderProperties.PREFIX` 对应的 `simplepoint.security.oauth2.external-provider.credential-encryption-key`。迁移旧宿主环境变量时须验证实际属性绑定及加解密，不能省略 `security` 层级；配置文件含有相同密钥文本并不代表业务组件已经读取它。

默认不向共享服务目录导出重复 RPC provider；本地调用仍保留逻辑模块身份、可信调用校验和事务隔离。Consul 默认关闭，部署需为明确服务名配置有效的注册权限并启用，不能借用另一微服务名绕过 ACL。

### 共享签名密钥与本机验签

共享原身份库和签名材料时，单体保留原公开 OAuth issuer。浏览器入口和 issuer 不必同域，
但令牌校验的 issuer 必须与实际签发值一致。单体内部验证使用本机 JWK 端点，避免启动注册
依赖外部代理、证书信任和尚未启用的正式路由；签名算法显式匹配原密钥配置，不使用默认
RS256 猜测。当前环境使用 PS256：

```properties
spring.security.oauth2.resourceserver.jwt.issuer-uri=https://auth.simplepoint.online:8000
spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:18088/oauth2/jwks
spring.security.oauth2.resourceserver.jwt.jws-algorithms=PS256
```

此处本机 HTTP 地址仅用于同一 JVM 的公钥读取；它不取消 issuer、时间、签名或可信调用方
检查，也不能用于指向未经认证的外部密钥服务。

### 正式部署的配置与恢复材料

2026-10-22 正式入口使用 `https://single.simplepoint.online:8000`，独立容器为
`simplepoint-single`，与现有微服务共享原 PostgreSQL `simplepoint` 和原基础设施。
本机 HTTP 诊断端口绑定 `127.0.0.1:18089`，不直接对外发布。具体上线核对及验证限制见
[部署证据](../../doc/verification/20261022-composition-production-single.json)。

私有部署目录 `.simplepoint/single/formal-20261022` 保存两个 Compose 配置、只读挂载配置、
安装回执、切换前冻结备份及原入口配置；这些材料不进入 Git。单体使用该目录的
`compose-single.json`，现有微服务的有效 Compose 同步到 `.simplepoint/deploy203/compose.json`。
恢复旧二进制前必须同时审核数据库迁移、部署权限世代和 MQ 权限，不能只切回旧镜像。
上线后的业务写入也不能被切换前备份直接覆盖。

### 同库微服务的配套发行

微服务升级镜像从完整 `installDist` 构建，避免在旧镜像上叠加 JAR 后残留已迁移的控制器或提供者。通用运行镜像只接收指定发行目录和入口脚本，不把仓库中的私有部署配置加入构建上下文：

```bash
./gradlew :simplepoint-services:simplepoint-service-common:installDist
docker build -f docker/runtime/Dockerfile.service \
  --build-context distribution=simplepoint-services/simplepoint-service-common/build/install/simplepoint-service-common \
  --build-arg SERVICE=common --build-arg HEALTH_PORT=7000 \
  --build-arg HEALTH_PATH=/actuator/health/readiness \
  -t simplepoint/common:<release> .
```

健康端口必须与部署配置一致；仅对已启用 probes 的宿主选择 readiness 路径。镜像构建不会启动服务或修改原库。切换仍须核对旧初始化器的停用方式、资源注册归属、共享 MFA 密钥环、事件身份、持久驱动和总连接预算；不能直接覆盖线上镜像后启动。

## 数据库连接预算

共享同一 PostgreSQL 时，需合计现有微服务连接池、单体业务池、DNA 每个活动数据源及临时目录连接池，并为安装、运维和恢复留出连接。逐模块 EntityManager 不应因此创建独立的同库连接池。

| 配置 | 用途 |
| --- | --- |
| `spring.datasource.hikari.maximum-pool-size` | 单体默认存储池上限 |
| `spring.datasource.hikari.minimum-idle` | 单体默认存储池空闲保留量 |
| `simplepoint.dna.managed-pool.maximum-pool-size` | DNA 每个托管 JDBC 池上限，默认 10 |
| `simplepoint.dna.managed-pool.minimum-idle` | DNA 每池空闲保留量，默认 0 |
| `simplepoint.dna.managed-pool.connection-timeout-ms` | DNA 获取连接超时，默认 10000 |
| `simplepoint.dna.managed-pool.idle-timeout-ms` | DNA 空闲连接回收时间，默认 60000 |

池限制属于部署配置，与业务数据源的 JDBC 驱动属性分离。默认托管工厂明确使用 Hikari，并在断开、临时连接使用结束和模块停机时关闭实际连接池及驱动类加载器。其他工厂继续通过 DNA 的工厂接口扩展。

现有环境演练和本次正式双入口并行部署使用默认存储池上限 4、DNA 每池上限 2；这是同库并行的连接预算，不代表吞吐量或生产容量承诺。后续扩容需结合并发、事务嵌套和数据库容量验证。

## 首次查询的调用超时

隔离本地调用与 HTTP 调用共用 `simplepoint.service-router.http.read-timeout`，默认 3 秒。单体启动后的首次 Calcite 元数据加载可能超过该值；就绪状态 UP 不代表查询计划和目录缓存已经预热。当前共享设施演练及正式单体显式设为 `30s`，以重启后的首次业务查询验证，并保留嵌套调用的共同截止时间。该配置不改变业务幂等性，也不构成超时写操作可以重放的依据；正式预算仍需结合数据库及业务并发验证。

正式更新期间，同机机械盘上的构建竞争曾导致慢启动和一次首次列表查询超时。发布过程应先完成镜像构建，再逐服务更新并验证真实查询；避免把同时构建和冷启动时的健康检查结果作为容量结论。此次恢复后已重新通过实际页面验收，相关记录保留在上线证据中。

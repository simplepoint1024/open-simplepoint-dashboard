# DNA JDBC 驱动连接说明

## 1. 背景与目标

DNA 联邦查询能力提供了一个**只读 JDBC 驱动**，可以让外部工具或 Java 程序通过标准 JDBC 方式连接 DNA 联邦目录，完成：

1. 目录、Schema、表、列等元数据浏览。
2. 基于联邦目录的只读查询（含跨数据源联合查询）。
3. 使用现有系统用户统一登录，而不是另建一套独立账号体系。

当前版本的定位是 **read-only federation JDBC driver**，重点是"把 DNA 作为一个可被外部消费的查询入口"，而不是完整模拟一个可写数据库。

## 2. 前置条件

在外部工具接入前，DNA 侧至少要准备好下面几项：

### 2.1 联邦目录

- 已创建目标联邦目录。
- 联邦目录处于启用状态。
- 连接时可以指定 `catalogCode` 绑定某个联邦目录；也可以省略 `catalogCode`，此时驱动会列出当前用户有权访问的全部联邦目录，并可通过 `Connection#setCatalog(...)` 切换。

### 2.2 查询策略

当前 JDBC 查询会复用 DNA SQL 控制台执行链路，因此目标联邦目录必须存在一条**已启用**的查询策略，并且至少满足：

- `allowSqlConsole = true`
- `maxRows` 已配置且大于 `0`
- `timeoutMs` 已配置且大于 `0`

如果要执行跨数据源 Join，还需要：

- `allowCrossSourceJoin = true`

### 2.3 JDBC 连接用户授权

DNA 菜单里的 **JDBC连接用户** 页面可以给系统用户分配 JDBC 访问授权。

当前接入前至少需要给目标系统用户分配：

- `METADATA` — 用于连通性校验和元数据浏览。
- `QUERY` — 用于执行查询。

`EXPLAIN`、`DDL`、`DML` 目前只是为未来扩展预留，当前驱动不会实际使用。

### 2.4 系统用户

JDBC 登录直接复用系统里的用户账号，当前支持：

- 邮箱
- 手机号

密码也直接使用该系统用户的登录密码。

## 3. 驱动产物与打包方式

### 3.1 构建命令

在仓库根目录执行：

```bash
./gradlew :simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-jdbc-driver:build
```

当前构建会同时产出两个 JAR：

1. 常规模块 JAR
2. 可单独分发的 standalone JAR

其中更推荐外部分发和数据库工具接入时使用 standalone JAR：

```text
simplepoint-plugins/simplepoint-plugin-dna/simplepoint-plugin-dna-jdbc-driver/build/libs/simplepoint-plugin-dna-jdbc-driver-standalone.jar
```

常规模块 JAR 仍然会保留在：

```text
simplepoint-plugins/simplepoint-plugin-dna/simplepoint-plugin-dna-jdbc-driver/build/libs/simplepoint-plugin-dna-jdbc-driver.jar
```

### 3.2 驱动类

驱动主类为：

```text
org.simplepoint.plugin.dna.jdbc.driver.DnaJdbcDriver
```

模块已经通过 `META-INF/services/java.sql.Driver` 注册了 `java.sql.Driver` SPI，所以大多数 JDBC 工具在 classpath 正确时可以自动识别；如果工具要求手填驱动类名，就填上面的类名。

### 3.3 standalone JAR 的分发方式

`standalone` JAR 会把驱动运行所需的依赖一起打进去，并合并 `META-INF/services/*`，因此外部分发时优先使用它。

也就是说，在 DBeaver、DataGrip 之类数据库工具里，正常情况下只需要加入：

```text
simplepoint-plugin-dna-jdbc-driver-standalone.jar
```

不再需要手工额外补 Jackson 等运行时依赖。

如果要核对 standalone JAR 打进去了哪些运行时依赖，可以执行：

```bash
./gradlew :simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-jdbc-driver:dependencies --configuration runtimeClasspath
```

## 4. 连接地址与参数

### 4.1 JDBC URL 格式

DNA JDBC URL 前缀固定为：

```text
jdbc:simplepoint:dna:
```

完整格式为：

```text
jdbc:simplepoint:dna://<host>:<port>?catalogCode=<catalogCode>&tenantId=<tenantId>&contextId=<contextId>&schema=<schema>
```

驱动通过 TCP Socket 直连 DNA 服务。DNA 服务默认在以下端口监听：

```text
simplepoint.dna.jdbc.socket.port=15432
```

例如：

- 本地默认：`jdbc:simplepoint:dna://localhost:15432?catalogCode=PG`
- 自定义地址：`jdbc:simplepoint:dna://dna.example.com:15432?catalogCode=PG`
- 不指定目录（浏览全部授权目录）：`jdbc:simplepoint:dna://localhost:15432`

### 4.2 参数说明

#### 连接参数

| 参数 | 是否必填 | 说明 |
| --- | --- | --- |
| `user` | 是 | 系统用户邮箱或手机号。 |
| `password` | 是 | 系统用户密码。 |
| `catalogCode` | 否 | 目标联邦目录编码。省略时列出全部授权目录。 |
| `tenantId` | 否 | 指定租户 ID。 |
| `contextId` | 否 | 指定权限上下文 ID。 |
| `schema` | 否 | 默认 Schema。 |

#### 高级连接属性

以下属性通过 JDBC `Properties` 传递，用于调优 TCP Socket 行为：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `connectTimeout` | `5000` | Socket 连接超时（毫秒）。 |
| `socketTimeout` | `30000` | Socket 读取超时（毫秒）。影响元数据和查询的默认等待上限。 |

示例：

```java
Properties props = new Properties();
props.setProperty("user", "alice@example.com");
props.setProperty("password", "secret");
props.setProperty("connectTimeout", "10000");   // 10 秒连接超时
props.setProperty("socketTimeout", "60000");     // 60 秒读取超时
```

### 4.3 URL Query 与 Connection Properties 的优先级

驱动同时支持：

1. 把参数写在 JDBC URL query 里。
2. 通过 JDBC Properties 传参。

当两边同时存在时，**Properties 优先级更高**。

推荐做法：

- URL 里只放基础地址和 `catalogCode`
- 用户名、密码放在数据库工具的账号字段或 Properties 里

这样更安全，也更符合大多数 JDBC 工具的使用习惯。

## 5. 连接示例

### 5.1 直接连接 DNA Socket 端口

```text
jdbc:simplepoint:dna://localhost:15432?catalogCode=analytics
```

建议在连接属性里再填写：

```text
user=alice@example.com
password=******
tenantId=tenant-a
schema=reporting
```

### 5.2 Java 程序示例

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Properties;

public class DnaJdbcExample {

  public static void main(String[] args) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("user", "alice@example.com");
    properties.setProperty("password", "secret");
    properties.setProperty("catalogCode", "analytics");
    properties.setProperty("tenantId", "tenant-a");
    properties.setProperty("schema", "reporting");

    try (
        Connection connection = DriverManager.getConnection(
            "jdbc:simplepoint:dna://localhost:15432",
            properties
        );
        PreparedStatement statement = connection.prepareStatement(
            "SELECT * FROM reporting.order_summary WHERE tenant_code = ? LIMIT 10"
        )
    ) {
      statement.setString(1, "A");
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          System.out.println(resultSet.getString(1));
        }
      }
    }
  }
}
```

### 5.3 DBeaver / DataGrip 推荐填写项

| 项目 | 值 |
| --- | --- |
| Driver Class | `org.simplepoint.plugin.dna.jdbc.driver.DnaJdbcDriver` |
| JDBC URL | `jdbc:simplepoint:dna://host:15432?catalogCode=analytics` |
| User | 系统用户邮箱或手机号 |
| Password | 系统用户密码 |
| Driver Libraries | `simplepoint-plugin-dna-jdbc-driver-standalone.jar` |

> **提示**：DataGrip 中可在 Advanced 选项卡里设置 `connectTimeout` 和 `socketTimeout`。

## 6. 架构概览

### 6.1 通信协议

驱动通过自定义的二进制帧协议与 DNA Socket Server 通信：

```
┌─────────────────────────────────────────────────────┐
│  JDBC Driver (客户端)         DNA Socket Server (服务端) │
│                                                       │
│  DnaJdbcConnection            FederationJdbcSocketServer│
│    └─ DnaJdbcClient             └─ FederationJdbcDriverService│
│        └─ DnaJdbcSocketTransport                       │
│                                                       │
│  [4 字节帧长度][JSON 请求体]  ──TCP──>                  │
│                              <──TCP──  [4 字节帧长度][JSON 响应体]│
└─────────────────────────────────────────────────────┘
```

每次请求/响应均为一个完整帧：前 4 字节为大端序整型，表示后续 JSON 负载的字节长度。

### 6.2 元数据树结构

DNA 联邦目录元数据以 **catalog → schema → table** 的三级结构暴露：

- **Catalog**：联邦目录编码（`catalogCode`），对应一个独立的联邦查询域。
- **Schema**：联邦目录下的数据源编码（`datasourceCode`），每个底层数据源映射为一个 Schema。
- **Table**：底层数据源里的实际表名，保持原名不做转换。

在 DataGrip 等工具中，元数据树显示为：

```
DNA Connection
 └─ analytics              (Catalog = 联邦目录编码)
     ├─ mysql_orders        (Schema = 数据源编码)
     │   ├─ orders
     │   ├─ order_items
     │   └─ 用户表           (支持中文表名)
     ├─ pg_warehouse        (Schema = 数据源编码)
     │   ├─ products
     │   └─ inventory_v2
     └─ oracle_finance
         ├─ GL_ACCOUNTS
         └─ AP_INVOICES
```

### 6.3 元数据缓存

驱动服务端实现了**两级元数据缓存**，以减少对底层数据源的重复请求：

| 层级 | 存储 | 作用域 | 生命周期 |
| --- | --- | --- | --- |
| L1 | `ConcurrentHashMap` | 单个 JDBC 连接会话 | 会话关闭时清除 |
| L2 | Redis（可选） | 全局共享 | TTL 过期自动清除 |

L2 缓存是可选的：如果 DNA 服务配置了 Redis（`simplepoint-cache-redis`），则自动启用；否则退化为仅使用 L1 会话缓存。

L2 缓存相关配置：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `simplepoint.dna.jdbc.metadata.cache.ttl-seconds` | `300` | L2 缓存 TTL（秒）。 |

#### 手动刷新缓存

驱动支持通过 SQL 命令手动清除元数据缓存：

```sql
FLUSH CACHE
```

执行后会同时清除当前会话的 L1 缓存和全局 L2 Redis 缓存。该命令返回被清除的缓存条目数。

在 DataGrip 或 DBeaver 的 SQL 控制台里直接执行即可。Java 程序中可以这样使用：

```java
try (Statement stmt = connection.createStatement()) {
    stmt.execute("FLUSH CACHE");
}
```

### 6.4 连接健康检查与优雅降级

当联邦目录包含多个底层数据源时，如果某个数据源暂时不可用，驱动不会让整个连接失败，而是：

1. 跳过不可用的数据源，并在服务端日志中记录警告。
2. 继续返回其他可用数据源的元数据和查询结果。

这保证了在部分数据源故障时，工具仍然能正常浏览可用的 Schema 和表。

## 7. 运行时行为

### 7.1 认证方式

驱动通过 TCP Socket 与 DNA 服务建立长连接，连接时发送 PING 认证请求包含：

- 系统用户账号
- 系统用户密码
- `catalogCode`（可选）
- `tenantId`（可选）
- `contextId`（可选）

认证成功后返回当前用户信息、数据库产品名称及版本。

### 7.2 `tenantId` 和 `contextId` 的默认行为

- `tenantId` 未传时，服务端会优先从当前用户可访问租户中按 `tenantName → tenantId` 排序后取第一个；如果没有可用租户，则回退为 `default`。
- `contextId` 未传时，服务端会自动计算当前租户下的权限上下文。

### 7.3 默认 Schema

- 连接属性里的 `schema` 会作为初始默认 Schema。
- 如果未传 `schema`，驱动会把当前联邦目录编码作为默认 Schema。
- 运行期可以通过 `Connection#setSchema(...)` 切换默认 Schema。

### 7.4 工具兼容性

驱动针对 DataGrip、DBeaver 等数据库工具做了专门适配：

| 行为 | 说明 |
| --- | --- |
| `setAutoCommit(false)` | 静默接受，不抛异常。连接始终处于自动提交模式。 |
| `setReadOnly(true/false)` | 静默接受。连接始终只读。 |
| `setTransactionIsolation(...)` | 静默接受任意隔离级别。 |
| `commit()` / `rollback()` | 静默忽略。 |
| `setQueryTimeout(seconds)` | 实际生效，通过临时调整 Socket 读取超时实现。 |

这些行为保证了数据库工具的默认初始化流程不会因为"只读驱动不支持事务"而报错。

### 7.5 PreparedStatement 参数类型支持

`PreparedStatement` 在客户端将参数渲染为 SQL 字面量后发到服务端执行。当前支持的参数类型：

| Java 类型 | SQL 渲染方式 |
| --- | --- |
| `null` | `NULL` |
| `Number`（Integer, Long, Double, BigDecimal 等） | 数字字面量 |
| `Boolean` | `true` / `false` |
| `String`, `Character` | 单引号包围，自动转义 `'` → `''`、`\` → `\\`、`\0` → `\0` |
| `java.sql.Date`, `java.sql.Time`, `java.sql.Timestamp` | 单引号包围的 ISO 格式 |
| `java.time.LocalDate`, `LocalTime`, `LocalDateTime` | 单引号包围的 ISO 格式 |
| `java.time.OffsetDateTime`, `ZonedDateTime`, `Instant` | 单引号包围的 ISO 格式 |
| `java.util.UUID` | 单引号包围的 UUID 字符串 |
| `byte[]` | `X'hex'` 十六进制字面量 |

> **注意**：客户端参数渲染适合只读查询场景，但不等价于底层数据库原生预编译语句。

### 7.6 DatabaseMetaData 能力声明

驱动通过 `DatabaseMetaData` 准确声明以下能力，供工具判断可用特性：

| 能力 | 值 | 说明 |
| --- | --- | --- |
| `isReadOnly()` | `true` | 只读连接 |
| `supportsTransactions()` | `false` | 不支持事务 |
| `supportsBatchUpdates()` | `false` | 不支持批量更新 |
| `supportsStoredProcedures()` | `false` | 不支持存储过程 |
| `supportsResultSetType(FORWARD_ONLY)` | `true` | 支持 |
| `supportsResultSetType(SCROLL_INSENSITIVE)` | `true` | 支持 |
| `supportsResultSetType(SCROLL_SENSITIVE)` | `false` | 不支持 |
| `supportsResultSetConcurrency(*, CONCUR_READ_ONLY)` | `true` | 只支持只读 |
| `supportsResultSetConcurrency(*, CONCUR_UPDATABLE)` | `false` | 不支持可更新 |
| `supportsColumnAliasing()` | `true` | 支持列别名 |
| `supportsUnion()` / `supportsUnionAll()` | `true` | 支持 UNION |
| `supportsOuterJoins()` / `supportsFullOuterJoins()` | `true` | 支持外连接 |
| `supportsSubqueriesInExists/Ins/Comparisons` | `true` | 支持子查询 |
| `supportsCoreSQLGrammar()` | `true` | 支持核心 SQL 语法 |
| `supportsANSI92EntryLevelSQL()` | `true` | 支持 ANSI SQL-92 |
| `isCatalogAtStart()` | `true` | Catalog 在限定名起始位置 |

## 8. 服务端配置

### 8.1 Socket Server 配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `simplepoint.dna.jdbc.socket.enabled` | `true` | 是否启用 JDBC Socket 服务。 |
| `simplepoint.dna.jdbc.socket.host` | `0.0.0.0` | 监听地址。 |
| `simplepoint.dna.jdbc.socket.port` | `15432` | 监听端口。 |
| `simplepoint.dna.jdbc.socket.backlog` | `50` | TCP 连接积压队列大小。 |
| `simplepoint.dna.jdbc.socket.max-connections` | `200` | 最大并发连接数。超出后使用 CallerRuns 背压策略。 |
| `simplepoint.dna.jdbc.socket.idle-timeout` | `300000` | 空闲连接超时（毫秒），默认 5 分钟。超时后自动断开。 |

### 8.2 元数据缓存配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `simplepoint.dna.jdbc.metadata.cache.ttl-seconds` | `300` | Redis L2 缓存 TTL（秒）。 |

## 9. 支持的能力与限制

### 9.1 支持的 JDBC 接口

| 接口 | 支持程度 |
| --- | --- |
| `Connection` | 完整支持（只读模式） |
| `Statement` | 完整支持（只读查询） |
| `PreparedStatement` | 客户端参数渲染 |
| `DatabaseMetaData` | 完整支持（getCatalogs, getSchemas, getTables, getColumns, getPrimaryKeys, getIndexInfo, getImportedKeys, getExportedKeys, getTypeInfo） |
| `ResultSet` | TYPE_FORWARD_ONLY 和 TYPE_SCROLL_INSENSITIVE |
| `ResultSetMetaData` | 类型感知（signed/caseSensitive/nullable 根据列类型推导） |

### 9.2 不支持的能力

| 能力 | 说明 |
| --- | --- |
| 写入语句（INSERT/UPDATE/DELETE） | 只读驱动 |
| 事务控制 | 始终自动提交；`commit()`/`rollback()` 静默忽略 |
| Batch | `executeBatch()` 不支持 |
| 存储过程 | `prepareCall()` 不支持 |
| Savepoint | 不支持 |
| 生成主键返回 | 不支持 |
| CallableStatement | 不支持 |
| LOB 创建 | 不支持 |

## 10. 排障建议

### 10.1 确认端口监听

```bash
ss -lnt | grep 15432
```

### 10.2 测试连通性

如果端口已监听但工具连不上，可以先用最简单的 Java 测试：

```java
try (Connection conn = DriverManager.getConnection(
    "jdbc:simplepoint:dna://localhost:15432?catalogCode=test",
    "user@example.com", "password")) {
  System.out.println("连接成功: " + conn.getMetaData().getDatabaseProductName());
}
```

### 10.3 查看服务端日志

如果某个数据源的表加载不出来，检查 DNA 服务日志中是否有类似 `safeCollect` 的警告，这通常意味着该底层数据源暂时不可达。

## 11. 常见问题

### 11.1 连接时报 401 / 403

优先检查：

1. 系统用户账号或密码是否正确。
2. 该系统用户是否已启用。
3. 是否已在 **JDBC连接用户** 页面为该用户配置目标目录授权。
4. 是否至少授予了 `METADATA` / `QUERY`。

### 11.2 连接时报"联邦目录不存在或未启用"

说明 `catalogCode` 对应的联邦目录不存在，或者目录当前未启用。

### 11.3 能连上但查询失败，提示查询策略未开放 SQL 控制台

这是当前版本的正常约束。因为 JDBC 查询复用了 SQL 控制台执行链路，所以目标目录的有效查询策略必须开放 SQL 控制台能力。

### 11.4 跨数据源 Join 被拒绝

检查目标联邦目录生效中的查询策略是否开启了：

```text
allowCrossSourceJoin = true
```

### 11.5 数据库工具提示找不到类或驱动初始化失败

通常先检查是否误用了常规模块 JAR。外部分发时更推荐直接使用 standalone JAR：

```text
simplepoint-plugin-dna-jdbc-driver-standalone.jar
```

如果你明确使用的是常规模块 JAR，那么还需要把运行时依赖一起加入 classpath。

### 11.6 部分数据源的表加载不出来

1. 检查对应数据源是否正常运行且网络可达。
2. 执行 `FLUSH CACHE` 清除缓存后重新加载。
3. 查看服务端日志确认是否有连接超时或认证失败。

### 11.7 查询超时

可通过以下方式调整超时时间：

1. **连接级别**：在连接属性中设置 `socketTimeout`（毫秒）。
2. **语句级别**：调用 `Statement#setQueryTimeout(seconds)`，会临时覆盖连接超时。

### 11.8 元数据不是最新的

元数据会被缓存以提升性能。如果底层数据源的表结构发生了变化：

1. 执行 `FLUSH CACHE` 清除全部缓存。
2. 或者等待 L2 缓存 TTL 自然过期（默认 5 分钟）。
3. 或者断开并重新建立 JDBC 连接（清除 L1 会话缓存）。

## 12. 关联文档

- `doc/design/dna_federated_query_platform.md` — 联邦查询平台设计文档
- `doc/api/api_conventions.md` — API 通用约定

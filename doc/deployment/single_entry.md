# 单体版独立入口

更新时间：2026-10-22。用户已明确要求启用正式单体入口，当前已接入独立单 JVM 服务。

目标地址：`https://single.simplepoint.online:8000/`。

## 当前状态

- 本地 `simplepoint203-edge-1` 的 Nginx 已新增独立虚拟主机并完成语法检查、平滑重载。
- 使用原有本地 CA 和覆盖 `*.simplepoint.online` 的证书；客户端信任要求与现有 `home` 入口一致。
- 正式上游为 `simplepoint-single:18088`，承载 35 个显式模块；复用原 PostgreSQL `simplepoint`、Redis、RabbitMQ、MinIO、Consul 及原密钥和 JDBC 驱动。
- 登录页返回 HTTP 200，未认证首页进入登录；登录后直接使用现有账号、租户和业务数据。原微服务入口保留，九个服务已协调升级到兼容共享原库的完整发行镜像。
- 同端口 HTTP 跳转到 HTTPS；`/actuator`、`/internal` 路径保持拒绝访问。
- `/edge-health` 只表示入口存活，不代表单体业务就绪。
- 通过本机指定解析及正常公网 DNS 校验正式入口和 TLS，原 `home` 仍保留正常登录入口。
- 用户已添加公网 DNS。Google、Cloudflare 和本机解析均已验证：`single` → `www.simplepoint.online` → `123.120.57.191`。
- 新注册账号创建个人空间，二次登录保留同一空间；组织空间已验证办公表单、流程、计算、附件及权限。单体重启后回读审批和快照。详见本次上线证据。

## 已配置的 DNS

域名权威服务为阿里云 DNS。用户已参照现有 `home` 的别名方式添加：

| 配置项 | 值 |
| --- | --- |
| 记录类型 | CNAME |
| 主机记录 | `single` |
| 记录值 | `www.simplepoint.online` |
| TTL | 建议 600 秒 |

端口由访问 URL 和现有 8000 入口负责，不填写进 DNS 记录值。
DNS 设置不负责后端服务或端口映射；单体由本地入口代理独立转发。

## 部署与恢复

运行配置和迁移回执位于私有目录 `.simplepoint/single/formal-20261022/`；单体 Compose
为 `compose-single.json`，并行微服务的有效 Compose 同步到 `.simplepoint/deploy203/compose.json`。
Consul 由应用自动注册 `simplepoint-standalone-production`，就绪检查不依赖 RabbitMQ
短暂可用性；消息积压、DEAD 和实际业务回执需单独检查。

当前运行配置：`.simplepoint/deploy203/nginx.conf`。
切换前冻结原库备份及 Nginx 备份保存在正式部署私有目录。代理配置是 Docker bind mount，
须原位写入、执行 `nginx -t` 后平滑重载，不能通过替换 inode 留下旧挂载。
停用单体入口不等于回滚数据库；上线后不可直接用切换前备份覆盖新增业务数据。
恢复旧版本须同时评估数据库迁移、目录写入权限世代和消息兼容，不能只恢复旧服务镜像。

验证记录：[正式上线](../verification/20261022-composition-production-single.json)；
[原入口预置历史](../verification/20261021-single-entry.json)。

> 2026-09-22 更新：文中资源授权中心相关内容是历史发布记录；该独立功能已移除。

# 2026-09-19 标准表格阶段成果发布

用户先要求当前阶段收尾，随后明确要求部署。本次发布现有成果，不恢复全平台未完成项的继续开发。

## 发布范围

- 本地 `simplepoint203` 的 authorization、common、auditing、dna、ai、mcp-gateway、host 已重新构建并切换为 `simplepoint203/<service>:20260919-tables1`。
- 7 个运行容器的镜像 ID 均与最终构建标签一致，全部 healthy；最终容器启动及真实查询检查没有记录 ERROR 或旧作用域查询异常。
- 入口仍为 `https://home.simplepoint.online:8000`，保留现有本地 CA 和 HTTP/2；未登录 HTTPS 请求正常 302，HTTP 同端口 308 跳转。
- 既有 PostgreSQL `simplepoint` 库已在事务外执行 `20260919-access-center-query-indexes.sql`，三个索引均 valid/ready，列顺序与脚本一致。仅增加索引，未删除业务记录。
- 本地部署构建配置移除已停止监听的 7891 代理，改用已验证可用的直连。未更改用户访问入口或运行服务的网络代理策略。

## 发布中发现并修复的兼容问题

真实 AI 请求暴露了 Spring Data 接口默认方法分派问题：`DefaultMethodInvokingMethodInterceptor` 优先调用 API 默认的 `limitScoped`，绕过 JPA 目标实现并抛出 UnsupportedOperationException。此前直接构造实现类的数据库测试未覆盖该代理行为。

JPA `BaseRepository` 现在将 `limitScoped`、`limitInScope`、`limitMemberships` 重新声明为抽象方法，使 Spring Data 代理正确调用已有实现；非 JPA 仓库不支持时仍明确拒绝，不降级、不跳过作用域。新增 3 项真正使用 `JpaRepositoryFactory`、业务仓库双接口继承和数据库的回归，覆盖标量、集合、NOT IN、分页/count 与空集合拒绝。三项在修复前均复现默认方法异常，最终通过。没有改变权限策略或继续扩展 Redis 并发改造。

## 验证

- 最终后端验证镜像 `simplepoint-query-verify:proxy-dispatch-regression`：22 个模块、165 个测试类、1285 项测试，失败/错误/跳过均为 0；JPA 生产 Checkstyle 通过。当前 1774 个 Java/Lua/Gradle 文件与该验证镜像 SHA-256 一致。日志 `/tmp/simplepoint-deploy-20260919-regression.log`。
- 本次重新执行 `pnpm check:frontend`：179 项单测、六工程 TypeScript、国际化、五应用构建、MF 预算和浏览器检查通过。日志 `/tmp/simplepoint-deploy-20260919-frontend.log`。
- 从最终运行 Host JAR 提取静态文件，远端模块经真实 HTTPS edge 加载，再执行浏览器回归：56 个暴露模块及专项场景通过，无非预期异常、警告或失败请求。此部分业务 API 使用隔离 fixtures。日志 `/tmp/simplepoint-deploy-20260919-browser-final.log`。
- 另以已有部署账号完成正常 OAuth 登录，对实际业务接口进行只读检查：AI 供应商/API Key/模型列表、资源列表、资源排序字段高级筛选均 HTTP 200，并返回真实分页结构；不打印业务数据或凭据，不修改业务记录、权限、偏好。正常登录会产生会话和登录审计记录。日志 `/tmp/simplepoint-deploy-20260919-http.log`。这不等同于全部角色/页面/写入流程验收。
- 一次性回归 Redis 容器及匿名卷已删除，未连接业务 Redis 执行测试写入。

## 备份与回滚边界

- 部署前完整应用分发包、原 compose 配置和 PostgreSQL custom-format dump 保存在本地忽略目录 `.simplepoint/deploy203/release-20260919-tables1-backup/`；目录权限 700，数据库和配置备份权限 600。dump 已通过归档目录检查，未执行恢复。
- 原运行镜像 ID 已无法通过 Docker 镜像库寻址，因此备份是实际运行容器的应用分发包，不是可直接切换的旧镜像；不能仅恢复旧标签就假定完成回滚。需要回滚时应基于这些分发包重建对应镜像，并协调 7 个服务版本。
- 切换前归档 490 个旧静态资源，保留 3 代；归档脚本清理 26 个不再引用的更早代静态文件。这些不是业务数据；完整当前运行版本另有上述备份，更早代文件不保证可从本次备份恢复。
- 新索引为增量兼容变更，回滚应用通常无需删除；不得为应用回滚直接覆盖已有库的新业务数据。

全平台剩余覆盖项和 Redis 并发/真实集群限制继续保留在开发验收文档中，本次部署不将其标记为完成。

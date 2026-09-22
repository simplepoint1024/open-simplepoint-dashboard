# 组织成员与资源配置清理（2026-09-22）

组织管理改为左侧组织树、右侧本级及所有下级用户。右侧使用递归 SQL 限定租户和组织子树，并在数据库分页、计数和搜索；添加用户只从当前租户成员中选择，已有组织归属会转移至所选组织。成员类型为“内部 / 外部 / 引用”，由系统字典提供并支持中英文。已有成员默认“内部”。

平台表格支持单击行选中和再次单击取消。租户成员操作统一在表格工具栏，以图标和文字呈现；配置角色仍使用底部抽屉。组织机构管理统一更名为组织管理。资源权限显示会解析国际化别名，缺失翻译时显示资源编码或名称，不暴露原始 `i18n:` 键。

独立资源授权中心及其页面、前端模拟、角色目录、影响用户查询、控制器、服务和 DTO 已删除。角色管理的资源配置保留，接口迁至 `/role-resource-configuration/roles/{roleId}`，只通过 `roles.config.resource` 授权。Common 资源清单移除旧页面和操作资源，启动同步会撤销对应旧授权记录。历史文档中的旧入口仅作追溯，不再是可用接口。

部署前运行 `doc/deployment/sql/20260922-organization-members.sql`，为成员关系补充 `user_type` 及组织子树查询索引，并清理旧授权中心独有的翻译记录。迁移是幂等的；建议先做数据库备份。回滚镜像时不应覆盖部署后产生的业务写入。

验证：`pnpm typecheck`、`pnpm i18n:check`、`pnpm build`、完整模块联邦浏览器回归，以及 `TenantMemberQueryHttpTest`、`RoleResourceConfigurationHttpTest`。浏览器接口使用隔离 fixtures；真实登录后的组织成员写入仍需由持有业务账号的人员验收。

已部署至 `simplepoint203`：Common、Host、AI、Auditing、DNA 五个镜像健康，HTTPS 入口正常；部署后浏览器回归加载了这些服务的真实远程前端产物。发布清单、数据库备份和回滚脚本在本地忽略目录 `.simplepoint/deploy203/release-20260922-org-member-access-removal1/`。数据库核对显示旧页面资源及其翻译命名空间已移除，新用户类型词典与索引存在。

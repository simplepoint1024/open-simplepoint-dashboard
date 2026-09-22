# 数据权限实现评估（2026-09-21）

## 结论与范围

当前公共查询链路已有较完整的权限防护，但还不能视为全系统闭环：新增入口能够绕过已有记录的写入归属检查；专用更新接口和轻量 DTO 存在字段权限旁路。这些应先修复，再调整权限模型。

评估对象是当前工作区源码（包含已有未提交修改），不是线上各服务镜像的版本认证。检查了授权上下文构建及缓存、租户和行级过滤、通用 CRUD、字段序列化与查询限制、角色和范围管理，并抽查 AI、组织/租户、存储、Redis、DNA 专用入口。没有逐个接口完成生产身份联调，也没有对业务库执行漏洞验证或修改权限。

## 已确认的缺陷

### 1. 高风险：新增请求带已有 ID，可更新无权修改的记录

- `BaseServiceImpl.create` 校验的是请求中正在创建的对象，没有拒绝已有 ID，也没有加载原记录检查租户/归属；随后调用 `repository.save`。批量新增同样使用 `saveAll`。
- `save` 对带 ID 的实体可执行合并更新。UUID 生成器还明确允许预分配 ID；`@Schema(READ_ONLY)` 不是请求反序列化的写入保护。
- 隔离 H2 实验：SELF 用户不能通过 `findById` 读取同租户另一用户的记录，但将其 ID 放入新增对象后，可以覆盖原记录的业务字段，并把创建人改成自己。使用其他租户记录的 ID，也成功覆盖了该记录的业务字段；`tenantId` 的 `updatable=false` 只保持租户列不变，没有阻止其他列更新。
- HTTP 可达链路示例：`DataScopeController.add` 接收实体并直接调用 `service.create`。因此创建权限与编辑权限的分离可能失效；作用于权限策略实体时，影响更大。实际利用前提包括具有对应新增入口权限、获知目标 ID、且没有其他专用校验拒绝该对象。

依据：[BaseServiceImpl.java](../../simplepoint-core/src/main/java/org/simplepoint/core/base/service/impl/BaseServiceImpl.java)（194、211、322 行）、[DataScopeController.java](../../simplepoint-services/simplepoint-service-common/src/main/java/org/simplepoint/plugin/rbac/core/rest/controller/DataScopeController.java)（81–85 行）、[UuidStringIdentifierGenerator.java](../../simplepoint-core/src/main/java/org/simplepoint/core/base/generator/UuidStringIdentifierGenerator.java)（40 行）。

建议：公共新增入口拒绝调用方指定 ID，区分外部 create 与内部预分配 ID 的 insert。不能只改 UUID 生成器，因为调用流水等内部功能确实需要预分配 ID。新增应使用明确的插入语义，更新必须加载原记录并检查权限；单条和批量保持一致。

### 2. 中风险：专用更新接口绕过字段只读限制

`TenantServiceImpl.updateCurrentTenantProfile` 验证当前租户及所有者/管理员身份后，直接设置 name、description、logo、backgroundImage 并调用仓库更新，没有应用 `FieldPermissionPolicy.writable` 或通用 `modifyById` 的字段恢复逻辑。

隔离单元实验：`Tenant#name=VISIBLE` 时，公共字段策略返回不可写，但当前租户所有者仍能通过该方法修改名称。这不是跨租户越权；是同一实体在不同入口上的字段权限语义不一致。

依据：[TenantServiceImpl.java](../../simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-tenant-implementation/src/main/java/org/simplepoint/plugin/rbac/tenant/service/impl/TenantServiceImpl.java)（158–186 行）、[TenantController.java](../../simplepoint-services/simplepoint-service-common/src/main/java/org/simplepoint/plugin/rbac/tenant/rest/controller/TenantController.java)（127–130 行）。

建议：抽取可供专用命令复用的字段写入校验，结合原始请求中实际出现的字段处理；不要依赖前端只读表单。若所有者确实需要豁免，应在统一策略中声明，使 schema、普通修改和专用修改一致。

### 3. 中风险：选择项 DTO 可以重新暴露实体隐藏字段

- `OrganizationController.tree` 做了显式字段投影，但 `/options` 直接返回 `OrganizationOptionPage`。
- `OrganizationOptionVo` 包含 description 等实体字段，没有 `PermissionResource` / `PermissionFields` 来源映射；`NamedTenantVo` 的 tenantName、tenantLogo 也没有来源映射。
- 隔离 Jackson 3 实验：隐藏 `Organization#description` 后，实体 JSON 不含 description，选择项 DTO 仍输出原文；隐藏 `Tenant#name` 后，`NamedTenantVo.tenantName` 仍输出原文。
- 组织 `/options` 的 keyword 分支直接调用专用搜索，没有复用公共查询字段权限检查。除响应投影外，查询是否能通过隐藏字段推断匹配结果也应一并补验。

依据：[OrganizationController.java](../../simplepoint-services/simplepoint-service-common/src/main/java/org/simplepoint/plugin/rbac/tenant/rest/controller/OrganizationController.java)（69–104 行）、[OrganizationServiceImpl.java](../../simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-tenant-implementation/src/main/java/org/simplepoint/plugin/rbac/tenant/service/impl/OrganizationServiceImpl.java)（123–175、258–284 行）、[OrganizationOptionVo.java](../../simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-tenant-api/src/main/java/org/simplepoint/plugin/rbac/tenant/api/vo/OrganizationOptionVo.java)、[NamedTenantVo.java](../../simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-tenant-api/src/main/java/org/simplepoint/plugin/rbac/tenant/api/vo/NamedTenantVo.java)。

建议：所有包含实体数据的 DTO 声明来源字段，Map 输出执行显式投影。选择器需要最小身份字段时，明确字段不可读后的降级/拒绝行为，不应默认为选择器可以绕开隐藏规则。

## 权限模型与工程上的不合理之处

### 4. 角色的数据范围被合成整个上下文的单一范围

`AuthorizationContextServiceImpl.resolveDataAndFieldScope` 收集所有生效角色的范围；任一范围为 ALL，就把整个上下文设为 ALL。上下文没有按业务资源或操作保存数据范围。

因此，当未指定单角色、多个角色共同生效时，“业务 A 的角色允许全部数据 + 业务 B 的角色只允许本人数据”最终可能让业务 B 也使用 ALL。该实现不会自行取消租户隔离，但无法表达不同业务使用不同数据范围。

这是当前明确采用的全局角色范围设计，不应把角色并集本身都判为漏洞。若系统目标是一般业务数据授权，这个表达能力不足，后台需要明确提示。建议在同一资源/操作内合并角色范围，再与租户边界相交；不要在请求上下文阶段丢失权限来源。

依据：[AuthorizationContextServiceImpl.java](../../simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-core-implementation/src/main/java/org/simplepoint/plugin/rbac/core/service/impl/AuthorizationContextServiceImpl.java)（196、238、360、407 行）。

### 5. 字段策略管理目录只认识 common 服务本地 JPA 实体

`FieldScopeCatalog.entries` 只枚举当前 EntityManager 的 metamodel；`validateResource` 拒绝目录之外的实体。common 服务的该目录没有跨服务聚合步骤。AI、DNA 等独立服务中未进入 common metamodel 的实体，不能通过这条统一配置入口创建字段规则。

因此“各服务支持字段过滤代码”不等于“管理员能够配置全系统字段权限”。当前模型也不能直接为未登记的非 JPA 查询 DTO 提供目录。

依据：[FieldScopeCatalog.java](../../simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-core-implementation/src/main/java/org/simplepoint/plugin/rbac/core/service/impl/FieldScopeCatalog.java)（18、28 行）、[FieldScopeServiceImpl.java](../../simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-core-implementation/src/main/java/org/simplepoint/plugin/rbac/core/service/impl/FieldScopeServiceImpl.java)（49–70 行）、[common build.gradle.kts](../../simplepoint-services/simplepoint-service-common/build.gradle.kts)。

建议：各业务服务发布版本化的资源/字段目录，由管理服务聚合并校验。策略身份使用稳定的业务资源编码；Java 包名变更应有迁移机制。

### 6. 权限完整性过于依赖每个服务正确使用公共方法

租户过滤通过显式 `enableTenantFilter` 启用；行级范围主要附加在自定义分页/计数 Specification 上。普通 JPA 主键读取、派生查询、EntityManager 或直接仓库写入并非自动受相同保护。通用服务的 `findById` 在加载后再次检查租户，这是必要防护，但专用服务需要自行复制或替代这些规则。

系统允许元数据服务通过 `isDataScopeApplicable=false` 退出部门/本人过滤，这对字典、授权元数据等有合理性；问题是例外没有形成可统一检查的边界契约。不能把“使用了 BaseRepository”视为已完成权限检查。上面的新增和专用更新缺陷就是实际例证。

建议：明确区分受当前用户约束的数据访问与受信任的内部访问；为业务查询/命令复用统一策略执行器。将例外登记在领域策略中，并增加自动检查，覆盖直接仓库调用及 DTO 投影。避免简单给所有表叠加 SELF，破坏共享元数据和内部任务。

依据：[BaseRepositoryImpl.java](../../simplepoint-data/simplepoint-data-jpa/src/main/java/org/simplepoint/data/jpa/base/repository/BaseRepositoryImpl.java)（107、278、443 行）、[BaseServiceImpl.java](../../simplepoint-core/src/main/java/org/simplepoint/core/base/service/impl/BaseServiceImpl.java)（712、779 行）。

## 已有防护与验证

已确认值得保留的实现：授权上下文绑定已验证的认证主体；缓存键包含租户/角色及权限版本；通用服务缺少数据范围时拒绝而非放开；分页/count 使用相同范围；用户筛选与服务端强制条件取交集；隐藏/脱敏字段禁止参与公共筛选和排序；Jackson 2/3 共用字段策略。

本次共执行 96 个用例：91 个已有权限相关回归，加 5 个诊断用例，全部运行通过。诊断用例断言的是缺陷实际发生，不能将“通过”理解为权限安全验收通过。

- 现有回归：BaseServiceImpl、FieldPermissionPolicy、FieldScopeJackson3Module、DataScopeDatabase、AuthorizationContextCacheSecurity、AuthorizationContextResolver、AuthorizationContextServiceImpl、DataScopeAspect。
- 新增诊断：已有 ID 新增覆盖其他所有者；已有 ID 新增覆盖其他租户业务字段；专用资料更新忽略只读名称；租户 DTO 暴露隐藏名称；组织 DTO 暴露隐藏描述。
- 数据库验证使用独立内存 H2；资料更新使用 Mockito，序列化使用生产 FieldScopeJackson3Module。未对线上接口执行跨权限写入攻击，也未完成所有服务的 HTTP 组合验证。
- 临时诊断源码保留于 `/tmp/DataPermissionAuditTest.java` 和 `/tmp/TenantFieldPermissionAuditTest.java`；运行日志为 `/tmp/simplepoint-data-permission-audit.log`、`/tmp/simplepoint-data-permission-review-tests.log`、`/tmp/simplepoint-data-permission-projection.log`。临时测试已从模块测试目录移除，避免将断言漏洞存在的诊断误当正常回归。

修复顺序：先封堵新增覆盖，再统一专用写入与 DTO 字段策略；随后完善跨服务字段目录，最后决定数据范围是否升级为资源/操作级。每项修复都应覆盖普通成员、租户管理员、平台管理员，以及“列表不可见但已知 ID”的场景。

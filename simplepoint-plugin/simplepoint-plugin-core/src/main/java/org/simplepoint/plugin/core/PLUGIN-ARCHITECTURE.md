# Simplepoint 插件化架构：原理、功能与实现

本文介绍 Simplepoint 的插件化实现：从类加载隔离、依赖可见、热安装/卸载，到 Spring MVC 动态路由与 Bean 生命周期管理，结合关键代码片段说明设计思路与落地细节。

## 目标与原则

- 每个插件独立类加载器，隔离实现与三方依赖，避免冲突与泄漏
- 支持插件间依赖：被依赖插件先装载，类可见性按顺序委派
- 热安装/热卸载：无需重启，动态注册、撤销 Bean 与路由
- 安全卸载：阻止卸载被其他插件依赖的插件，释放 JAR 句柄
- 与 Spring 生态融合：Bean 初始化、AOP 代理、MVC 路由映射清理

## 功能概览

- 插件安装/卸载/查询
- 依赖声明与校验、拓扑顺序安装（当前实现为运行期校验依赖是否已加载）
- 独立类加载器 + 依赖感知类加载策略
- WebMVC 控制器动态映射注册/注销
- Spring Bean 动态注册为单例（保持 AOP 代理）
- Plugin lifecycle handler 注册 / 回滚 manifest 声明式贡献
- 修复热替换常见问题（旧路由残留、类加载器不一致导致的断言失败）

## 核心设计

### 1) 插件 Manifest

运行时插件必须在包内声明 `plugin.yaml`、`plugin.yml`、`plugin.json` 或 `META-INF/plugin.yaml`。`PluginClassloader` 会读取为 `PluginManifest`，其中 `dependencies` 使用插件 `id`、可选版本约束和是否可选来声明依赖关系：

```yaml
id: org.simplepoint.example.plugin.controller
name: demo-controller
version: "1.0"
dependencies:
  - id: org.simplepoint.example.plugin.service
    version: ">=1.0.0 <2.0.0"
backend:
  packageScan:
    controller: org.simplepoint.example.plugin.controller
  instances: {}
frontend:
  remotes:
    - name: demo
      entry: http://127.0.0.1:8080/demo/mf/mf-manifest.json
resources:
  - code: demo.dashboard
    name: Demo Dashboard
    title: Demo
    type: PAGE
    path: /demo
    component: demo/Dashboard
    children:
      - code: demo.dashboard.view
        name: Demo View
        type: ACTION
```

### 2) 依赖可见的类加载器

自定义 `DependencyAwareUrlClassLoader` 实现“父优先白名单 + 自身优先 + 依赖 + 父加载器”的策略，既避免覆盖 JDK/Spring 等核心包，又允许跨插件 API 可见：

```java
// org.simplepoint.plugin.core.DependencyAwareUrlClassLoader
final class DependencyAwareUrlClassLoader extends URLClassLoader {
  private static final String[] PARENT_FIRST_PREFIXES = {
    "java.", "javax.", "jakarta.", "sun.", "com.sun.",
    "org.slf4j.", "org.apache.logging.", "ch.qos.logback.",
    "org.springframework.", "reactor.", "io.netty."
  };

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      if (isParentFirst(name)) return super.loadClass(name, resolve);
      Class<?> c = findLoadedClass(name);
      if (c != null) return c;
      try { c = findClass(name); if (resolve) resolveClass(c); return c; }
      catch (ClassNotFoundException ignore) { }
      for (ClassLoader dep : dependencies) {
        try { c = dep.loadClass(name); if (resolve) resolveClass(c); return c; }
        catch (ClassNotFoundException ignored) { }
      }
      return super.loadClass(name, resolve);
    }
  }
  // ...
}
```

### 3) 插件管理器：安装、卸载与类加载器图

`PluginClassloader` 负责：
- 使用 `PluginInstallPlanFactory` 生成安装 dry-run read model，预览安装顺序、依赖解析和阻断原因
- 在 `installAll()` 中读取目录内所有 manifest，并交给 `PluginDependencyResolver` 做拓扑排序
- 使用 `PluginCompatibilityVerifier` 校验 manifest 中的运行时版本要求
- 校验已安装依赖和同目录候选依赖是否满足 `dependencies.version`
- 按依赖顺序为插件创建类加载器
- 使用插件类加载器加载类并注册实例
- 维护 `RESOLVED`、`INSTALLED`、`ENABLED`、`DISABLED`、`FAILED` 等运行时状态
- 在 `submit()` 中触发 `PluginLifecycleHandler.installed(plugin)`；任务失败会原地重试，避免后续 lifecycle 越过失败任务
- 卸载时阻止被依赖插件，关闭 URLClassLoader 释放 JAR

关键逻辑摘录：

```java
// org.simplepoint.plugin.core.PluginClassloader
private final Map<String, URLClassLoader> pluginClassLoaders = new ConcurrentHashMap<>();
private final Map<String, List<String>> pluginDependencies = new ConcurrentHashMap<>();
private final PluginDependencyResolver dependencyResolver = new PluginDependencyResolver();

public List<Plugin> installAll(File path) throws Exception {
  List<PluginDescriptor> descriptors = readAllDescriptors(path);
  List<PluginDescriptor> sorted = dependencyResolver.sort(descriptors, installedPluginVersions());
  for (PluginDescriptor descriptor : sorted) install(descriptor);
}

private synchronized Plugin analyzeJar(PluginDescriptor descriptor) throws Exception {
  PluginManifest manifest = descriptor.manifest();
  List<String> deps = resolveDependencyClassLoaders(descriptor);

  // 为当前插件创建依赖可见类加载器
  URLClassLoader pluginCl = new DependencyAwareUrlClassLoader(urls, getParent(), depCls);
  pluginClassLoaders.put(descriptor.id(), pluginCl);
  pluginDependencies.put(descriptor.id(), List.copyOf(deps));

  // 使用插件类加载器加载并注册实例
  merge(classes, registerInstances(descriptor.id(), PluginManifestRuntime.instances(manifest), pluginCl));
  return new Plugin(descriptor.artifact(), manifest, classes);
}

public synchronized void uninstall(String pluginId) {
  // 反向依赖阻止卸载
  List<String> blockers = new ArrayList<>();
  pluginDependencies.forEach((id, ds) -> { if (ds != null && ds.contains(pluginId)) blockers.add(id); });
  if (!blockers.isEmpty()) throw new IllegalStateException("插件 " + pluginId + " 被以下插件依赖: " + blockers);

  // 回滚、移除、关闭类加载器
  storage.save(plugin.withStatus(PluginStatus.DISABLED));
  uninstall(plugin.registered());
  removeQueuedTasks(pluginId);
  storage.remove(pluginId);
  closeClassLoader(pluginId);
  pluginDependencies.remove(pluginId);
}

private Map<String, Set<Plugin.PluginInstance>> registerInstances(
    String pluginId, Map<String, Set<Plugin.PluginInstance>> beansRegister, ClassLoader pluginCl) throws Exception {
  // 用插件类加载器加载类
  pluginInstance.classes(pluginCl.loadClass(name));
  // 延迟执行 handler.handle(pluginInstance) 完成注册
}
```

### 4) Manifest lifecycle 贡献处理

实例 handler 只处理运行时 class，声明式资源通过 `PluginLifecycleHandler` 扩展。RBAC 模块提供的
`PluginResourceContributionHandler` 会把 manifest 中的微前端 remote、资源树写入对应表，并用
`pluginId` 标记归属。卸载插件时先回滚这些声明式资源，再注销 Bean 和 MVC 映射。RBAC 贡献注册失败时，
handler 会在抛回原始异常前按 `pluginId` 显式清理已经写入的资源；配合事务回滚，可避免半注册的
resource 或 remote 残留。

插件 remote 的 `entry` 在数据库中保存 manifest 声明的 canonical URL。`/resources/service-routes` 输出时会根据
插件版本、remote 版本和 artifact SHA-256 追加 `_sp_plugin` / `_sp_v` query，隔离浏览器和 host 的 remote 缓存。

如果运行时插件注册出的 Spring Bean 自身实现了 `PluginLifecycleHandler`，`SpringBeanPluginInstanceHandler` 会把它动态注册进 `PluginsManager`，并在插件卸载回滚时移除。

### 5) Spring Bean 注册为单例（保留代理）

避免用 BeanDefinition 再创建一次实例导致“映射类型与实际 Bean 类型不一致”，直接将插件创建并 `initializeBean` 后的对象注册为单例：

```java
// org.simplepoint.plugin.spring.handle.SpringBeanPluginInstanceHandler
public void registerBean(String beanName, Object bean) {
  unregisterBean(beanName);
  var bf = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
  var dlbf = (DefaultListableBeanFactory) bf;
  Object initialized = applicationContext.getAutowireCapableBeanFactory().initializeBean(bean, beanName);
  dlbf.registerSingleton(beanName, initialized);
}

public <T> T createBean(Class<T> beanClass) {
  return (T) applicationContext.getAutowireCapableBeanFactory()
    .createBean(beanClass, AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR, true);
}
```

### 6) Spring MVC 路由的动态注册/清理

- 注册：插件实例注册后，调用 `RequestMappingHandlerMapping.processCandidateBean(beanName)` 完成路由映射。
- 清理：热卸载/重装时，必须移除所有旧映射（包括不同 `HandlerMapping` 中的条目），且用“bean 名 + beanType 名称”双重匹配来规避类加载器导致的“看似相同、实际不同类型”。

```java
// org.simplepoint.plugin.webmvc.handler.ServletMappingPluginInstanceHandler
public void unregisterMapping(String beanName) {
  ApplicationContext ctx = this.handler.applicationContext();
  Map<String, RequestMappingHandlerMapping> all = ctx.getBeansOfType(RequestMappingHandlerMapping.class);
  if (!all.containsValue(this)) all.put("_self", this);
  all.values().forEach(m -> unregisterFrom(m, beanName));
}

private void unregisterFrom(RequestMappingHandlerMapping mapping, String beanName) {
  List<RequestMappingInfo> toRemove = new ArrayList<>();
  Class<?> currentType = safeUserClassOf(beanName);
  for (var e : mapping.getHandlerMethods().entrySet()) {
    HandlerMethod hm = e.getValue();
    boolean match = (hm.getBean() instanceof String s && beanName.equals(s))
      || hm.getBeanType().getName().equals(beanName)
      || (currentType != null && hm.getBeanType().getName().equals(currentType.getName()));
    if (match) toRemove.add(e.getKey());
  }
  toRemove.forEach(mapping::unregisterMapping);
}

@Override
public void handle(Plugin.PluginInstance instance) {
  this.unregisterMapping(instance.getName()); // 预清理
  this.handler.handle(instance);              // 注册 Bean (singleton)
  super.processCandidateBean(instance.getName()); // 注册路由
}
```

## 热安装/卸载流程

- REST 安装预检查：`pluginsManager.planInstall(tempUri)` -> 返回 `PluginInstallPlan`，不持久化 JAR、不创建类加载器、不写 storage
- REST 上传安装：`pluginsManager.inspect(tempUri)` -> 持久化为 `id-version.jar` -> `pluginsManager.install(uri)` -> `pluginsManager.submit()`
- 直接安装：`pluginsManager.install(uri)` -> `pluginsManager.submit()`
- 目录安装：先读取所有 manifest -> `PluginDependencyResolver` 校验依赖版本并拓扑排序 -> 逐个安装
- 升级：`pluginsManager.upgrade(uri)` -> 卸载旧插件 -> 安装新插件并 `submit()` -> 失败时恢复旧插件
- 禁用：`pluginsManager.disable(pluginId)` -> 校验无活跃下游依赖 -> 回滚声明式贡献、Bean 和 MVC 映射 -> 保留类加载器和插件记录
- 启用：`pluginsManager.enable(pluginId)` -> 校验必需依赖均已启用 -> 重新注册实例与声明式贡献
- 卸载：`pluginsManager.uninstall(pluginId)` -> `pluginsManager.submit()`
- 顺序要求：必需依赖必须已安装或存在于同一目录；候选可选依赖会参与排序；依赖声明中的 `version` 会对已存在依赖和本次候选依赖同时生效；卸载前检查是否被依赖
- 分组要求：`backend.instances` 和 `backend.packageScan` 声明的 group 必须有对应的 `PluginInstanceHandler`；
  未注册 group 会在创建插件类加载器前失败，避免静默跳过实例注册。
- 唯一性要求：同一个插件内所有 backend instance `name` 必须唯一；显式 `instances` 与 `packageScan`
  扫描结果会先合并再校验，避免 submit 阶段出现同名 Bean 或路由覆盖。

`coreVersion`、`frontendSdkVersion` 和 `dependencies.version` 支持精确版本、比较符范围、通配符、`^`、`~` 等表达式。Spring 环境下可通过
`plugin.runtime.core-version` 和 `plugin.runtime.frontend-sdk-version` 配置当前运行时版本。

插件包在读取 descriptor 时会计算 `PluginArtifact`，包含 JAR `size` 和 `sha256`。随后执行
`PluginArtifactVerifier`。Spring 默认提供 `TrustedSha256PluginArtifactVerifier`，可通过
`plugin.trust.sha256` 配置插件 ID 到可信 SHA-256 列表；开启 `plugin.trust.require-known-sha256=true`
后，未配置可信 digest 的插件也会被拒绝。需要证书签名或远端信任中心时，可以额外声明
`PluginArtifactVerifier` Bean，系统会组合执行。

`PluginsManager.registry()` 返回 `PluginRegistryView`，包含插件状态、失败原因、声明依赖、依赖边、被依赖列表、
注册实例数量、插件包 `size` / `sha256`，以及 `uninstallable` / `upgradeable` / `disableable` / `enableable`
可操作性判断。依赖边会返回声明版本约束、实际解析版本和版本是否满足，用于管理页暴露不兼容依赖状态。

`PluginsManager.planInstall(uri)` / `planInstallAll(path)` 和 `POST /plugins/plan` 返回 `PluginInstallPlan`。它会复用
manifest、兼容性、artifact trust、依赖 resolver、backend group 和实例唯一性校验，但错误会进入 `issues`，
用于管理页展示安装前阻断原因。这个流程不持久化上传文件、不创建插件类加载器、不写入 storage，也不会产生
operation audit。

`PluginsManager.operationAudits()` 和 `GET /plugins/operations` 返回最近的插件操作审计记录。默认 recorder 是
有界内存队列；需要持久化或集中日志时，可以声明额外的 `PluginOperationAuditRecorder` Bean。审计记录包含
operation、outcome、插件 ID/版本、artifact 指纹、开始/完成时间、耗时和失败原因。

`PluginsManager.operationTasks()` 和 `GET /plugins/tasks` 返回 `submit()` 阶段的运行时任务快照。任务包含
任务 ID、插件 ID、operation、status、attempts、创建/更新时间和失败原因。任务状态通过 `PluginTaskStore`
写入；默认实现是有界内存结构，生产环境可以替换为数据库、Redis 或消息系统实现，支持重启后的查询、死信排查和人工重试入口。

Spring 环境内置 JDBC 任务存储，默认关闭。存在 `DataSource` 且配置 `plugin.task-store.jdbc.enabled=true` 后，
会注册 `JdbcPluginTaskStore`。可通过 `plugin.task-store.jdbc.initialize-schema=false` 禁用自动建表，
并使用 `plugin.task-store.jdbc.table-name` 指定表名。

`PluginRuntimeCoordinator` 是集群化扩展点。变更类操作会进入 `coordinate(context, callback)` 协调边界，并在完成后
通过 `publish(event)` 发布结果。Spring 环境默认把完成事件转发到 application event bus；多节点部署可以声明自定义
Bean，把这里接到分布式锁、消息队列、Redis Stream 或控制面事件总线。

Spring 环境内置 JDBC 租约锁 coordinator，默认关闭。存在 `DataSource` 且配置
`plugin.runtime-coordinator.jdbc.enabled=true` 后，会把安装、提交、升级、启停和卸载串行化到同一个逻辑锁。
关键参数包括 `table-name`、`lock-name`、`owner-id`、`lease-duration`、`acquire-timeout` 和 `retry-interval`。
如果节点异常退出，其他节点会在租约过期后重新获取锁。

Spring 环境还内置 JDBC 运行时事件日志，默认关闭。存在 `DataSource` 且配置
`plugin.runtime-events.jdbc.enabled=true` 后，本节点完成的插件操作会写入 `sp_plugin_runtime_event`；
relay 会按 `poll-interval` 轮询其他 `origin-id` 写入的事件，并转发到本节点 Spring application event bus。
关键参数包括 `table-name`、`origin-id`、`relay-enabled`、`replay-existing`、`poll-interval` 和 `batch-size`。
默认不回放 relay 启动前已有事件；如果需要把事件表当作完整事件流，可以设置
`plugin.runtime-events.jdbc.replay-existing=true`。

建议启用 `/actuator/mappings` 观测路由变化，验证无陈旧映射残留。

## 依赖声明示例（plugin.yaml 片段）

```yaml
id: org.simplepoint.example.plugin
name: Example Controller
version: "1.0"
dependencies:
  - id: org.simplepoint.commons.api
    version: ">=1.0.0 <2.0.0"
  - id: org.simplepoint.i18n.plugin
    optional: true
backend:
  packageScan:
    controller: org.simplepoint.example.plugin.controller
  instances: {}
```

## 常见问题与修复

- 旧路由残留导致 IllegalStateException（映射方法类型与实际 Bean 类型不一致）
  - 修复：统一在所有 HandlerMapping 上按“bean 名 + beanType 名称”清除旧映射；注册前预清理；Bean 以 singleton 注册、保留代理
- Windows 下 JAR 文件锁定
  - 卸载时调用 `URLClassLoader.close()`，并置空引用，帮助 GC
- 跨插件类找不到
  - 使用 `DependencyAwareUrlClassLoader` 并在 manifest 中声明 `dependencies`；确保按拓扑顺序安装
- 插件卡在半安装状态
  - `submit()` 失败会按 pluginId 回滚已注册实例、清除队列任务、关闭类加载器并移除 storage 记录

## 可扩展方向

- 子优先/命名空间路由的高级隔离策略
- 插件生命周期事件（onInstall、onActivate、onDeactivate、onUninstall）

## 结语

该实现兼顾了“隔离性、可见性与热操作”的平衡，配合 Spring 的 Bean/路由机制，使插件可以安全地动态装载与替换。在你的业务中，只需声明依赖并按照流程调用 install/submit/uninstall，即可获得稳定的插件化体验。

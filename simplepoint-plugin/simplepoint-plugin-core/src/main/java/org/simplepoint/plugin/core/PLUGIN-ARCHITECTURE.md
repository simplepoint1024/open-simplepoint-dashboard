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
- 修复热替换常见问题（旧路由残留、类加载器不一致导致的断言失败）

## 核心设计

### 1) 插件元数据增加依赖

在 PluginMetadata 增加 `dependencies` 字段，声明依赖插件包名，实现装载依赖验证与类可见链路：

```java
// org.simplepoint.plugin.api.Plugin
public static final class PluginMetadata implements Serializable {
  // ...existing fields...
  private java.util.List<String> dependencies;
}
```

建议在 plugin-config.xml 中维护依赖列表，安装时进行校验。

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
- 按依赖顺序为插件创建类加载器
- 使用插件类加载器加载类并注册实例
- 卸载时阻止被依赖插件，关闭 URLClassLoader 释放 JAR

关键逻辑摘录：

```java
// org.simplepoint.plugin.core.PluginClassloader
private final Map<String, URLClassLoader> pluginClassLoaders = new ConcurrentHashMap<>();
private final Map<String, List<String>> pluginDependencies = new ConcurrentHashMap<>();

private synchronized Plugin analyzeJar(URI uri) throws Exception {
  // 读取 metadata
  String pkg = pluginMetadata.getPackageName();
  List<String> deps = pluginMetadata.getDependencies();

  // 解析依赖的类加载器
  List<ClassLoader> depCls = new ArrayList<>();
  if (deps != null) for (String d : deps) {
    URLClassLoader cl = pluginClassLoaders.get(d);
    if (cl == null) throw new IllegalStateException("找不到依赖插件 '" + d + "'");
    depCls.add(cl);
  }

  // 为当前插件创建依赖可见类加载器
  URLClassLoader pluginCl = new DependencyAwareUrlClassLoader(new URL[]{ uri.toURL() }, getParent(), depCls);
  pluginClassLoaders.put(pkg, pluginCl);
  pluginDependencies.put(pkg, deps == null ? List.of() : List.copyOf(deps));

  // 使用插件类加载器加载并注册实例
  merge(classes, registerInstances(pluginMetadata.getInstances(), pluginCl));
  merge(classes, registerInstances(analyzeBeanPackageScan(pluginMetadata.getPackageScan(), jarFile), pluginCl));
  return new Plugin(uri, pluginMetadata, classes);
}

public synchronized void uninstall(String packageName) {
  // 反向依赖阻止卸载
  List<String> blockers = new ArrayList<>();
  pluginDependencies.forEach((pkg, ds) -> { if (ds != null && ds.contains(packageName)) blockers.add(pkg); });
  if (!blockers.isEmpty()) throw new IllegalStateException("插件 " + packageName + " 被以下插件依赖: " + blockers);

  // 回滚、移除、关闭类加载器
  uninstall(plugin.registered());
  storage.remove(packageName);
  URLClassLoader cl = pluginClassLoaders.remove(packageName);
  if (cl != null) cl.close();
  pluginDependencies.remove(packageName);
}

private Map<String, Set<Plugin.PluginInstance>> registerInstances(
    Map<String, Set<Plugin.PluginInstance>> beansRegister, ClassLoader pluginCl) throws Exception {
  // 用插件类加载器加载类
  pluginInstance.classes(pluginCl.loadClass(name));
  // 延迟执行 handler.handle(pluginInstance) 完成注册
}
```

### 4) Spring Bean 注册为单例（保留代理）

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

### 5) Spring MVC 路由的动态注册/清理

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

- 安装：`pluginsManager.install(uri)` -> `pluginsManager.submit()`
- 卸载：`pluginsManager.uninstall(packageName)` -> `pluginsManager.submit()`
- 顺序要求：确保依赖插件先安装；卸载前检查是否被依赖

建议启用 `/actuator/mappings` 观测路由变化，验证无陈旧映射残留。

## 依赖声明示例（plugin-config.xml 片段）

```xml
<plugin>
  <name>Example Controller</name>
  <packageName>org.simplepoint.example.plugin</packageName>
  <dependencies>
    <dependency>org.simplepoint.commons.api</dependency>
    <dependency>org.simplepoint.i18n.plugin</dependency>
  </dependencies>
  <!-- packageScan、instances 等 -->
</plugin>
```

## 常见问题与修复

- 旧路由残留导致 IllegalStateException（映射方法类型与实际 Bean 类型不一致）
  - 修复：统一在所有 HandlerMapping 上按“bean 名 + beanType 名称”清除旧映射；注册前预清理；Bean 以 singleton 注册、保留代理
- Windows 下 JAR 文件锁定
  - 卸载时调用 `URLClassLoader.close()`，并置空引用，帮助 GC
- 跨插件类找不到
  - 使用 `DependencyAwareUrlClassLoader` 并在 metadata 中声明 `dependencies`；确保按拓扑顺序安装

## 可扩展方向

- 自动拓扑排序 `installAll`
- 循环依赖检测与诊断
- 子优先/命名空间路由的高级隔离策略
- 插件生命周期事件（onInstall、onActivate、onDeactivate、onUninstall）

## 结语

该实现兼顾了“隔离性、可见性与热操作”的平衡，配合 Spring 的 Bean/路由机制，使插件可以安全地动态装载与替换。在你的业务中，只需声明依赖并按照流程调用 install/submit/uninstall，即可获得稳定的插件化体验。


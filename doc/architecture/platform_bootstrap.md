# 平台启动贡献（Platform Bootstrap Contribution）

## 1. 目标

平台启动贡献用于替代旧的一次性启动任务模型。它负责执行需要状态记录的模块启动任务，例如默认用户、基础 i18n 数据、内置字典等。

资源、菜单、权限点和模块 i18n 文件不再放进一次性任务里；它们通过各模块的声明文件在启动时幂等同步。

## 2. 设计原则

1. 模块自己声明自己的启动贡献。
2. 执行状态由平台统一记录。
3. 每个贡献都有稳定的模块、类型、键、版本和 checksum。
4. 已应用且版本/checksum 未变化的贡献不会重复执行。
5. 失败默认 fail-fast，避免系统带着不完整基础数据启动。

## 3. 核心结构

贡献 Bean 使用 `PlatformBootstrapContribution` 暴露：

```java
return () -> BootstrapContribution.versioned(
    "rbac-core",
    "system",
    "system-user",
    "1",
    100,
    () -> registerUsers()
);
```

执行状态记录在 `simpoint_platform_contribution`：

| 字段 | 说明 |
| --- | --- |
| `serviceName` | 执行贡献的服务。 |
| `moduleCode` | 贡献所属模块。 |
| `contributionType` | 贡献类型。 |
| `contributionKey` | 稳定贡献键。 |
| `version` | 贡献版本。 |
| `checksum` | 内容指纹。 |
| `status` | `RUNNING`、`APPLIED`、`FAILED`。 |
| `error` | 最近一次失败信息。 |

## 4. 配置

```properties
simplepoint.platform.bootstrap.enabled=true
simplepoint.platform.bootstrap.fail-fast=true
simplepoint.platform.bootstrap.services[0]=common
simplepoint.platform.bootstrap.contributions.system-user.enabled=true
simplepoint.platform.bootstrap.contributions.i18n-base.enabled=true
simplepoint.platform.bootstrap.contributions.i18n-messages.enabled=true
simplepoint.platform.bootstrap.contributions.i18n-bootstrap-v2.enabled=true
simplepoint.platform.bootstrap.contributions.platform-organization-type-dictionary.enabled=true
```

单个贡献可以使用 `contributionKey` 关闭，也可以使用完整键：

```properties
simplepoint.platform.bootstrap.contributions.rbac-core:system:system-user.enabled=false
```

## 5. 与模块声明的关系

平台启动贡献只处理需要执行状态的任务。声明型内容走自己的模块声明目录：

| 类型 | 目录 |
| --- | --- |
| 资源 | `META-INF/simplepoint/resources/*.json` |
| i18n | `META-INF/simplepoint/i18n/{locale}/*.json` |

这保证资源和 i18n 可以按模块拆分，并在每次启动时幂等补齐。

# 快速开始（Quick Start）

> 本文按目录逐项补全，当前已补齐“下一步阅读”中的架构、权限与本地开发文档；Quick Start 正文章节仍在继续完善。

## 目录

- [1. 目标与范围](#1-目标与范围)
- [2. 适用对象](#2-适用对象)
- [3. 环境要求](#3-环境要求)
  - [3.1 基础软件版本](#31-基础软件版本)
  - [3.2 依赖中间件](#32-依赖中间件)
  - [3.3 可选前端环境](#33-可选前端环境)
- [4. 代码获取与目录说明](#4-代码获取与目录说明)
- [5. 最小启动路径](#5-最小启动路径)
  - [5.1 校验后端工程](#51-校验后端工程)
  - [5.2 启动 authorization 服务](#52-启动-authorization-服务)
  - [5.3 启动 common 服务](#53-启动-common-服务)
  - [5.4 启动 host 服务](#54-启动-host-服务)
  - [5.5 启动前端工作区（可选）](#55-启动前端工作区可选)
- [6. 默认访问地址与端口](#6-默认访问地址与端口)
- [7. 初始化数据与默认账号](#7-初始化数据与默认账号)
- [8. 最小验证清单](#8-最小验证清单)
- [9. 常见问题](#9-常见问题)
- [10. 下一步阅读](#10-下一步阅读)

## 1. 目标与范围

本文档的目标，是帮助首次接触 `open-simplepoint-dashboard` 的使用者和开发者，以**最短路径**完成以下事情：

1. 理解本项目的运行形态：它是一个多模块 Gradle 后端工程，配套一个可选的 React 微前端工作区。
2. 准备本地开发所需的基础环境和依赖中间件。
3. 按推荐顺序启动核心服务，并确认系统已经可以访问。
4. 完成最小验证，确认登录、菜单、基础接口或页面链路可用。

本文聚焦的是**“把项目跑起来并完成第一次验证”**，而不是一次性解释所有架构细节。为了降低首次上手成本，文档会优先给出最小可行路径，再在后续章节补充服务职责、配置项、默认入口和常见问题。

当前 quick start 的覆盖范围如下：

| 范围 | 内容 |
| --- | --- |
| 本文覆盖 | 本地开发环境准备、核心服务启动顺序、前端工作区的可选启动方式、默认访问入口、最小验证步骤 |
| 本文暂不覆盖 | 生产部署、高可用架构、完整权限模型、插件开发细节、数据库设计细节、复杂二开流程 |

对于第一次接触项目的读者，建议先把本文走通，再继续阅读系统概览、项目结构、服务拓扑、权限模型和插件架构相关文档。

## 2. 适用对象

- 首次接触本项目的使用者
- 准备本地调试的后端开发者
- 需要联调前后端的开发者
- 准备补全文档与测试的贡献者

## 3. 环境要求

### 3.1 基础软件版本

- JDK 17+
- Gradle Wrapper（使用仓库自带 `./gradlew`）
- Git
- Node.js 20+（如需运行前端工作区）
- pnpm 9（如需运行前端工作区）

### 3.2 依赖中间件

- PostgreSQL（当前默认开发配置见 `doc/deployment/local_development.md`）
- Redis（当前默认开发配置见 `doc/deployment/local_development.md`）
- RabbitMQ（当前默认开发配置见 `doc/deployment/local_development.md`）
- Consul（当前默认开发配置见 `doc/deployment/local_development.md`）
- Vault（当前默认开发配置见 `doc/deployment/local_development.md`）

### 3.3 可选前端环境

- `open-simplepoint-dashboard-react/` 工作区的安装要求
- 本地微前端联调方式
- 仅后端调试时可跳过的内容

## 4. 代码获取与目录说明

- 仓库克隆方式
- 根目录主要模块说明
- 后端与前端仓库关系说明

## 5. 最小启动路径

### 5.1 校验后端工程

- 克隆仓库
- 查看 Gradle 子模块
- 执行基础构建或测试命令

### 5.2 启动 authorization 服务

- 启动命令
- 所需配置
- 启动成功判断方式

### 5.3 启动 common 服务

- 启动命令
- 所需配置
- 启动成功判断方式

### 5.4 启动 host 服务

- 启动命令
- 网关职责说明
- 启动成功判断方式

### 5.5 启动前端工作区（可选）

- 安装依赖
- 启动 host / common / 其他 remote 的开发命令
- 与后端联调时的注意事项

## 6. 默认访问地址与端口

- Host UI 地址
- Authorization 服务地址
- Consul 地址
- Vault 地址
- 其他关键服务端口

## 7. 初始化数据与默认账号

- 是否包含初始化数据
- 初始化数据来源
- 默认租户 / 默认账号 / 默认密码（当前默认开发账号见 `doc/deployment/local_development.md`）
- 首次登录后的建议操作

## 8. 最小验证清单

- 页面是否可访问
- 登录是否成功
- 菜单是否正常加载
- 基础接口是否可调用
- `/schema` 是否可返回预期结构

## 9. 常见问题

- 中间件未启动导致的失败
- 配置中心或密钥服务连接失败
- 前端资源或模块联邦加载失败
- 权限、租户、上下文初始化异常
- 详见：`doc/troubleshooting/common_issues.md`

## 10. 下一步阅读

- `doc/architecture/system_overview.md`
- `doc/architecture/project_structure_diagram.md`
- `doc/architecture/service_topology.md`
- `doc/architecture/plugin_architecture.md`
- `doc/permission/permission_model.md`
- `doc/deployment/local_development.md`
- `doc/permission/authorization_context.md`
- `doc/design/authorization_flow.md`
- `doc/api/schema_api.md`
- `doc/api/api_conventions.md`
- `doc/troubleshooting/common_issues.md`

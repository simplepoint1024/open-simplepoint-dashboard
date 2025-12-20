# 📘 项目文档（doc/）

本目录用于存放后台系统的所有设计文档、架构文档、数据库文档、权限模型文档、API 文档等内容。  
文档结构遵循企业级 SaaS / IAM 系统的最佳实践，便于团队协作、系统维护与长期演进。

---

## 📂 目录结构说明
```
doc/
├── architecture/          # 系统架构设计文档
│   ├── system_overview.md  # 系统概览
│   ├── component_design.md  # 组件设计说明
│   └── deployment_diagram.md # 部署架构图
├── design_patterns/      # 设计模式与最佳实践
│   ├── singleton_pattern.md # 单例模式
│   ├── factory_pattern.md   # 工厂模式
│   └── repository_pattern.md # 仓库模式
├── database/              # 数据库设计文档
│   ├── er_diagram.md       # 实体关系图
│   ├── schema_definition.md # 数据库模式定义
│   └── migration_strategy.md # 数据库迁移策略
├── api/                   # API 文档
│   ├── rest_api.md         # REST API 说明
│   ├── graphql_api.md      # GraphQL API 说明
│   └── api_versioning.md    # API 版本管理策略
├── permissions/           # 权限模型文档
│   ├── role_definitions.md  # 角色定义
│   ├── access_control.md    # 访问控制策略
│   └── permission_matrix.md # 权限矩阵
├── resource_model/       # 资源模型文档
│   ├── resource_definitions.md # 资源定义
│   ├── resource_hierarchy.md   # 资源层级结构
│   └── resource_audit.md       # 资源审计说明
├── menu_structure/       # 菜单体系文档
│   ├── menu_hierarchy.md      # 菜单层级结构
│   ├── menu_permission_mapping.md # 菜单与权限映射
│   └── routing_rules.md        # 路由规则说明
├── workflow/             # 业务流程文档
│   ├── user_registration.md    # 用户注册流程
│   ├── login_process.md        # 登录流程
│   └── approval_workflow.md    # 审批流程设计
├── deployment/           # 部署文档
│   ├── docker_deployment.md    # Docker 部署说明
│   ├── k8s_deployment.md       # Kubernetes 部署说明
│   └── ci_cd_pipeline.md       # CI/CD 流程说明
├── config/               # 配置文档
│   ├── environment_variables.md # 环境变量说明
│   ├── logging_configuration.md  # 日志配置说明
│   └── security_configuration.md # 安全配置说明
├── troubleshooting/      # 故障排查文档
│   ├── common_issues.md       # 常见问题与解决方案
│   ├── performance_issues.md   # 性能问题排查指南
│   └── permission_issues.md    # 权限问题排查指南
├── changelog/            # 版本变更记录
│   ├── v1.0.0.md              # 版本 1.0.0 变更记录
└── README.md              # 本文档说明

```


---

## 📐 各目录用途

### **architecture/**
存放系统整体架构相关文档，包括：
- 系统架构图
- 模块划分
- 服务依赖关系
- 部署架构（单机 / 分布式 / 多租户）
- 时序图、组件图

适合新成员快速理解系统全貌。

---

### **design_patterns/**
系统设计文档，包括：
- 领域模型（DDD）
- 核心模块设计
- 登录/认证设计
- 审计设计
- 关键业务设计
- 设计模式说明
- 扩展性设计
- 性能优化设计
- 安全设计
- 缓存设计
- 异步处理设计
- 日志设计
- 错误处理设计

适合深入理解系统内部机制。

---

### **api/**
接口相关文档，包括：
- OpenAPI/Swagger
- REST API 说明
- 请求/响应示例
- 错误码说明

适合前后端联调、第三方对接。

---

### **database/**
数据库相关文档，包括：
- ER 图（Mermaid）
- 表结构说明
- DDL 文件
- 数据库迁移记录

适合 DBA、后端开发、数据分析人员。

---

### **permission/**
权限体系文档（重点），包括：
- 权限模型（RBAC + PBAC + ABAC）
- 权限点设计规范
- 权限点与资源绑定规则
- 数据权限（ABAC）表达式
- 角色继承模型

适合理解系统的安全模型。

---

### **resource/**
资源模型文档，包括：
- API 资源
- 页面资源
- 实体资源
- 字段资源
- 资源属性（owner、service、tags）
- 资源审计

适合权限点与资源映射、审计、可视化。

---

### **menu/**
菜单体系文档，包括：
- 菜单树结构
- 菜单与权限点绑定
- 路由规则
- 菜单类型（menu/page/button）

适合前端开发、权限可视化。

---

### **workflow/**
业务流程文档，包括：
- BPMN 流程图
- 审批流
- 登录流程
- 用户注册流程

适合业务人员、开发人员理解业务逻辑。

---

### **deployment/**
部署相关文档，包括：
- Docker 部署
- Kubernetes 部署
- CI/CD 流程
- 环境变量说明

适合运维、DevOps。

---

### **config/**
配置文档，包括：
- 环境变量
- 日志配置
- 安全配置
- 第三方服务配置

适合开发、运维。

---

### **troubleshooting/**
故障排查文档，包括：
- 常见错误
- 日志说明
- 性能问题排查
- 权限问题排查

适合快速定位问题。

---

### **changelog/**
版本变更记录，包括：
- 新功能
- 修复内容
- 兼容性变更

适合版本管理与发布说明。

---

## 📚 文档阅读顺序（推荐）

1. **architecture/** —— 了解系统全貌
2. **design/** —— 理解系统设计
3. **permission/** —— 理解权限体系（本系统核心）
4. **resource/** —— 理解资源模型
5. **menu/** —— 理解菜单体系
6. **db/** —— 理解数据库结构
7. **api/** —— 使用接口
8. **deployment/** —— 部署系统

---

## 🧩 文档规范

- 所有图表优先使用 **Mermaid**
- 所有接口文档使用 **OpenAPI**
- 所有设计文档使用 Markdown
- 所有流程图使用 BPMN 或 Mermaid

---

## 📬 贡献文档

提交文档前请确保：

- 文档结构符合本 README
- 文件命名清晰
- 图表可渲染
- 内容准确、可复现

---

如果你愿意，我还能继续帮你：

### ✔ 生成每个子目录的 README 模板
### ✔ 自动生成权限模型文档
### ✔ 自动生成 ER 图（Mermaid）
### ✔ 生成 API 文档模板

你想让我继续生成子目录的 README 模板吗？
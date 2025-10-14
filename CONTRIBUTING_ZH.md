---

## 贡献指南

感谢您对`simplepoint-main`项目的关注与贡献请遵循以下指南，以确保顺畅的协作

### 环境要求

- Java（JDK 17或更高版本）
- Kotlin
- Gradle
- Node.js（用于JavaScript/TypeScript）

### 开发环境搭建

1\. 克隆仓库：

```shell
git clone git@github.com:simplepoint1024/simplepoint-main.git
cd simplepoint-main
```

2\. 使用Gradle构建项目：

```shell
./gradlew build
```

3\. 运行Spring Boot应用：

```shell
./gradlew bootRun
```

### 分支策略

- Fork仓库，并基于`master`分支创建您的分支
- 分支命名清晰，例如：`feature/功能名称` 或 `fix/问题描述`

### 提交信息规范

使用清晰简洁的提交信息：

```
类型(范围): 简短描述

示例：
feat(auth): 添加JWT认证支持
fix(ui): 修复按钮对齐问题
```

### Pull Request提交

- 向`master`分支提交Pull Request
- 清晰描述您的修改，并关联相关Issue
- 确保所有测试通过，代码格式统一

### 代码风格

- 遵循标准的Java/Kotlin代码规范
- JavaScript/TypeScript代码使用ESLint/Prettier格式化
- 保持代码整洁、易读、易维护

### 测试

- 为新功能编写单元测试和集成测试
- 提交PR前确保现有测试通过：

```shell
./gradlew test
```

### 问题反馈

- 清晰描述问题、复现步骤及预期行为
- 提供相关日志、截图或代码片段

感谢您的贡献！
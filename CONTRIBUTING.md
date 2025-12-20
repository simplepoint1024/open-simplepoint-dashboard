# Contributing Guidelines

感谢关注和贡献 `open-simplepoint-dashboard`。请按以下规范协作。

## 准备工作
- JDK 17+（项目基于 Spring Boot）。
- Gradle（使用仓库自带 `./gradlew`）。
- Node.js（如需前端/文档构建）。
- 可选：Docker / Docker Compose（本地依赖编排）。

## 环境与构建
- 克隆后运行：
```shell
git clone <repo>
cd open-simplepoint-dashboard
./gradlew projects
```
- 全量构建与测试：
```shell
./gradlew build
./gradlew test
```
- 示例运行（如 AMQP RPC Provider）：
```shell
./gradlew :simplepoint-examples:simplepoint-amqprpc-examples:simplepoint-amqprpc-example-provider:bootRun
```

## 分支与提交流程
- 分支命名：`feature/<topic>`、`fix/<topic>`、`chore/<topic>` 等。
- 基线分支：默认基于 `master`（如有发布分支请遵循发布节奏）。
- 提交信息建议遵循约定式格式：`type(scope): message`
  - 常用 type：`feat`/`fix`/`chore`/`docs`/`test`/`refactor`。

## 代码风格与质量
- Java/Kotlin：遵循 Checkstyle（`checkstyle/google_checks.xml`），IDE 保持 import 有序、无未使用代码。
- 包结构：`org.simplepoint...`，与模块分层一致（API → Boot/Core → Data → Plugin/Cloud/Security → Services/Examples）。
- 配置与环境：优先使用 `SIMPLEPOINT_*` 或 `primitive.*` 前缀变量，遵循 `SimpleEnvironmentPostProcessor` 规则。
- 提交前执行：
```shell
./gradlew test
# 若项目定义了 check 任务
./gradlew check
```

## Pull Request 要求
- 说明变更目的、影响范围，并关联 Issue（如有）。
- 保持变更最小、可回滚；必要时附带迁移/配置说明。
- 新功能/重要修改需补充测试或示例。
- CI 通过且无 Checkstyle/格式化问题。

## 问题反馈
- 提供复现步骤、期望结果、实际结果；附日志/截图/环境信息。
- 安全问题请通过私密渠道报告，避免在公共 Issue 中披露。

## 协议
- 贡献默认遵循仓库开源协议（Apache-2.0）。提交即表示同意按该协议许可贡献内容。

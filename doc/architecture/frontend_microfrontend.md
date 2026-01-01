# 前端微前端与模块联邦指引

本文说明本仓库的前端形态：微前端 + 模块联邦，每个后端微服务对应一个前端子应用，打包产物需放入该微服务的 `resources/static/` 目录。

## 1. 架构原则
- 一个后端微服务 ≈ 一个前端子应用，边界一致，便于自治发布与回滚。
- 模块联邦（Module Federation）作为前端运行时组装方式：
  - 主应用（容器）负责路由聚合、权限与菜单装配。
  - 子应用暴露页面/组件入口（remote entry），按需动态加载。
- 部署耦合：子应用打包后与对应微服务一起发布，将静态文件放入该服务的 `src/main/resources/static/` 下，便于统一入口和鉴权。

## 2. 开发流程（子应用）
1) 在对应微服务目录下创建/维护前端工程（建议放置 `frontend/` 或单独仓库引入）。
2) 配置 Module Federation：
   - `name`：与微服务/子应用名称对应。
   - `filename`: 通常 `remoteEntry.js`。
   - `exposes`：对外暴露的页面/模块。
   - `remotes`：需要消费的其他子应用。
3) 本地开发：
   - 启动子应用前端 Dev Server，确认暴露接口与路由。
   - 后端微服务可通过代理或本地接口模拟。
4) 打包：
   - 运行前端构建命令（如 `npm run build`）。
   - 将生成的静态文件（含 `remoteEntry.js`、静态资源）复制到该微服务的 `src/main/resources/static/`。

## 3. 部署与运行
- 打包后的静态文件随微服务的 Spring Boot 可执行包（jar）一起发布。
- Nginx/Ingress 只需转发到微服务 HTTP 端口，静态文件由 Spring Boot 内置静态资源机制提供。
- 版本管理：推荐在静态文件名中加入 hash 或通过构建产物目录隔离（如 `static/mf/<app>/<hash>/`）以便回滚与缓存控制。

## 4. 约定与命名
- 子应用命名：与微服务名保持一致（例如服务 `user-service` → 子应用 `user-frontend`）。
- 路由前缀：为避免冲突，子应用路由应以服务域前缀开头（如 `/user/**`）。
- 资源目录：`src/main/resources/static/` 下为子应用独占目录（如 `static/user/`）。
- 远程入口：`remoteEntry.js` 路径应固定，供主应用动态加载。

## 5. 主应用集成要点
- 主容器应用需配置 Module Federation `remotes` 指向各子应用的 `remoteEntry.js` URL。
- 菜单/权限：结合后端权限模型，在主容器根据用户权限决定加载哪些子应用或页面。
- 运行时降级：当某个子应用不可用时，应提供降级页或隐藏入口。

## 6. 本地调试建议
- 前端子应用使用本地端口（如 4200/3000），主容器通过跨域或代理方式加载。
- 若需与后端联调，确保后端微服务允许来自前端端口的 CORS，或通过 API Gateway/代理统一入口。

## 7. 交付物检查清单
- `remoteEntry.js` 可被主应用成功加载。
- 静态资源已复制到对应微服务的 `src/main/resources/static/` 并随 jar 发布。
- 路由与资源前缀无冲突，缓存策略（hash/版本目录）已设置。
- 更新相关文档/README，说明子应用位置与构建命令。

## 8. 关联文档
- 系统概览：`doc/architecture/system_overview.md`
- 组件设计：`doc/architecture/component_design.md`
- 项目结构：`doc/architecture/project_structure_diagram.md`
- 开发环境与规范：`doc/architecture/dev_environment.md`
- 部署架构：`doc/architecture/deployment_diagram.md`


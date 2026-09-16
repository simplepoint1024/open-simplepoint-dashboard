## 平台设施基本功能要求
1.前端新增模块simplepoint-ops,后端新增simplepoint-service-ops服务，以及相关的plugins
2.新增菜单平台管理/云设施/Registry 配置管理 包含功能
  2.1.支持 Docker Registry、Harbor、GHCR 等 OCI Distribution 兼容仓库
  2.2.地址、TLS CA、命名空间、只读/可写模式；
  2.3.连通性和能力检测；
  2.4.证加密保存，绝不能下发到浏览器。

# DNA / APP / Workflow 真实链路验证

`form-workflow-e2e.mjs` 通过实际 HTTPS 接口创建隔离的演示应用，完成：

- 客户档案既有记录变更、版本快照、乐观锁冲突拒绝和审批完成。
- 巡检风险表单发布、显式字段映射、精确金额条件分支与审批完成。
- 每一步核对真实返回值；任一步失败返回非零退出码。

环境必须已部署三个引擎及相应数据库迁移，验证用户需要设计、发布、运行页和流程权限。
管理员可验证完整链路；普通用户的资源授权与权限撤销应另行验证。
本地反向代理需要固定连接 IP 时可设置 `SIMPLEPOINT_VERIFY_CONNECT_ADDRESS`；TLS 主机名与证书验证仍使用正式域名。

脚本不会绕过身份认证，也不会直接写控制库、业务库或流程引擎表。

使用 Node.js 24 或以上。通过当前进程的环境传入有效 BFF 登录会话 Cookie（当前平台网关）或访问令牌（接受 Bearer 的入口）、租户和授权上下文，勿将凭据提交到仓库：

```bash
# 预先安全设置 SIMPLEPOINT_VERIFY_ORIGIN、SIMPLEPOINT_VERIFY_COOKIE（或 TOKEN）、
# SIMPLEPOINT_VERIFY_TENANT_ID、SIMPLEPOINT_VERIFY_CONTEXT_ID。
# 私有 CA 使用标准 NODE_EXTRA_CA_CERTS，禁止关闭 TLS 校验。
node scripts/verification/form-workflow-e2e.mjs /path/to/acceptance.json
```

每次运行创建新的带时间标记的验证应用，不覆盖已有应用或记录。输出文件权限为 0600，
包含应用、表单、页面、提交与流程身份及断言结果，不保存 Cookie 或令牌。
保留样例用于界面复核，后续可按输出身份在平台中清理。该脚本覆盖业务场景，
不代替仓库测试、浏览器验证、数据库核对和故障恢复演练。

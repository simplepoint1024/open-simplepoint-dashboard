# SimpleTable 改造计划

## 目标

围绕 `libs/components/SimpleTable/index.tsx` 做渐进式改造，目标是把当前“功能集中型组件”逐步收敛为“边界清晰、可配置、可扩展”的表格内核，同时确保现有页面平滑迁移。

## 当前现状

`SimpleTable` 目前同时承担了以下职责：

- i18n 命名空间加载
- schema 请求与 schema 二次加工
- 分页数据请求
- tenant/context 监听
- 抽屉表单状态管理
- 默认 CRUD 提交/删除行为
- 页面按钮与自定义事件装配

这让它开箱即用，但也带来了几个问题：

- 状态耦合较重，局部优化容易牵一发动全身
- schema / page / i18n / mutation 的刷新策略不够细
- 错误态和 loading 态粒度偏粗
- 页面级扩展点逐渐增多，但 contract 还不够明确

## 分阶段方案

### 第 1 步：收敛配置边界与刷新策略

**目标**
- 明确哪些行为是 `SimpleTable` 默认内建的，哪些行为应交给页面配置
- 把 mutation 后的刷新行为从“固定 page+schema 全刷”改成“可配置”

**实施项**
- 在 `SimpleTableProps` 中增加刷新策略配置
- 提炼统一刷新辅助函数，避免删除/提交逻辑重复拼装 `refetchPage/refetchSchema`
- 保持默认行为向后兼容
- 在低风险页面试点关闭不必要的 schema 刷新

**验收标准**
- 不改页面时，现有行为保持不变
- 页面可以单独控制 submit/delete 后是否刷新 schema
- 受影响页面类型检查与构建通过

**风险**
- 个别页面可能确实依赖 schema 刷新来更新按钮或字段定义
- 因此默认值必须兼容旧行为，再按页面逐步 opt-out

---

### 第 2 步：拆分 loading / error 状态层次

**目标**
- 区分首屏加载、表格翻页刷新、表单提交、schema 错误等状态

**实施项**
- 拆出 `bootLoading`、`tableLoading`、`submitLoading`
- 首屏使用骨架，局部刷新使用表格 loading，提交使用抽屉内 loading
- 为 schema/page 错误增加页面内错误态与重试入口

**验收标准**
- 翻页或筛选时不再整页骨架屏
- 抽屉提交时不影响整页交互
- schema/page 请求失败有明确错误反馈

---

### 第 3 步：抽离状态编排层

**目标**
- 把 `SimpleTable` 从“大而全组件”拆成“状态容器 + 展示层”

**实施项**
- 抽离 `useSimpleTableController` 或类似 hook
- 承接 schema/page/filter/tenant/context/mutation 状态
- `SimpleTable` 主体只保留渲染与事件装配

**验收标准**
- `index.tsx` 主体明显变薄
- 页面行为不变
- controller 可单测或独立复用

---

### 第 4 步：增强表单与列扩展点

**目标**
- 让 schema 驱动保留默认优势，同时允许页面更细粒度覆写

**实施项**
- 增加列覆写能力，如 `columnOverrides`
- 增加 hooks，如 `beforeSubmit` / `afterSubmit`
- 进一步明确 `formSchemaTransform` / `initialValues` / `onSubmit` 的 contract

**验收标准**
- 页面无需完全重写 `SimpleTable`，也能对列与表单局部扩展
- 表单行为可插拔而不是继续堆 if/else

---

### 第 5 步：业务页面分批迁移

**建议顺序**
1. `system/Permission`
2. `system/Role`
3. `platform/Organization`
4. `platform/Tenant`
5. `system/User`

**原因**
- 前两者结构最标准，最适合试点
- `Tenant` / `User` 有更多受控抽屉和额外配置逻辑，适合在 contract 稳定后迁移

## 本轮准备先做的内容

当前已完成：

### 已完成第 1 步
- 为 `SimpleTable` 增加 mutation 后刷新策略配置
- 提炼统一刷新函数
- 在 `system/Permission` 与 `system/Role` 这两个低风险页面试点关闭 schema 刷新

### 已完成第 2 步
- 拆出 `bootLoading`、`tableLoading`、`submitLoading`
- 首屏使用骨架，局部刷新使用表格 loading，提交使用抽屉内 loading
- 为 schema/page 错误增加页面内错误态与重试入口

### 已完成第 3 步
- 新增 `useSimpleTableController`
- 将 schema/page/filter/tenant/context/mutation 状态迁入 controller
- `SimpleTable/index.tsx` 仅保留渲染与事件装配

### 已完成第 4 步
- 为 `SimpleTable` 增加列覆写能力（`columnOverrides`）
- 增加表单提交流程 hooks（`beforeSubmit` / `afterSubmit`）
- 明确 `onSubmit` 与默认 CRUD 流程的扩展 contract
- 在 `system/Permission` 与 `system/Role` 页面完成低风险试点

## 下一步准备执行的内容

下一轮进入 **第 5 步**：

1. 将新 contract 分批迁移到更多业务页面
2. 优先迁移 `platform/Organization`、`platform/Tenant`、`system/User`
3. 识别哪些页面适合使用 `columnOverrides`，哪些页面适合使用提交 hooks
4. 完成迁移后的回归验证与必要的文档补充

## 回滚策略

- 所有新增刷新策略配置均保留兼容默认值
- 如果试点页面出现异常，可仅回退页面侧配置，不需要回滚底层能力


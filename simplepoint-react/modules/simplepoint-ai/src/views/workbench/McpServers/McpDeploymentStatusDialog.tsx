import TableActions from "@simplepoint/components/Table/Actions";
import { canEditPool } from "./Runtime/rowPolicy";
import DataTable, {type QuerySchema, type TableQueryState} from "@simplepoint/components/DataTable";
import {transportFilters} from '@simplepoint/components/Table/query';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import { del, get, post } from "@simplepoint/shared/api/methods";
import { useI18n } from "@simplepoint/shared/hooks/useI18n";
import { usePagePolling } from "@simplepoint/shared/page/usePagePolling";
import {usePageRetention} from "@simplepoint/shared/page/hooks";
import {useOperationScope} from "./Runtime/useOperationScope";
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {deploymentWorkloadFilters, terminalWorkloadStatuses} from './Runtime/deploymentQueries';
import {
  Alert,
  App,
  Descriptions,
  Modal,
  Space,
  Steps,
  Tag,
  Typography,
} from "antd";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { RuntimePool, RuntimeWorkload } from "./Runtime/types";
import { runtimeErrorLabel, runtimeStatusLabel } from "./Runtime/runtimeLabels";
import {
  formatBytes,
  formatCpu,
  formatDateTime,
  resolveErrorMessage,
  statusColor,
} from "./Runtime/utils";
import { mcpServerErrorLabel } from "./labels";

const { Paragraph, Text } = Typography;

type ManagedMcpServer = {
  id: string;
  name?: string;
  code?: string;
  status?: string;
  protocolVersion?: string;
  remoteServerName?: string;
  remoteServerVersion?: string;
  activeSnapshotId?: string;
  lastError?: string;
};

type McpTool = {
  name: string;
  title?: string;
  description?: string;
};

type DiscoveryResult = {
  protocolVersion?: string;
  serverName?: string;
  serverVersion?: string;
  tools?: McpTool[];
};

type McpDeploymentStatusDialogProps = {
  canManage: boolean;
  canDiscover: boolean;
  open: boolean;
  server: ManagedMcpServer;
  pool: RuntimePool;
  poolsUrl: string;
  workloadsUrl: string;
  serversUrl: string;
  autoDiscover?: boolean;
  onClose: () => void;
  onEdit: (pool: RuntimePool) => void;
  onChanged: () => void;
  onOpenCapabilities?: (serverId: string) => void;
  onDeleted: () => void;
};

const terminalStatuses = new Set<string>(terminalWorkloadStatuses);

const McpDeploymentStatusDialog = ({
  open,
  server,
  pool,
  poolsUrl,
  workloadsUrl,
  serversUrl,
  autoDiscover = false,
  canManage,
  canDiscover,
  onClose,
  onEdit,
  onChanged,
  onOpenCapabilities,
  onDeleted,
}: McpDeploymentStatusDialogProps) => {
  const { t } = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope();
  const [confirmation, setConfirmation] = useState<"redeploy" | "disable" | "delete">();
  const [loadError, setLoadError] = useState("");
  const [loaded, setLoaded] = useState(false);
  const readFlight = useRef<Promise<void> | undefined>(undefined);
  const readKey = useRef<string | undefined>(undefined);
  const readRequest = useRef<AbortController | undefined>(undefined);
  const selectedRef = useRef(operation.selected); selectedRef.current = operation.selected;
  usePageRetention(false, open);
  const [currentServer, setCurrentServer] = useState(server);
  const [currentPool, setCurrentPool] = useState(pool);
  const [workloads, setWorkloads] = useState<RuntimeWorkload[]>([]);
  const [workloadPage, setWorkloadPage] = useState(0);
  const [workloadQuery, setWorkloadQuery] = useState<TableQueryState>({filters: {}});
  const workloadSchema = useSchema(workloadsUrl, {enabled: open && operation.selected, retry: false});
  const querySchema = workloadSchema.data?.schema?.['x-query'] ? workloadSchema.data.schema as unknown as QuerySchema : undefined;
  const schemaFailed = !!workloadSchema.error || !!workloadSchema.data && !querySchema;
  const [workloadTotal, setWorkloadTotal] = useState(0);
  const [activeCount, setActiveCount] = useState<number>();
  const [firstActive, setFirstActive] = useState<RuntimeWorkload>();
  const viewKey = JSON.stringify([pool.id, server.id, workloadPage, workloadQuery]);
  const currentView = useRef(viewKey); currentView.current = viewKey;
  const [discovery, setDiscovery] = useState<DiscoveryResult>();
  const [refreshing, setRefreshing] = useState(false);
  const [operating, setOperating] = useState(false);
  const [discovering, setDiscovering] = useState(false);
  const [discoverAfterReady, setDiscoverAfterReady] = useState(autoDiscover);
  const discoveryAttempted = useRef(false);
  const operatingRef = useRef(false);
  const discoveringRef = useRef(false);

  const refresh = useCallback(async (quiet = false): Promise<void> => {
    if (!open || !selectedRef.current || !operation.current() || !querySchema || workloadSchema.error) return;
    if (readFlight.current) {
      if (!quiet) setRefreshing(true);
      if (readKey.current === viewKey) return readFlight.current;
      readRequest.current?.abort(); await readFlight.current;
      if (!open || !selectedRef.current || !operation.current() || currentView.current !== viewKey) return;
    }
    const request = new AbortController(); readRequest.current = request;
    readKey.current = viewKey;
    if (!quiet) setRefreshing(true);
    const run = (async () => {
      try {
        const [nextPool, nextWorkloads, nextServer, nextActive] = await Promise.all([
          get<RuntimePool>(poolsUrl + "/" + encodeURIComponent(pool.id), {}, {signal: request.signal}),
          get<RawPage<RuntimeWorkload>>(workloadsUrl, {page: workloadPage, size: 20,
            ...deploymentWorkloadFilters(pool.id, false, transportFilters(workloadQuery.filters)),
            ...(workloadQuery.sort ? {sort: workloadQuery.sort} : querySchema['x-query'].fields.createdAt ? {sort: 'createdAt,desc'} : {}),
          }, {signal: request.signal}),
          get<ManagedMcpServer>(serversUrl + "/" + encodeURIComponent(server.id), {}, {signal: request.signal}),
          get<RawPage<RuntimeWorkload>>(workloadsUrl, {page: 0, size: 1, ...deploymentWorkloadFilters(pool.id, true)}, {signal: request.signal}),
        ]);
        if (!operation.current(request.signal) || currentView.current !== viewKey) return;
        const page = normalizePage(nextWorkloads), active = normalizePage(nextActive);
        setCurrentPool(nextPool); setWorkloads(page.content); setCurrentServer(nextServer);
        setWorkloadTotal(page.page.totalElements); setActiveCount(active.page.totalElements); setFirstActive(active.content[0]);
        if (workloadPage > 0 && workloadPage >= page.page.totalPages) setWorkloadPage(Math.max(0, page.page.totalPages - 1));
        setLoadError(""); setLoaded(true);
      } catch (error) {
        if (operation.current(request.signal)) setLoadError(resolveErrorMessage(error,
          t("ai.mcp.deployment.error.load", "托管 MCP 部署状态加载失败")));
      } finally {
        if (readRequest.current === request) {readRequest.current = undefined; readFlight.current = undefined;}
        if (operation.current()) setRefreshing(false);
      }
    })();
    readFlight.current = run; return run;
  }, [open, pool.id, poolsUrl, server.id, serversUrl, t, workloadsUrl, workloadPage, workloadQuery, querySchema, workloadSchema.error, viewKey, operation.current]);
  useEffect(() => {
    if (!open || !operation.selected) readRequest.current?.abort();
    return () => {readRequest.current?.abort();};
  }, [open, operation.selected]);

  useEffect(() => {
    if (!open) return;
    setCurrentServer(server);
    setCurrentPool(pool);
    setWorkloads([]); setLoaded(false); setLoadError(""); setConfirmation(undefined);
    setDiscovery(undefined);
    setDiscoverAfterReady(autoDiscover);
    discoveryAttempted.current = false;
  }, [autoDiscover, open, pool, server]);
  useEffect(() => {if (open && operation.selected) void refresh();}, [open, operation.selected, refresh]);
  useEffect(() => {setWorkloadQuery({filters: {}}); setWorkloadPage(0);}, [pool.id, server.id]);
  useEffect(() => {if (schemaFailed) setLoaded(false);}, [schemaFailed]);

  const pollStatus = useCallback(() => refresh(true), [refresh]);
  usePagePolling(pollStatus, open && !operation.busy && !confirmation, 2500);

  const discover = useCallback(async () => {
    if (!canDiscover || !open || discoveringRef.current || operatingRef.current || !loaded || loadError || confirmation) return;
    const request = operation.begin(); if (!request) return;
    readRequest.current?.abort();
    discoveringRef.current = true;
    discoveryAttempted.current = true;
    setDiscovering(true);
    try {
      const result = await post<DiscoveryResult>(
        `${serversUrl}/${server.id}/discover`,
        {}, {signal: request.signal},
      );
      if (!operation.current(request.signal)) return;
      setDiscovery(result);
      setDiscoverAfterReady(false);
      message.success(
        t(
          "ai.mcp.deployment.success.discover",
          "MCP 初始化完成，工具能力已经同步",
        ),
      );
      await refresh(true);
      onChanged();
    } catch (error) {
      if (!operation.current(request.signal)) return;
      message.error(
        resolveErrorMessage(
          error,
          t("ai.mcp.servers.error.discover", "MCP 能力发现失败"),
        ),
      );
      await refresh(true);
    } finally {
      discoveringRef.current = false; operation.finish(request);
      if (operation.current()) setDiscovering(false);
    }
  }, [canDiscover, message, onChanged, refresh, server.id, serversUrl, t, loaded, loadError, confirmation, open, operation.begin, operation.current, operation.finish]);

  useEffect(() => {
    if (
      !open || !operation.selected || !!confirmation ||
      currentPool.readyReplicas < 1 ||
      discoveryAttempted.current ||
      (!discoverAfterReady && currentServer.status === "READY")
    ) {
      return;
    }
    void discover();
  }, [
    currentPool.readyReplicas,
    currentServer.status,
    discover,
    discoverAfterReady,
    open, operation.selected, confirmation,
  ]);

  const operate = useCallback(
    async (action: "activate" | "redeploy" | "disable") => {
      if (!canManage || !open || operatingRef.current || discoveringRef.current || !loaded || loadError) return;
      const request = operation.begin(); if (!request) return;
      readRequest.current?.abort();
      operatingRef.current = true;
      setOperating(true);
      try {
        const result = await post<RuntimePool>(
          `${poolsUrl}/${currentPool.id}/${action}`,
          {}, {signal: request.signal},
        );
        if (!operation.current(request.signal)) return;
        setConfirmation(undefined); setCurrentPool(result);
        if (action === "redeploy" || action === "activate") {
          setDiscovery(undefined);
          setDiscoverAfterReady(true);
          discoveryAttempted.current = false;
        }
        message.success(
          action === "disable"
            ? t("ai.runtime.success.pool.disabled", "Runtime Pool 已禁用")
            : action === "redeploy"
              ? t(
                  "ai.runtime.success.pool.redeployed",
                  "Runtime Pool 正在重新部署",
                )
              : t("ai.runtime.success.pool.activated", "Runtime Pool 已激活"),
        );
        onChanged();
        await refresh(true);
      } catch (error) {
        if (!operation.current(request.signal)) return;
        message.error(
          resolveErrorMessage(
            error,
            t("ai.runtime.error.pool.operation", "Runtime Pool 操作失败"),
          ),
        );
      } finally {
        operatingRef.current = false; operation.finish(request);
        if (operation.current()) setOperating(false);
      }
    },
    [canManage, currentPool.id, message, onChanged, poolsUrl, refresh, t, loaded, loadError, open, operation.begin, operation.current, operation.finish],
  );

  const remove = useCallback(async () => {
    if (!canManage || !open || operatingRef.current || discoveringRef.current || !loaded || loadError
      || currentPool.status !== "DISABLED" || currentPool.currentReplicas !== 0 || currentPool.readyReplicas !== 0 || activeCount !== 0) return;
    const request = operation.begin(); if (!request) return;
    readRequest.current?.abort();
    operatingRef.current = true;
    setOperating(true);
    try {
      await del(poolsUrl + "/" + encodeURIComponent(currentPool.id), [], {signal: request.signal});
      if (!operation.current(request.signal)) return;
      setConfirmation(undefined);
      message.success(
        t(
          "ai.runtime.success.pool.deleted",
          "Runtime Pool 及其已结束 Workload 已删除",
        ),
      );
      onDeleted();
    } catch (error) {
      if (!operation.current(request.signal)) return;
      message.error(
        resolveErrorMessage(
          error,
          t(
            "ai.runtime.error.pool.delete",
            "请先禁用 Pool，并等待全部副本回收后再删除",
          ),
        ),
      );
      await refresh(true);
    } finally {
      operatingRef.current = false; operation.finish(request);
      if (operation.current()) setOperating(false);
    }
  }, [canManage, currentPool.id, currentPool.status, currentPool.currentReplicas, currentPool.readyReplicas, activeCount, message, onDeleted, poolsUrl, refresh, t, loaded, loadError, open, operation.begin, operation.current, operation.finish]);

  const activeWorkloads = useMemo(
    () => workloads.filter((item) => !terminalStatuses.has(item.status)),
    [workloads],
  );
  const scheduled = Boolean(firstActive?.assignedNodeId) || activeWorkloads.some((item) =>
    Boolean(item.assignedNodeId),
  );
  const running = currentPool.readyReplicas > 0;
  const discovered =
    currentServer.status === "READY" && Boolean(currentServer.activeSnapshotId);
  const failed =
    currentPool.status === "ERROR" || currentServer.status === "ERROR";
  const canDelete =
    currentPool.status === "DISABLED" &&
    currentPool.currentReplicas === 0 &&
    currentPool.readyReplicas === 0 && activeCount === 0;
  const workloadError = activeWorkloads.find(
    (item) => item.lastError,
  )?.lastError;
  const errorMessage = currentPool.lastError
    ? runtimeErrorLabel(t, currentPool.lastError)
    : currentServer.lastError
      ? mcpServerErrorLabel(t, currentServer.lastError)
      : workloadError
        ? runtimeErrorLabel(t, workloadError)
        : undefined;

  const stepStatus = (
    finished: boolean,
    active: boolean,
  ): "finish" | "process" | "wait" | "error" => {
    if (finished) return "finish";
    if (failed) return "error";
    return active ? "process" : "wait";
  };

  const toolRows = discovery?.tools ?? [];

  return (
    <>
    <Modal
      wrapClassName="mcp-deployment-status"
      open={open && operation.selected}
      destroyOnHidden={!open}
      width={980}
      title={t("ai.mcp.deployment.title", "托管 MCP 部署中心")}
      onCancel={() => {if (!operatingRef.current && !discoveringRef.current && !confirmation) onClose();}}
      closable={!operating && !discovering && !confirmation}
      maskClosable={!operating && !discovering && !confirmation}
      keyboard={!operating && !discovering && !confirmation}
      footer={
        <TableActions
          selectedKeys={[currentPool.id]}
          selectedRows={[currentPool]}
          isButtonDisabled={button => operating || discovering || !!confirmation || (button.key !== "close" && button.key !== "refresh" && (!loaded || !!loadError || schemaFailed || refreshing))}
          buttons={[
            {
              key: "close",
              sort: 10,
              text: t("ai.runtime.action.close", "关闭"),
              onClick: onClose,
            },
            {
              key: "refresh",
              sort: 20,
              text: t("ai.runtime.action.refresh", "刷新"),
              loading: refreshing,
              onClick: () => void refresh(),
            },
            ...(canManage
              ? [
                  {
                    key: "edit",
                    sort: 30,
                    text: t("ai.mcp.deployment.action.edit", "更新部署配置"),
                    disabled: !canEditPool(currentPool),
                    onClick: () => onEdit(currentPool),
                  },
                  ...(currentPool.status === "DISABLED"
                    ? [
                        {
                          key: "activate",
                          sort: 40,
                          type: "primary" as const,
                          text: t("ai.runtime.action.activate", "激活"),
                          loading: operating,
                          onClick: () => void operate("activate"),
                        },
                      ]
                    : [
                        {
                          key: "redeploy",
                          sort: 50,
                          text: t("ai.runtime.action.redeploy", "重新部署"),
                          loading: operating,
                          onClick: () => setConfirmation("redeploy"),
                        },
                        {
                          key: "disable",
                          sort: 60,
                          text: t("ai.runtime.action.disable", "禁用"),
                          danger: true,
                          loading: operating,
                          onClick: () => setConfirmation("disable"),
                        },
                      ]),
                  {
                    key: "delete",
                    sort: 70,
                    text: t("ai.runtime.action.delete", "删除部署"),
                    danger: true,
                    disabled: !canDelete,
                    loading: operating,
                    onClick: () => setConfirmation("delete"),
                  },
                ]
              : []),
          ]}
        />
      }
    >
      {loadError && <Alert type="error" showIcon title={loadError} style={{marginBottom: 12}}/>}
      {schemaFailed && <Alert type="error" showIcon title={t('table.schemaLoadFail', '页面结构加载失败，请稍后重试。')}
        action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retrySchema', sort: 0,
          text: t('table.retry', '重试'), onClick: () => void workloadSchema.refetch()}]}/>}/>}
      <Alert
        showIcon
        type={errorMessage ? "error" : running ? "success" : "info"}
        style={{ marginBottom: 20 }}
        message={
          errorMessage ||
          (running
            ? t("ai.mcp.deployment.ready", "OCI Runtime 副本已经就绪")
            : t(
                "ai.mcp.deployment.progress",
                "平台正在校验镜像并协调 Runtime 副本",
              ))
        }
        description={
          errorMessage
            ? t(
                "ai.mcp.deployment.error.hint",
                "请修正镜像、Digest、网络或节点容量配置后重新部署。",
              )
            : undefined
        }
      />
      <Steps
        size="small"
        style={{ marginBottom: 24 }}
        items={[
          {
            title: t("ai.mcp.deployment.step.image", "镜像校验"),
            status: stepStatus(
              currentPool.prewarmedNodes > 0 || activeWorkloads.length > 0,
              true,
            ),
          },
          {
            title: t("ai.mcp.deployment.step.schedule", "节点调度"),
            status: stepStatus(scheduled, activeWorkloads.length > 0),
          },
          {
            title: t("ai.mcp.deployment.step.runtime", "Runtime 就绪"),
            status: stepStatus(running, scheduled),
          },
          {
            title: t("ai.mcp.deployment.step.discover", "能力发现"),
            status: stepStatus(discovered, running || discovering),
          },
        ]}
      />
      <Descriptions
        bordered
        size="small"
        column={2}
        style={{ marginBottom: 20 }}
        items={[
          {
            key: "server",
            label: t("ai.mcp-servers.entity.title", "MCP Server"),
            children:
              currentServer.name || currentServer.code || currentServer.id,
          },
          {
            key: "poolStatus",
            label: t("ai.runtime.field.status", "状态"),
            children: (
              <Space>
                <Tag color={statusColor(currentPool.status)}>
                  {runtimeStatusLabel(t, currentPool.status)}
                </Tag>
                <Text>{`${currentPool.readyReplicas}/${currentPool.currentReplicas} → ${currentPool.desiredReplicas}`}</Text>
              </Space>
            ),
          },
          {
            key: "image",
            label: t("ai.runtime.field.imageReference", "OCI 镜像"),
            span: 2,
            children: (
              <Space direction="vertical" size={0}>
                <Text copyable>{currentPool.imageReference}</Text>
                <Text type="secondary" copyable>
                  {currentPool.imageDigest}
                </Text>
              </Space>
            ),
          },
          {
            key: "resources",
            label: t("ai.runtime.field.resources", "资源限制"),
            children: `${formatCpu(currentPool.requestedNanoCpus)} · ${formatBytes(currentPool.requestedMemoryBytes)} · PID ${currentPool.requestedPidsLimit}`,
          },
          {
            key: "protocol",
            label: t("ai.mcp.gateway.protocol", "协议版本"),
            children:
              currentServer.protocolVersion ||
              discovery?.protocolVersion ||
              "-",
          },
        ]}
      />
      <DataTable
        storageKey="ai.mcp.deployment-workloads"
        rowKey="id"
        size="small"
        loading={refreshing || workloadSchema.isFetching}
        query={querySchema ? {mode: 'remote', schema: querySchema, state: workloadQuery, onChange: next => {
          if (operation.busy || confirmation) return;
          setWorkloadQuery(next); setWorkloadPage(0);
        }} : undefined}
        dataSource={loadError || schemaFailed ? [] : workloads}
        pagination={{current: workloadPage + 1, pageSize: 20, total: workloadTotal, hideOnSinglePage: true,
          disabled: operation.busy || !!confirmation || refreshing,
          onChange: page => {if (!operation.busy && !confirmation && !refreshing) setWorkloadPage(page - 1);}}}
        style={{ marginBottom: 20 }}
        columns={[
          {
            title: t("ai.runtime.workload.entity", "Workload"),
            dataIndex: "runtimeWorkloadId",
            ellipsis: true,
            render: (value: string, item: RuntimeWorkload) => value || item.id,
          },
          {
            title: t("ai.runtime.field.node", "节点"),
            dataIndex: "assignedNodeId",
            width: 170,
            render: (value?: string) => value || "-",
          },
          {
            title: t("ai.runtime.field.status", "状态"),
            dataIndex: "status",
            width: 110,
            render: (value: string) => (
              <Tag color={statusColor(value)}>
                {runtimeStatusLabel(t, value)}
              </Tag>
            ),
          },
          {
            title: t("ai.runtime.field.startedAt", "启动时间"),
            dataIndex: "startedAt",
            width: 180,
            render: formatDateTime,
          },
        ].filter(column => !!querySchema?.properties[column.dataIndex])}
      />
      {running && (
        <Space direction="vertical" style={{ width: "100%" }} size="middle">
          <Space>
            {canDiscover && (
              <TableActions
                selectedKeys={[]}
                selectedRows={[]}
                buttons={[
                  {
                    key: "discover",
                    sort: 80,
                    text: discovered
                      ? t("ai.mcp.deployment.action.rediscover", "重新发现能力")
                      : t("ai.mcp.deployment.action.discover", "发现工具"),
                    type: discovered ? "default" : "primary",
                    loading: discovering,
                    onClick: () => void discover(),
                  },
                ]}
              />
            )}
            {discovered && onOpenCapabilities && (
              <TableActions
                selectedKeys={[]}
                selectedRows={[]}
                buttons={[
                  {
                    key: "testTools",
                    sort: 90,
                    text: t("ai.mcp.deployment.action.test", "打开工具测试"),
                    type: "primary",
                    onClick: () => {
                      onOpenCapabilities(server.id);
                    },
                  },
                ]}
              />
            )}
          </Space>
          {toolRows.length > 0 && (
            <>
              <Text strong>
                {t("ai.mcp.deployment.discoveredTools", "本次发现的工具")}
              </Text>
              <DataTable
                storageKey="ai.mcp.deployment-tools"
                query={{
                  mode: "local",
                  scope: "result",
                  fields: {
                    name: { type: "string" },
                    title: { type: "string" },
                    description: { type: "string" },
                  },
                }}
                rowKey="name"
                size="small"
                dataSource={toolRows}
                pagination={{ pageSize: 5, hideOnSinglePage: true }}
                columns={[
                  {
                    title: t("ai.mcp.tools.name", "工具名称"),
                    dataIndex: "name",
                  },
                  {
                    title: t("ai.mcp.tools.title", "标题"),
                    dataIndex: "title",
                    width: 180,
                  },
                  {
                    title: t("ai.mcp.tools.description", "说明"),
                    dataIndex: "description",
                  },
                ]}
              />
            </>
          )}
        </Space>
      )}
      {!canDelete && currentPool.status === "DISABLED" && (
        <Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 0 }}>
          {t(
            "ai.runtime.pool.delete.wait",
            "正在回收 Runtime 副本；副本归零后即可删除部署。",
          )}
        </Paragraph>
      )}
    </Modal>
    <Modal wrapClassName="mcp-deployment-confirmation" open={!!confirmation && open && operation.selected} destroyOnHidden={!confirmation}
      title={confirmation === "redeploy" ? t("ai.runtime.confirm.pool.redeploy", "确认使用当前配置替换全部 Runtime 副本？")
        : confirmation === "disable" ? t("ai.runtime.confirm.pool.disable", "确认禁用并回收该 Pool 的全部副本？")
        : t("ai.runtime.confirm.pool.delete", "确认删除该 Pool 和已结束的 Workload 记录？")}
      closable={!operation.busy} maskClosable={!operation.busy} keyboard={!operation.busy}
      onCancel={() => {if (!operation.busy) setConfirmation(undefined);}}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: "cancel", sort: 1, text: t("ai.mcp.action.cancel", "取消"), disabled: operation.busy},
        {key: "confirm", sort: 2, text: t("ai.runtime.action.confirm", "确认"), type: "primary", danger: confirmation !== "redeploy", loading: operation.busy, disabled: operation.busy},
      ]} onButtonEvents={{cancel: () => {if (!operation.busy) setConfirmation(undefined);},
        confirm: () => {if (confirmation === "delete") void remove(); else if (confirmation) void operate(confirmation);}}}/>}>
      <Text strong>{currentPool.name || currentPool.code || currentPool.id}</Text>
    </Modal>
    </>
  );
};

export default McpDeploymentStatusDialog;

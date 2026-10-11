import TableActions from "@simplepoint/components/Table/Actions";
import api from "@/api";
import type { TableButtonProps } from "@simplepoint/components/Table";
import SimpleTable from "@simplepoint/components/SimpleTable";
import DataTable from "@simplepoint/components/DataTable";
import { resolveApiErrorMessage } from "@simplepoint/shared/api/client";
import { get, post, useData } from "@simplepoint/shared/api/methods";
import { useI18n } from "@simplepoint/shared/hooks/useI18n";
import type { Page } from "@simplepoint/shared/types/request";
import { Alert, App, Modal, Space, Spin, Tabs, Tag, Typography } from "antd";
import type { Key } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {useNavigate} from "react-router";
import {useQueryScope} from "@simplepoint/shared/hooks/useQueryScope";
import {usePageLocation, usePageRetention} from "@simplepoint/shared/page/hooks";
import PageSection from "@simplepoint/shared/page/Section";
import {useOperationScope} from "./Runtime/useOperationScope";
import { resourceScopeLabel } from "../modelLabels";
import { localizeWorkbenchOperationError } from "../workbenchErrorCodes";
import McpCapabilities from "./Capabilities";
import McpDeploymentStatusDialog from "./McpDeploymentStatusDialog";
import McpGatewayStatusCard from "./McpGatewayStatusCard";
import McpPublications from "./Publications";
import Runtime from "./Runtime/McpRuntime";
import RuntimePoolEditorDialog from "./Runtime/RuntimePoolEditorDialog";
import type { RuntimePool, RuntimeServer } from "./Runtime/types";
import {
  mcpAuthenticationLabel,
  mcpOauthResultNotice,
  mcpServerErrorLabel,
  mcpServerStatusLabel,
  mcpTransportLabel,
} from "./labels";

const { Text } = Typography;

type McpServerRow = {
  id: string;
  name?: string;
  code?: string;
  status?: string;
  scopeType?: string;
  authenticationType?: string;
  deploymentType?: string;
  transportType?: string;
  enabled?: boolean;
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

type OAuthAuthorizationStart = {
  authorizationUrl: string;
  expiresAt: string;
};

type DeploymentView = {
  server: McpServerRow;
  pool: RuntimePool;
  autoDiscover: boolean;
};

type McpWorkbenchPermissions = {
  discoverServer: boolean;
  viewServers: boolean;
  viewGateway: boolean;
  viewCapabilities: boolean;
  viewPublications: boolean;
  viewRuntime: boolean;
  viewNodes: boolean;
  managePools: boolean;
  manageWorkloads: boolean;
  manageSecrets: boolean;
};

const McpServers = () => {
  const config = api["ai-workbench.mcp-servers"];
  const { t, ensure, locale } = useI18n();
  const {message} = App.useApp();
  const ownerLocation = usePageLocation();
  const location = useMemo(() => new URL(ownerLocation, "https://simplepoint.invalid"), [ownerLocation]);
  const operation = useOperationScope();
  const navigate = useNavigate();
  const [tableKey, setTableKey] = useState(0);
  const [deployingServer, setDeployingServer] = useState<McpServerRow>();
  const [deployingPool, setDeployingPool] = useState<RuntimePool>();
  const [deploymentView, setDeploymentView] = useState<DeploymentView>();
  const [activeSection, setActiveSection] = useState("servers");
  const [capabilityServerId, setCapabilityServerId] = useState<string>();
  const [discovery, setDiscovery] = useState<DiscoveryResult>();
  const [operationError, setOperationError] = useState<string>();
  const oauthCompleted = useRef<string | undefined>(undefined);
  const oauthPending = useRef(false);
  const permissionsQuery = useData([config.baseUrl, "workbench-permissions"], ({signal}) =>
    get<McpWorkbenchPermissions>(config.baseUrl + "/workbench-permissions", {}, {signal}), {retry: false, staleTime: 30_000, gcTime: 120_000});
  const permissions = permissionsQuery.error ? undefined : permissionsQuery.data;
  const permissionsLoading = permissionsQuery.isFetching;
  const permissionsError = permissionsQuery.error ? resolveApiErrorMessage(permissionsQuery.error,
    t("ai.mcp.center.error.permissions", "MCP 中心权限加载失败")) : undefined;
  const locked = operation.busy || !!deployingServer || !!deploymentView || !!discovery;
  usePageRetention(false, locked);
  const loadPermissions = () => {if (operation.selected && !permissionsQuery.isFetching) void permissionsQuery.refetch();};
  useEffect(() => {
    if (!permissions) return;
    const allowed = {servers: permissions.viewServers, capabilities: permissions.viewCapabilities,
      publications: permissions.viewPublications, runtime: permissions.viewRuntime};
    setActiveSection(current => allowed[current as keyof typeof allowed] ? current
      : Object.keys(allowed).find(key => allowed[key as keyof typeof allowed]) ?? "");
  }, [permissions]);

  const tableErrorMessageResolver = useCallback(
    (error: unknown) =>
      localizeWorkbenchOperationError(t, error, {
        key: "ai.error.operationFailed",
        fallback: "MCP Server 操作失败，请稍后重试",
      }),
    [t],
  );

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  const clearOauthQuery = useCallback(() => {
    if (!operation.isSelected() || !operation.current()) return;
    const params = new URLSearchParams(location.search);
    for (const key of ["mcpOauthResult", "state", "code", "error", "error_description"]) params.delete(key);
    navigate(
      { pathname: location.pathname, search: params.toString(), hash: location.hash },
      {
        replace: true,
      },
    );
  }, [location.hash, location.pathname, location.search, navigate, operation.selected]);

  useEffect(() => {
    if (!operation.selected || !operation.current()) return;
    if (oauthCompleted.current === location.search) {if (!oauthPending.current) clearOauthQuery(); return;}
    const parameters = new URLSearchParams(location.search);
    const browserResult = mcpOauthResultNotice(
      parameters.get("mcpOauthResult"),
    );
    if (browserResult) {
      oauthCompleted.current = location.search;
      const content = t(browserResult.key, browserResult.fallback);
      if (browserResult.type === "success") {
        message.success(content);
      } else {
        message.error(content);
      }
      setTableKey((value) => value + 1);
      clearOauthQuery();
      return;
    }
    const state = parameters.get("state");
    const code = parameters.get("code");
    const error = parameters.get("error");
    if (!state || (!code && !error)) return;
    const complete = async () => {
      const request = operation.begin();
      if (!request) return;
      oauthPending.current = true;
      oauthCompleted.current = location.search;
      const hide = message.loading(
        t("ai.mcp.oauth.progress.complete", "正在完成 MCP OAuth 授权..."),
        0,
      );
      try {
        await post(`${config.baseUrl}/oauth/callback`, {
          code,
          state,
          error,
          errorDescription: parameters.get("error_description"),
        }, {signal: request.signal});
        if (!operation.current(request.signal)) return;
        hide();
        const callbackNotice = mcpOauthResultNotice(
          error
            ? error === "access_denied"
              ? "AI_MCP_OAUTH_ACCESS_DENIED"
              : "AI_MCP_OAUTH_AUTHORIZATION_FAILED"
            : "SUCCESS",
        );
        if (callbackNotice?.type === "success") {
          message.success(t(callbackNotice.key, callbackNotice.fallback));
        } else if (callbackNotice) {
          message.error(t(callbackNotice.key, callbackNotice.fallback));
        }
        setTableKey((value) => value + 1);
      } catch (callbackError) {
        if (!operation.current(request.signal)) return;
        hide();
        message.error(
          resolveApiErrorMessage(
            callbackError,
            t("ai.mcp.oauth.error.complete", "MCP OAuth 授权失败"),
          ),
        );
      } finally {
        oauthPending.current = false; hide(); operation.finish(request);
        if (operation.current(request.signal)) clearOauthQuery();
      }
    };
    void complete();
  }, [clearOauthQuery, config.baseUrl, location.search, message, t, operation.selected]);

  const discover = async (rows: McpServerRow[]) => {
    const server = rows[0];
    if (!server?.id || permissions?.discoverServer !== true || locked) return;
    const request = operation.begin(); if (!request) return; setOperationError(undefined);
    try {
      const result = await post<DiscoveryResult>(config.baseUrl + "/" + encodeURIComponent(server.id) + "/discover", {}, {signal: request.signal});
      if (!operation.current(request.signal)) return;
      setTableKey(value => value + 1); setDiscovery(result);
    } catch (error) {
      if (operation.current(request.signal)) {setTableKey(value => value + 1); setOperationError(resolveApiErrorMessage(error,
        t("ai.mcp.servers.error.discover", "MCP 能力发现失败")));}
    } finally {operation.finish(request);}
  };
  const authorize = async (rows: McpServerRow[]) => {
    const server = rows[0];
    if (!server?.id || server.authenticationType !== "OAUTH2" || locked) return;
    const request = operation.begin(); if (!request) return; setOperationError(undefined);
    try {
      const result = await post<OAuthAuthorizationStart>(config.baseUrl + "/" + encodeURIComponent(server.id) + "/oauth/authorize", {}, {signal: request.signal});
      if (!operation.current(request.signal) || !operation.isSelected()) return;
      const url = new URL(result.authorizationUrl);
      if (url.protocol !== "https:" || url.username || url.password) throw Error(t("ai.mcp.oauth.error.start", "无法发起 MCP OAuth 授权"));
      window.location.assign(url.href);
    } catch (error) {if (operation.current(request.signal)) setOperationError(resolveApiErrorMessage(error,
      t("ai.mcp.oauth.error.start", "无法发起 MCP OAuth 授权")));}
    finally {operation.finish(request);}
  };
  const deploy = async (rows: McpServerRow[]) => {
    const server = rows[0];
    if (!server?.id || server.deploymentType !== "MANAGED_OCI" || permissions?.managePools !== true || locked) return;
    const request = operation.begin(); if (!request) return; setOperationError(undefined);
    try {
      const page = await get<Page<RuntimePool>>(config.poolsUrl, {page: 0, size: 1,
        serverId: JSON.stringify({version: 1, where: {field: "serverId", op: "equals", value: server.id}})}, {signal: request.signal});
      if (!operation.current(request.signal)) return;
      const pool = page.content?.[0];
      if (pool) setDeploymentView({server: {...server}, pool, autoDiscover: false});
      else {setDeployingPool(undefined); setDeployingServer({...server});}
    } catch (error) {if (operation.current(request.signal)) setOperationError(resolveApiErrorMessage(error,
      t("ai.runtime.error.pools.load", "Runtime Pool 加载失败")));}
    finally {operation.finish(request);}
  };

  const formSchemaTransform = useCallback(
    (schema: any) => {
      const nextSchema = structuredClone(schema ?? {});
      const properties = nextSchema?.properties ?? {};
      if (properties.deploymentType) {
        properties.deploymentType.oneOf = [
          {
            const: "REMOTE",
            title: t("ai.mcp.deployment.remote", "远程 MCP Server"),
          },
          {
            const: "MANAGED_OCI",
            title: t("ai.mcp.deployment.managed", "平台托管 OCI"),
          },
        ];
        properties.deploymentType.default = "REMOTE";
      }
      if (properties.transportType) {
        properties.transportType.oneOf = [
          { const: "STREAMABLE_HTTP", title: "Streamable HTTP" },
          { const: "STDIO", title: "stdio" },
        ];
        properties.transportType.default = "STREAMABLE_HTTP";
      }
      if (properties.authenticationType) {
        properties.authenticationType.oneOf = [
          { const: "NONE", title: t("ai.mcp.auth.none", "无认证") },
          { const: "BEARER", title: "Bearer Token" },
          { const: "OAUTH2", title: "OAuth 2.1" },
        ];
        properties.authenticationType.default = "NONE";
      }
      if (properties.bearerToken) {
        properties.bearerToken.description = t(
          "ai.mcp.servers.bearerToken.description",
          "凭证只写入；编辑时留空会保留已有凭证。",
        );
      }
      if (properties.oauthClientId) {
        properties.oauthClientId.description = t(
          "ai.mcp.oauth.clientId.description",
          "可填写预注册 Client ID；留空时将尝试动态客户端注册。",
        );
      }
      if (properties.oauthClientSecret) {
        properties.oauthClientSecret.description = t(
          "ai.mcp.oauth.clientSecret.description",
          "公共客户端可留空；凭证只写入，编辑留空会保留现有值。",
        );
      }
      if (properties.oauthRedirectUri) {
        properties.oauthRedirectUri.description = t(
          "ai.mcp.oauth.redirectUri.description",
          "必须精确指向当前工作台 MCP 中心页面。",
        );
      }
      delete properties.scopeType;
      delete properties.tenantId;
      delete properties.hasCredential;
      delete properties.hasOauthToken;
      delete properties.oauthStatus;
      delete properties.oauthAccessTokenExpiresAt;
      delete properties.oauthAuthorizedAt;
      delete properties.status;
      delete properties.protocolVersion;
      delete properties.remoteServerName;
      delete properties.remoteServerVersion;
      delete properties.lastDiscoveredAt;
      delete properties.lastError;
      return nextSchema;
    },
    [t],
  );

  const columnOverrides = useMemo(
    () => ({
      scopeType: {
        width: 110,
        render: (value: string) => (
          <Tag color={value === "TENANT" ? "blue" : "purple"}>
            {resourceScopeLabel(t, value)}
          </Tag>
        ),
      },
      transportType: {
        width: 160,
        render: (value: string) => mcpTransportLabel(t, value),
      },
      deploymentType: {
        width: 150,
        render: (value: string) => (
          <Tag color={value === "MANAGED_OCI" ? "cyan" : "default"}>
            {value === "MANAGED_OCI"
              ? t("ai.mcp.deployment.managed", "平台托管 OCI")
              : t("ai.mcp.deployment.remote", "远程 MCP Server")}
          </Tag>
        ),
      },
      endpointUrl: { width: 320, ellipsis: true },
      authenticationType: {
        width: 140,
        render: (value: string) => mcpAuthenticationLabel(t, value),
      },
      status: {
        width: 110,
        render: (value: string) => {
          const color =
            value === "READY"
              ? "green"
              : value === "ERROR"
                ? "red"
                : value === "DRAFT"
                  ? "blue"
                  : "default";
          return <Tag color={color}>{mcpServerStatusLabel(t, value)}</Tag>;
        },
      },
      enabled: {
        width: 100,
        render: (value: boolean) => (
          <Tag color={value ? "green" : "default"}>
            {value
              ? t("ai.common.enabled", "已启用")
              : t("ai.common.disabled", "已禁用")}
          </Tag>
        ),
      },
      lastError: {
        width: 260,
        ellipsis: true,
        render: (value: string) =>
          value ? (
            <Text type="danger">{mcpServerErrorLabel(t, value)}</Text>
          ) : (
            "-"
          ),
      },
    }),
    [t],
  );

  const customButtonEvents: Record<
    string,
    (
      selectedRowKeys: Key[],
      selectedRows: McpServerRow[],
      props: TableButtonProps,
    ) => void
  > = {
    capabilities: (_keys, rows) => {
      const server = rows[0];
      if (locked || !operation.selected || permissions?.viewCapabilities !== true) return;
      if (!server?.id) {
        message.warning(
          t("ai.mcp.servers.warning.select", "请选择一个 MCP Server"),
        );
        return;
      }
      if (server.status !== "READY" || server.enabled === false) {
        message.info(
          t(
            "ai.mcp.tools.warning.notReady",
            "请先启用 Server 并完成能力发现，再浏览和调试 MCP 能力。",
          ),
        );
        return;
      }
      setCapabilityServerId(server.id);
      setActiveSection("capabilities");
    },
    discover: (_keys, rows) => void discover(rows),
    authorize: (_keys, rows) => void authorize(rows),
    deploy: (_keys, rows) => void deploy(rows),
  };

  return (
    <div
      className="mcp-center"
      style={{
        height: "100%",
        minHeight: 0,
        display: "flex",
        flexDirection: "column",
      }}
    >
      <Space
        direction="vertical"
        size={12}
        style={{ display: "flex", marginBottom: 12 }}
      >
        <Alert
          showIcon
          type="info"
          closable
          message={t("ai.mcp.center.title", "MCP 中心")}
          description={t(
            "ai.mcp.center.description",
            "统一管理 MCP Server 的连接、认证、能力发现、托管部署和运行状态。",
          )}
        />
        {permissions?.viewGateway && (
          <McpGatewayStatusCard statusUrl={config.gatewayStatusUrl} />
        )}
      </Space>
      {operationError && <Alert type="error" showIcon title={operationError} style={{marginBottom: 12}}/>}
      {permissionsError ? (
        <Alert
          showIcon
          type="error"
          message={t("ai.mcp.center.error.permissions", "MCP 中心权限加载失败")}
          description={permissionsError}
          action={
            <TableActions
              selectedKeys={[]}
              selectedRows={[]}
              buttons={[
                {
                  key: "action1",
                  sort: 10,
                  text: t("ai.mcp.action.retry", "重试"),
                  size: "small",
                  onClick: () => void loadPermissions(),
                },
              ]}
            />
          }
        />
      ) : (
        <Spin spinning={permissionsLoading} style={{ flex: 1, minHeight: 0 }}>
          <Tabs
            activeKey={activeSection}
            onChange={key => {if (!locked) setActiveSection(key);}}
            style={{ flex: 1, minHeight: 0 }}
            styles={{
              body: { minHeight: 0, overflow: "hidden" },
              content: { height: "100%" },
            }}
            items={[
              permissions?.viewServers
                ? {
                    key: "servers", disabled: locked,
                    label: t("ai.mcp.center.tab.servers", "Servers"),
                    children: (
                      <div style={{ height: "100%", minHeight: 0 }}>
                        <PageSection selected={activeSection === "servers"}><SimpleTable
                          key={tableKey}
                          {...config}
                          customButtonEvents={customButtonEvents}
                          errorMessageResolver={tableErrorMessageResolver}
                          formSchemaTransform={formSchemaTransform}
                          columnOverrides={columnOverrides}
                          isButtonDisabled={button => locked || !operation.selected
                            || button.key === 'discover' && permissions.discoverServer !== true
                            || button.key === 'deploy' && permissions.managePools !== true
                            || button.key === 'capabilities' && permissions.viewCapabilities !== true}
                        /></PageSection>
                      </div>
                    ),
                  }
                : null,
              permissions?.viewCapabilities
                ? {
                    key: "capabilities", disabled: locked,
                    label: t("ai.mcp.center.tab.capabilities", "能力与调试"),
                    children: (
                      <div style={{ height: "100%", overflow: "auto" }}>
                        <PageSection selected={activeSection === "capabilities"}><McpCapabilities initialServerId={capabilityServerId}/></PageSection>
                      </div>
                    ),
                  }
                : null,
              permissions?.viewPublications
                ? {
                    key: "publications", disabled: locked,
                    label: t("ai.mcp.center.tab.publications", "对外发布"),
                    children: (
                      <div style={{ height: "100%", minHeight: 0 }}>
                        <PageSection selected={activeSection === "publications"}><McpPublications/></PageSection>
                      </div>
                    ),
                  }
                : null,
              permissions?.viewRuntime
                ? {
                    key: "runtime", disabled: locked,
                    label: t("ai.mcp.center.tab.runtime", "运行与安全"),
                    children: (
                      <div style={{ height: "100%", minHeight: 0 }}>
                        <PageSection selected={activeSection === "runtime"}><Runtime permissions={permissions}/></PageSection>
                      </div>
                    ),
                  }
                : null,
            ].filter((item): item is NonNullable<typeof item> => item !== null)}
          />
        </Spin>
      )}
      <Modal wrapClassName="mcp-discovery" open={!!discovery && operation.selected} destroyOnHidden={!discovery}
        title={t("ai.mcp.servers.discover.title", "MCP 能力发现完成")} width={780}
        onCancel={() => setDiscovery(undefined)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: "close", sort: 1, text: t("ai.runtime.action.close", "关闭")},
        ]} onButtonEvents={{close: () => setDiscovery(undefined)}}/>}>
        <Text type="secondary">{[discovery?.serverName || "—", discovery?.serverVersion, discovery?.protocolVersion || "—"].filter(Boolean).join(" · ")}</Text>
        <DataTable<McpTool> storageKey="ai.mcp.discovery-tools" size="small" rowKey="name" pagination={{pageSize: 8, hideOnSinglePage: true}}
          query={{mode: 'local', scope: 'result', contextKey: JSON.stringify(discovery), fields: {
            name: {type: 'string'}, title: {type: 'string'}, description: {type: 'string'},
          }}}
          dataSource={discovery?.tools ?? []} columns={[
            {title: t("ai.mcp.tools.name", "工具名称"), dataIndex: "name", width: 220},
            {title: t("ai.mcp.tools.title", "标题"), dataIndex: "title", width: 180},
            {title: t("ai.mcp.tools.description", "说明"), dataIndex: "description"},
          ]}/>
      </Modal>
      <RuntimePoolEditorDialog
        open={Boolean(deployingServer)}
        pool={deployingPool}
        serversUrl={config.baseUrl}
        fixedServer={deployingServer as RuntimeServer | undefined}
        poolsUrl={config.poolsUrl}
        secretsUrl={config.secretsUrl}
        onCancel={() => {
          setDeployingServer(undefined);
          setDeployingPool(undefined);
        }}
        onSaved={(pool) => {
          const server = deployingServer;
          setDeployingServer(undefined);
          setDeployingPool(undefined);
          if (server) {
            setDeploymentView({ server, pool, autoDiscover: true });
          }
          setTableKey((value) => value + 1);
        }}
      />
      {deploymentView && (
        <McpDeploymentStatusDialog
          open
          server={deploymentView.server}
          pool={deploymentView.pool}
          poolsUrl={config.poolsUrl}
          workloadsUrl={config.workloadsUrl}
          serversUrl={config.baseUrl}
          autoDiscover={deploymentView.autoDiscover}
          canManage={permissions?.managePools === true}
          canDiscover={permissions?.discoverServer === true}
          onClose={() => setDeploymentView(undefined)}
          onEdit={(pool) => {
            setDeployingServer(deploymentView.server);
            setDeployingPool(pool);
            setDeploymentView(undefined);
          }}
          onChanged={() => setTableKey((value) => value + 1)}
          onOpenCapabilities={
            permissions?.viewCapabilities
              ? (serverId) => {
                  setDeploymentView(undefined);
                  setCapabilityServerId(serverId);
                  setActiveSection("capabilities");
                }
              : undefined
          }
          onDeleted={() => {
            setDeploymentView(undefined);
            setTableKey((value) => value + 1);
          }}
        />
      )}
    </div>
  );
};

export default function ScopedMcpServers() {
  const scope = useQueryScope();
  return <McpServers key={JSON.stringify(scope)}/>;
}

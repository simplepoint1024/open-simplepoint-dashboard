type Translate = (key: string, fallback?: string) => string;

export type McpOauthResultNotice = {
  type: 'success' | 'error';
  key: string;
  fallback: string;
};

export const mcpOauthResultNotice = (
  result?: string | null,
): McpOauthResultNotice | undefined => {
  if (!result) return undefined;
  switch (result) {
    case 'SUCCESS':
      return {
        type: 'success',
        key: 'ai.mcp.oauth.success.complete',
        fallback: 'MCP OAuth 授权已完成',
      };
    case 'AI_MCP_OAUTH_ACCESS_DENIED':
      return {
        type: 'error',
        key: 'ai.mcp.oauth.error.accessDenied',
        fallback: '授权已取消，MCP Server 尚未连接',
      };
    case 'AI_MCP_OAUTH_AUTHORIZATION_EXPIRED':
      return {
        type: 'error',
        key: 'ai.mcp.oauth.error.expired',
        fallback: 'OAuth 授权请求已失效，请重新发起授权',
      };
    case 'AI_MCP_OAUTH_AUTHORIZATION_FAILED':
    default:
      return {
        type: 'error',
        key: 'ai.mcp.oauth.error.complete',
        fallback: 'MCP OAuth 授权失败，请重新尝试',
      };
  }
};

export const mcpServerStatusLabel = (
  t: Translate,
  status?: string,
) => {
  switch (status) {
    case 'DRAFT':
      return t('ai.mcp.server.status.draft', '草稿');
    case 'READY':
      return t('ai.mcp.server.status.ready', '就绪');
    case 'ERROR':
      return t('ai.mcp.server.status.error', '异常');
    case 'DISABLED':
      return t('ai.mcp.server.status.disabled', '已停用');
    default:
      return t('ai.mcp.server.status.unknown', '状态未知');
  }
};

export const mcpServerErrorLabel = (
  t: Translate,
  errorCode?: string,
) => {
  switch (errorCode) {
    case 'AI_MCP_DISCOVERY_FAILED':
      return t(
        'ai.mcp.server.error.discoveryFailed',
        'MCP 能力发现失败，请检查连接、认证和协议配置后重试',
      );
    default:
      return t(
        'ai.mcp.server.error.unknown',
        'MCP Server 状态异常，请检查配置后重试',
      );
  }
};

export const mcpTransportLabel = (
  t: Translate,
  transportType?: string,
) => {
  switch (transportType) {
    case 'STREAMABLE_HTTP':
      return t('ai.mcp.transport.streamableHttp', 'Streamable HTTP');
    case 'STDIO':
      return t('ai.mcp.transport.stdio', 'stdio');
    default:
      return t('ai.mcp.transport.unknown', '传输方式未知');
  }
};

export const mcpAuthenticationLabel = (
  t: Translate,
  authenticationType?: string,
) => {
  switch (authenticationType) {
    case 'NONE':
      return t('ai.mcp.auth.none', '无认证');
    case 'BEARER':
      return t('ai.mcp.auth.bearer', 'Bearer 令牌');
    case 'OAUTH2':
      return t('ai.mcp.auth.oauth2', 'OAuth 2.1');
    default:
      return t('ai.mcp.auth.unknown', '认证方式未知');
  }
};

export const mcpPublicationStatusLabel = (
  t: Translate,
  status?: string,
) => {
  switch (status) {
    case 'DRAFT':
      return t('ai.mcp.publication.status.draft', '草稿');
    case 'PUBLISHED':
      return t('ai.mcp.publication.status.published', '已发布');
    case 'DISABLED':
      return t('ai.mcp.publication.status.disabled', '已停用');
    case 'ERROR':
      return t('ai.mcp.publication.status.error', '异常');
    default:
      return t('ai.mcp.publication.status.unknown', '状态未知');
  }
};

export const mcpCapabilityTypeLabel = (
  t: Translate,
  capabilityType?: string,
) => {
  switch (capabilityType) {
    case 'TOOL':
      return t('ai.mcp.capabilities.tools', 'Tools');
    case 'RESOURCE':
      return t('ai.mcp.capabilities.resources', 'Resources');
    case 'RESOURCE_TEMPLATE':
      return t('ai.mcp.capabilities.templates', 'Resource Templates');
    case 'PROMPT':
      return t('ai.mcp.capabilities.prompts', 'Prompts');
    default:
      return t('ai.mcp.capabilities.unknown', '未知能力类型');
  }
};

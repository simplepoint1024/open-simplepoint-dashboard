package org.simplepoint.plugin.ai.mcp.api.constants;

/**
 * HTTP paths owned by the AI MCP domain.
 */
public final class AiMcpPaths {

  public static final String GATEWAY = "/workbench/mcp/gateway";

  public static final String SERVERS = "/workbench/mcp/servers";

  public static final String PUBLICATIONS = "/workbench/mcp/publications";

  public static final String INTERNAL_GATEWAY = "/internal/mcp";

  public static final String INTERNAL_PUBLICATIONS =
      INTERNAL_GATEWAY + "/publications";

  public static final String INTERNAL_UPSTREAM_EVENTS =
      INTERNAL_PUBLICATIONS + "/upstream-events";

  public static final String INTERNAL_STATUS = INTERNAL_GATEWAY + "/status";

  public static final String INTERNAL_DISCOVER = INTERNAL_GATEWAY + "/connections/discover";

  public static final String INTERNAL_CALL_TOOL = INTERNAL_GATEWAY + "/tools/call";

  public static final String INTERNAL_CALL_WORKFLOW_TOOL =
      INTERNAL_GATEWAY + "/workflows/tools/call";

  public static final String INTERNAL_GET_WORKFLOW_PROMPT =
      INTERNAL_GATEWAY + "/workflows/prompts/get";

  public static final String INTERNAL_READ_WORKFLOW_RESOURCE =
      INTERNAL_GATEWAY + "/workflows/resources/read";

  public static final String INTERNAL_READ_RESOURCE =
      INTERNAL_GATEWAY + "/resources/read";

  public static final String INTERNAL_GET_PROMPT =
      INTERNAL_GATEWAY + "/prompts/get";

  public static final String INTERNAL_OAUTH_DISCOVER =
      INTERNAL_GATEWAY + "/oauth/discover";

  public static final String INTERNAL_OAUTH_REGISTER =
      INTERNAL_GATEWAY + "/oauth/register";

  public static final String INTERNAL_OAUTH_TOKEN =
      INTERNAL_GATEWAY + "/oauth/token";

  private AiMcpPaths() {
  }
}

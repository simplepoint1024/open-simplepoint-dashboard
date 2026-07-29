package org.simplepoint.mcp.gateway.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational limits for remote MCP connections and the internal control API.
 */
@ConfigurationProperties(prefix = "simplepoint.mcp.gateway")
public class McpGatewayProperties {

  private Duration connectTimeout = Duration.ofSeconds(10);

  private Duration initializationTimeout = Duration.ofSeconds(15);

  private Duration requestTimeout = Duration.ofSeconds(30);

  private long maxArgumentsBytes = 1024L * 1024L;

  private long maxResultBytes = 10L * 1024L * 1024L;

  private long maxOauthResponseBytes = 1024L * 1024L;

  private int maxProtocolRequestBytes = 2 * 1024 * 1024;

  private boolean allowInsecureOauthEndpoints;

  private String controlPlaneBaseUrl = "http://ai:2888";

  private Duration publicationManifestTtl = Duration.ofSeconds(30);

  private int maxRemoteSessions = 256;

  private int maxSubscriptionsPerSession = 1000;

  private Duration remoteSessionIdleTimeout = Duration.ofMinutes(5);

  private String eventChannel = "simplepoint:mcp:gateway:events";

  private String publicationIssuerUri = "http://localhost:9000";

  private String publicationJwkSetUri = "http://authorization:9000/oauth2/jwks";

  private String oauthClientMetadataDocumentUri;

  private String oauthClientName = "Open SimplePoint MCP Gateway";

  private List<String> oauthClientRedirectUris = new ArrayList<>();

  private String oauthClientUri;

  private String oauthClientLogoUri;

  private String internalHeaderName = "X-SimplePoint-MCP-Gateway-Token";

  private String internalToken;

  private boolean runtimeMtlsEnabled;

  private String runtimeMtlsKeyStore;

  private String runtimeMtlsKeyStorePassword;

  private String runtimeMtlsTrustStore;

  private String runtimeMtlsTrustStorePassword;

  private String runtimeMtlsIdentity = "spiffe://open-simplepoint/mcp-gateway";

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(final Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getInitializationTimeout() {
    return initializationTimeout;
  }

  public void setInitializationTimeout(final Duration initializationTimeout) {
    this.initializationTimeout = initializationTimeout;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(final Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public long getMaxArgumentsBytes() {
    return maxArgumentsBytes;
  }

  public void setMaxArgumentsBytes(final long maxArgumentsBytes) {
    this.maxArgumentsBytes = maxArgumentsBytes;
  }

  public long getMaxResultBytes() {
    return maxResultBytes;
  }

  public void setMaxResultBytes(final long maxResultBytes) {
    this.maxResultBytes = maxResultBytes;
  }

  public long getMaxOauthResponseBytes() {
    return maxOauthResponseBytes;
  }

  public void setMaxOauthResponseBytes(final long maxOauthResponseBytes) {
    this.maxOauthResponseBytes = maxOauthResponseBytes;
  }

  public int getMaxProtocolRequestBytes() {
    return maxProtocolRequestBytes;
  }

  public void setMaxProtocolRequestBytes(final int maxProtocolRequestBytes) {
    this.maxProtocolRequestBytes = maxProtocolRequestBytes;
  }

  public boolean isAllowInsecureOauthEndpoints() {
    return allowInsecureOauthEndpoints;
  }

  public void setAllowInsecureOauthEndpoints(final boolean allowInsecureOauthEndpoints) {
    this.allowInsecureOauthEndpoints = allowInsecureOauthEndpoints;
  }

  public String getControlPlaneBaseUrl() {
    return controlPlaneBaseUrl;
  }

  public void setControlPlaneBaseUrl(final String controlPlaneBaseUrl) {
    this.controlPlaneBaseUrl = controlPlaneBaseUrl;
  }

  public Duration getPublicationManifestTtl() {
    return publicationManifestTtl;
  }

  public void setPublicationManifestTtl(final Duration publicationManifestTtl) {
    this.publicationManifestTtl = publicationManifestTtl;
  }

  public int getMaxRemoteSessions() {
    return maxRemoteSessions;
  }

  public void setMaxRemoteSessions(final int maxRemoteSessions) {
    this.maxRemoteSessions = maxRemoteSessions;
  }

  public int getMaxSubscriptionsPerSession() {
    return maxSubscriptionsPerSession;
  }

  public void setMaxSubscriptionsPerSession(final int maxSubscriptionsPerSession) {
    this.maxSubscriptionsPerSession = maxSubscriptionsPerSession;
  }

  public Duration getRemoteSessionIdleTimeout() {
    return remoteSessionIdleTimeout;
  }

  public void setRemoteSessionIdleTimeout(final Duration remoteSessionIdleTimeout) {
    this.remoteSessionIdleTimeout = remoteSessionIdleTimeout;
  }

  public String getEventChannel() {
    return eventChannel;
  }

  public void setEventChannel(final String eventChannel) {
    this.eventChannel = eventChannel;
  }

  public String getPublicationIssuerUri() {
    return publicationIssuerUri;
  }

  public void setPublicationIssuerUri(final String publicationIssuerUri) {
    this.publicationIssuerUri = publicationIssuerUri;
  }

  public String getPublicationJwkSetUri() {
    return publicationJwkSetUri;
  }

  public void setPublicationJwkSetUri(final String publicationJwkSetUri) {
    this.publicationJwkSetUri = publicationJwkSetUri;
  }

  public String getOauthClientMetadataDocumentUri() {
    return oauthClientMetadataDocumentUri;
  }

  public void setOauthClientMetadataDocumentUri(
      final String oauthClientMetadataDocumentUri
  ) {
    this.oauthClientMetadataDocumentUri = oauthClientMetadataDocumentUri;
  }

  public String getOauthClientName() {
    return oauthClientName;
  }

  public void setOauthClientName(final String oauthClientName) {
    this.oauthClientName = oauthClientName;
  }

  public List<String> getOauthClientRedirectUris() {
    return oauthClientRedirectUris;
  }

  public void setOauthClientRedirectUris(final List<String> oauthClientRedirectUris) {
    this.oauthClientRedirectUris = oauthClientRedirectUris == null
        ? new ArrayList<>() : new ArrayList<>(oauthClientRedirectUris);
  }

  public String getOauthClientUri() {
    return oauthClientUri;
  }

  public void setOauthClientUri(final String oauthClientUri) {
    this.oauthClientUri = oauthClientUri;
  }

  public String getOauthClientLogoUri() {
    return oauthClientLogoUri;
  }

  public void setOauthClientLogoUri(final String oauthClientLogoUri) {
    this.oauthClientLogoUri = oauthClientLogoUri;
  }

  public String getInternalHeaderName() {
    return internalHeaderName;
  }

  public void setInternalHeaderName(final String internalHeaderName) {
    this.internalHeaderName = internalHeaderName;
  }

  public String getInternalToken() {
    return internalToken;
  }

  public void setInternalToken(final String internalToken) {
    this.internalToken = internalToken;
  }

  public boolean isRuntimeMtlsEnabled() {
    return runtimeMtlsEnabled;
  }

  public void setRuntimeMtlsEnabled(final boolean runtimeMtlsEnabled) {
    this.runtimeMtlsEnabled = runtimeMtlsEnabled;
  }

  public String getRuntimeMtlsKeyStore() {
    return runtimeMtlsKeyStore;
  }

  public void setRuntimeMtlsKeyStore(final String runtimeMtlsKeyStore) {
    this.runtimeMtlsKeyStore = runtimeMtlsKeyStore;
  }

  public String getRuntimeMtlsKeyStorePassword() {
    return runtimeMtlsKeyStorePassword;
  }

  public void setRuntimeMtlsKeyStorePassword(
      final String runtimeMtlsKeyStorePassword
  ) {
    this.runtimeMtlsKeyStorePassword = runtimeMtlsKeyStorePassword;
  }

  public String getRuntimeMtlsTrustStore() {
    return runtimeMtlsTrustStore;
  }

  public void setRuntimeMtlsTrustStore(final String runtimeMtlsTrustStore) {
    this.runtimeMtlsTrustStore = runtimeMtlsTrustStore;
  }

  public String getRuntimeMtlsTrustStorePassword() {
    return runtimeMtlsTrustStorePassword;
  }

  public void setRuntimeMtlsTrustStorePassword(
      final String runtimeMtlsTrustStorePassword
  ) {
    this.runtimeMtlsTrustStorePassword = runtimeMtlsTrustStorePassword;
  }

  public String getRuntimeMtlsIdentity() {
    return runtimeMtlsIdentity;
  }

  public void setRuntimeMtlsIdentity(final String runtimeMtlsIdentity) {
    this.runtimeMtlsIdentity = runtimeMtlsIdentity;
  }
}

package org.simplepoint.cloud.oauth.server.client;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

/**
 * Authenticates RFC 8628 public clients at the device and token endpoints.
 *
 * <p>Spring's built-in public-client converter is intentionally PKCE-specific.
 * Device clients also cannot keep a secret, so they need the same
 * {@link ClientAuthenticationMethod#NONE} treatment for device-code requests.</p>
 */
public final class DevicePublicClientAuthenticationConverter
    implements AuthenticationConverter {

  static final String DEVICE_PUBLIC_CLIENT =
      DevicePublicClientAuthenticationConverter.class.getName()
          + ".DEVICE_PUBLIC_CLIENT";

  static final String REQUEST_GRANT_TYPE =
      DevicePublicClientAuthenticationConverter.class.getName()
          + ".REQUEST_GRANT_TYPE";

  private final String deviceAuthorizationEndpoint;

  private final String tokenEndpoint;

  /**
   * Creates the converter from the configured authorization-server endpoints.
   *
   * @param settings authorization-server endpoint settings
   */
  public DevicePublicClientAuthenticationConverter(
      final AuthorizationServerSettings settings
  ) {
    this.deviceAuthorizationEndpoint = settings.getDeviceAuthorizationEndpoint();
    this.tokenEndpoint = settings.getTokenEndpoint();
  }

  @Override
  public Authentication convert(final HttpServletRequest request) {
    if (!HttpMethod.POST.matches(request.getMethod())) {
      return null;
    }
    String requestGrantType = resolveRequestGrantType(request);
    if (requestGrantType == null) {
      return null;
    }
    String[] clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
    if (clientIds == null || clientIds.length != 1
        || !StringUtils.hasText(clientIds[0])) {
      throw new OAuth2AuthenticationException("invalid_request");
    }
    return new OAuth2ClientAuthenticationToken(
        clientIds[0],
        ClientAuthenticationMethod.NONE,
        null,
        Map.of(
            DEVICE_PUBLIC_CLIENT, Boolean.TRUE,
            REQUEST_GRANT_TYPE, requestGrantType
        )
    );
  }

  private String resolveRequestGrantType(final HttpServletRequest request) {
    String path = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
      path = path.substring(contextPath.length());
    }
    if (deviceAuthorizationEndpoint.equals(path)) {
      return AuthorizationGrantType.DEVICE_CODE.getValue();
    }
    if (!tokenEndpoint.equals(path)) {
      return null;
    }
    String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
    if (AuthorizationGrantType.DEVICE_CODE.getValue().equals(grantType)
        || AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(grantType)) {
      return grantType;
    }
    return null;
  }
}

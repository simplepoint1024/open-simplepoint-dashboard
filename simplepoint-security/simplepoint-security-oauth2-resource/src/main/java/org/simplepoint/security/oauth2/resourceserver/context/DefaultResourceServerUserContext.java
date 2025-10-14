package org.simplepoint.security.oauth2.resourceserver.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.GeneralException;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.core.oidc.OidcScopes;
import org.simplepoint.security.entity.User;
import org.simplepoint.security.oauth2.resourceserver.ResourceServerUserContext;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * DefaultResourceServerUserContext 是一个实现 ResourceServerUserContext 接口的类，
 * 用于提供 OAuth 2.0 资源服务器中的用户信息上下文
 *
 * <p>This class implements the UserContext interface,
 * providing user information context in an OAuth 2.0 resource server.
 */
public class DefaultResourceServerUserContext implements ResourceServerUserContext<BaseUser> {

  /**
   * JSON 解析器，用于处理用户信息数据
   * ObjectMapper used for JSON processing of user information.
   */
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * OAuth 2.0 资源服务器的配置属性
   * Properties for configuring the OAuth 2.0 resource server.
   */
  protected final OAuth2ResourceServerProperties resourceServerProperties;

  /**
   * OpenID Connect (OIDC) 提供者的元数据信息
   * Metadata information about the OpenID Connect (OIDC) provider.
   */
  protected final OIDCProviderMetadata providerMetadata;

  /**
   * 构造函数，初始化资源服务器属性和 OIDC 提供者元数据
   * Constructor initializing the resource server properties and OIDC provider metadata.
   *
   * @param resourceServerProperties OAuth2 资源服务器属性
   * @throws GeneralException 一般性异常
   * @throws IOException      I/O 异常
   */
  public DefaultResourceServerUserContext(
      OAuth2ResourceServerProperties resourceServerProperties
  ) throws GeneralException, IOException {
    this.resourceServerProperties = resourceServerProperties;
    this.providerMetadata = OIDCProviderMetadata.resolve(
        Issuer.parse(resourceServerProperties.getJwt().getIssuerUri()));
  }

  /**
   * 获取当前身份验证的用户名
   * Retrieves the username of the current authentication.
   *
   * @return 用户名或 null
   */
  @Override
  public String getName() {
    var authentication = this.getAuthentication();
    return authentication != null ? authentication.getName() : null;
  }

  /**
   * 获取当前用户详细信息
   * Retrieves the details of the currently authenticated user.
   *
   * @return 用户信息或 null
   */
  @Override
  public User getDetails() {
    if (this.getAuthentication() instanceof JwtAuthenticationToken authenticationToken) {
      Map<String, Object> userInfo =
          getUserInfo(new BearerAccessToken(authenticationToken.getToken().getTokenValue()));
      return this.createUser(userInfo);
    }
    return null;
  }

  /**
   * 获取当前的身份验证对象
   * Retrieves the current authentication object.
   *
   * @return 当前身份验证对象
   */
  @Override
  public Authentication getAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }

  /**
   * 通过访问令牌获取用户信息
   * Retrieves user information using the access token.
   *
   * @param accessToken 访问令牌
   * @return 用户信息 Map
   */
  protected Map<String, Object> getUserInfo(BearerAccessToken accessToken) {
    HttpRequest request = HttpRequest.newBuilder(providerMetadata.getUserInfoEndpointURI())
        .POST(HttpRequest.BodyPublishers.noBody())
        .header(HttpHeaders.AUTHORIZATION, new BearerAccessToken(
            accessToken.getValue()).toAuthorizationHeader())
        .build();

    HttpClient httpClient = HttpClient.newHttpClient();
    try {
      HttpResponse<String> send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return objectMapper.readValue(send.body(), new TypeReference<HashMap<String, Object>>() {
      });
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 通过用户信息创建 User 对象
   * Creates a User object using the given user information.
   *
   * @param userInfo 用户信息
   * @return 创建的 User 对象
   */
  protected User createUser(Map<String, Object> userInfo) {
    var user = new User();
    user.setUsername((String) userInfo.get(StandardClaimNames.SUB));
    user.setName((String) userInfo.get(StandardClaimNames.NAME));
    user.setPicture((String) userInfo.get(StandardClaimNames.PICTURE));
    user.setGender((String) userInfo.get(StandardClaimNames.GENDER));
    user.setLocale((String) userInfo.get(StandardClaimNames.LOCALE));
    user.setZoneinfo((String) userInfo.get(StandardClaimNames.ZONEINFO));
    user.setMiddleName((String) userInfo.get(StandardClaimNames.MIDDLE_NAME));
    user.setNickname((String) userInfo.get(StandardClaimNames.NICKNAME));
    user.setGivenName((String) userInfo.get(StandardClaimNames.GIVEN_NAME));
    user.setEmail((String) userInfo.get(StandardClaimNames.EMAIL));
    user.setPhoneNumber((String) userInfo.get(StandardClaimNames.PHONE_NUMBER));
    user.setAddress((String) userInfo.get(StandardClaimNames.ADDRESS));
    user.setSuperAdmin((Boolean) userInfo.get("super_admin"));

    Object roles = userInfo.get(OidcScopes.ROLES);
    if (roles instanceof Collection<?> collection) {
      List<SimpleGrantedAuthority> authorities = new ArrayList<>();
      collection.forEach(
          role -> authorities.add(new SimpleGrantedAuthority(String.valueOf(role))));
      user.setAuthorities(authorities);
    }
    return user;
  }
}

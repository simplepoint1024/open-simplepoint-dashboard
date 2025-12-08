package org.simplepoint.plugin.oidc.service.repository.impl;

import static org.simplepoint.plugin.oidc.service.SecurityJacksonParse.parseMap;
import static org.simplepoint.plugin.oidc.service.SecurityJacksonParse.writeMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.oidc.api.entity.Client;
import org.simplepoint.plugin.oidc.service.repository.JpaClientRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Implementation of {@link RegisteredClientRepository} for managing OAuth2 registered clients.
 *
 * <p>This class provides methods to save and retrieve registered clients using a JPA repository.
 * It handles the conversion between {@link RegisteredClient} objects and the underlying
 * {@link Client} entity used for persistence.</p>
 */
@Component
public class RegisteredClientRepositoryImpl implements RegisteredClientRepository {

  /**
   * Repository for storing OAuth2 client information.
   */
  private final JpaClientRepository clientRepository;

  private final PasswordEncoder passwordEncoder;


  /**
   * Constructs a new RegisteredClientRepositoryImpl with the provided client repository.
   *
   * @param clientRepository the repository for storing OAuth2 client information
   * @param passwordEncoder  the password encoder for encoding client secrets
   */
  public RegisteredClientRepositoryImpl(
      final JpaClientRepository clientRepository,
      final PasswordEncoder passwordEncoder
  ) {
    this.passwordEncoder = passwordEncoder;
    Assert.notNull(clientRepository, "clientRepository cannot be null");
    this.clientRepository = clientRepository;
  }

  /**
   * Saves the provided registered client to the repository.
   *
   * @param registeredClient the registered client to save
   * @throws IllegalArgumentException if {@code registeredClient} is null
   */
  @Override
  public void save(RegisteredClient registeredClient) {
    Assert.notNull(registeredClient, "registeredClient cannot be null");
    this.clientRepository.save(toEntity(registeredClient));
  }

  /**
   * Finds a registered client by its ID.
   *
   * @param id the registered client ID
   * @return the found {@link RegisteredClient}, or {@code null} if not found
   * @throws IllegalArgumentException if {@code id} is empty
   */
  @Override
  public RegisteredClient findById(String id) {
    Assert.hasText(id, "id cannot be empty");
    return this.clientRepository.findById(id).map(this::toObject).orElse(null);
  }

  /**
   * Finds a registered client by its client ID.
   *
   * @param clientId the client identifier
   * @return the found {@link RegisteredClient}, or {@code null} if not found
   * @throws IllegalArgumentException if {@code clientId} is empty
   */
  @Override
  public RegisteredClient findByClientId(String clientId) {
    Assert.hasText(clientId, "clientId cannot be empty");
    return this.clientRepository.findByClientId(clientId).map(this::toObject).orElse(null);
  }


  /**
   * Converts a {@code Client} entity to a {@link RegisteredClient} instance.
   *
   * @param client the entity containing OAuth2 client information
   * @return the converted {@link RegisteredClient} object
   */
  private RegisteredClient toObject(Client client) {
    Set<String> clientAuthenticationMethods = StringUtils.commaDelimitedListToSet(
        client.getClientAuthenticationMethods());
    Set<String> authorizationGrantTypes = StringUtils.commaDelimitedListToSet(
        client.getAuthorizationGrantTypes());
    Set<String> redirectUris = StringUtils.commaDelimitedListToSet(
        client.getRedirectUris());
    Set<String> postLogoutRedirectUris = StringUtils.commaDelimitedListToSet(
        client.getPostLogoutRedirectUris());
    Set<String> clientScopes = StringUtils.commaDelimitedListToSet(
        client.getScopes());

    RegisteredClient.Builder builder = RegisteredClient.withId(client.getId())
        .clientId(client.getClientId())
        .clientIdIssuedAt(client.getClientIdIssuedAt())
        .clientSecret(client.getClientSecret())
        .clientSecretExpiresAt(client.getClientSecretExpiresAt())
        .clientName(client.getClientName())
        .clientAuthenticationMethods(authenticationMethods ->
            clientAuthenticationMethods.forEach(authenticationMethod ->
                authenticationMethods.add(resolveClientAuthenticationMethod(authenticationMethod))))
        .authorizationGrantTypes((grantTypes) ->
            authorizationGrantTypes.forEach(grantType ->
                grantTypes.add(resolveAuthorizationGrantType(grantType))))
        .redirectUris((uris) -> uris.addAll(redirectUris))
        .postLogoutRedirectUris((uris) -> uris.addAll(postLogoutRedirectUris))
        .scopes((scopes) -> scopes.addAll(clientScopes));

    Map<String, Object> clientSettingsMap = parseMap(client.getClientSettings());
    builder.clientSettings(ClientSettings.withSettings(clientSettingsMap).build());

    Map<String, Object> tokenSettingsMap = parseMap(client.getTokenSettings());
    builder.tokenSettings(TokenSettings.withSettings(tokenSettingsMap).build());

    return builder.build();
  }

  private Client toEntity(RegisteredClient registeredClient) {
    List<String> clientAuthenticationMethods =
        new ArrayList<>(registeredClient.getClientAuthenticationMethods().size());
    registeredClient.getClientAuthenticationMethods().forEach(clientAuthenticationMethod ->
        clientAuthenticationMethods.add(clientAuthenticationMethod.getValue()));

    List<String> authorizationGrantTypes =
        new ArrayList<>(registeredClient.getAuthorizationGrantTypes().size());
    registeredClient.getAuthorizationGrantTypes().forEach(authorizationGrantType ->
        authorizationGrantTypes.add(authorizationGrantType.getValue()));

    Client entity = new Client();
    entity.setClientId(registeredClient.getClientId());
    entity.setClientIdIssuedAt(registeredClient.getClientIdIssuedAt());
    String clientSecret = registeredClient.getClientSecret();

    if (clientSecret != null) {
      entity.setClientSecret(
          clientSecret.matches("\\A\\$2([ayb])?\\$(\\d\\d)\\$[./0-9A-Za-z]{53}")
              ? clientSecret
              : passwordEncoder.encode(clientSecret)
      );
    }
    entity.setClientSecretExpiresAt(registeredClient.getClientSecretExpiresAt());
    entity.setClientName(registeredClient.getClientName());
    entity.setClientAuthenticationMethods(
        StringUtils.collectionToCommaDelimitedString(clientAuthenticationMethods));
    entity.setAuthorizationGrantTypes(
        StringUtils.collectionToCommaDelimitedString(authorizationGrantTypes));
    entity.setRedirectUris(
        StringUtils.collectionToCommaDelimitedString(registeredClient.getRedirectUris()));
    entity.setPostLogoutRedirectUris(
        StringUtils.collectionToCommaDelimitedString(registeredClient.getPostLogoutRedirectUris()));
    entity.setScopes(StringUtils.collectionToCommaDelimitedString(registeredClient.getScopes()));
    entity.setClientSettings(writeMap(registeredClient.getClientSettings().getSettings()));
    entity.setTokenSettings(writeMap(registeredClient.getTokenSettings().getSettings()));

    return entity;
  }

  /**
   * Resolves a given string value to an {@link AuthorizationGrantType}.
   * This method maps standard OAuth2 authorization grant types such as
   * "authorization_code", "client_credentials", and "refresh_token".
   * If an unknown grant type is provided, a custom {@code AuthorizationGrantType}
   * is returned with the given string value.
   *
   * @param authorizationGrantType the string representing the authorization grant type
   * @return the corresponding {@link AuthorizationGrantType} object
   */
  private static AuthorizationGrantType resolveAuthorizationGrantType(
      String authorizationGrantType) {
    if (AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(authorizationGrantType)) {
      return AuthorizationGrantType.AUTHORIZATION_CODE;
    } else if (AuthorizationGrantType.CLIENT_CREDENTIALS.getValue()
        .equals(authorizationGrantType)) {
      return AuthorizationGrantType.CLIENT_CREDENTIALS;
    } else if (AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(authorizationGrantType)) {
      return AuthorizationGrantType.REFRESH_TOKEN;
    }
    return new AuthorizationGrantType(
        authorizationGrantType);
  }

  /**
   * Resolves a given string value to a {@link ClientAuthenticationMethod}.
   * This method maps standard OAuth2 client authentication methods such as
   * "client_secret_basic", "client_secret_post", and "none". If an unknown
   * authentication method is provided, a custom {@code ClientAuthenticationMethod}
   * is returned with the given string value.
   *
   * @param clientAuthenticationMethod the string representing the authentication method
   * @return the corresponding {@link ClientAuthenticationMethod} object
   */
  private static ClientAuthenticationMethod resolveClientAuthenticationMethod(
      String clientAuthenticationMethod) {
    if (ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue()
        .equals(clientAuthenticationMethod)) {
      return ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
    } else if (ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue()
        .equals(clientAuthenticationMethod)) {
      return ClientAuthenticationMethod.CLIENT_SECRET_POST;
    } else if (ClientAuthenticationMethod.NONE.getValue().equals(clientAuthenticationMethod)) {
      return ClientAuthenticationMethod.NONE;
    }
    return new ClientAuthenticationMethod(
        clientAuthenticationMethod);
  }
}

package org.simplepoint.plugin.oidc.service.service.impl;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityMatchStrategy;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderConnectionTestResult;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderPreset;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderProtocol;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityProviderView;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.repository.ExternalIdentityLinkRepository;
import org.simplepoint.plugin.oidc.api.repository.ExternalIdentityProviderRepository;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.simplepoint.plugin.oidc.service.security.ExternalIdentityProviderCredentialCipher;
import org.simplepoint.plugin.oidc.service.security.ExternalIdentityProviderUrlValidator;
import org.simplepoint.plugin.oidc.service.support.ExternalIdentityClientRegistrationFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default service for encrypted, database-managed external identity providers. */
@Service
public class ExternalIdentityProviderServiceImpl
    extends BaseServiceImpl<
        ExternalIdentityProviderRepository,
        ExternalIdentityProvider,
        String
    >
    implements ExternalIdentityProviderService {

  private static final Pattern REGISTRATION_ID =
      Pattern.compile("[a-z0-9][a-z0-9._-]{1,63}");

  private final ExternalIdentityProviderRepository repository;

  private final ExternalIdentityLinkRepository linkRepository;

  private final ExternalIdentityProviderCredentialCipher credentialCipher;

  private final ExternalIdentityClientRegistrationFactory registrationFactory;

  private final Environment environment;

  /**
   * Creates the provider service.
   */
  public ExternalIdentityProviderServiceImpl(
      final ExternalIdentityProviderRepository repository,
      final DetailsProviderService detailsProviderService,
      final ExternalIdentityLinkRepository linkRepository,
      final ExternalIdentityProviderCredentialCipher credentialCipher,
      final ExternalIdentityClientRegistrationFactory registrationFactory,
      final Environment environment
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.linkRepository = linkRepository;
    this.credentialCipher = credentialCipher;
    this.registrationFactory = registrationFactory;
    this.environment = environment;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  public <S extends ExternalIdentityProvider> Page<S> limit(
      final Map<String, String> attributes,
      final Pageable pageable
  ) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    Page<S> result = super.limit(normalized, pageable);
    result.getContent().forEach(this::decorate);
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends ExternalIdentityProvider> S create(final S entity) {
    applyPreset(entity, true);
    normalizeAndValidate(entity, null, true);
    String ciphertext = credentialCipher.encrypt(entity.getClientSecret());
    entity.setClientSecret(null);
    entity.setClientSecretCiphertext(ciphertext);
    S saved = super.create(entity);
    if (!Objects.equals(ciphertext, saved.getClientSecretCiphertext())) {
      saved.setClientSecretCiphertext(ciphertext);
      saved = repository.save(saved);
    }
    decorate(saved);
    return saved;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends ExternalIdentityProvider> ExternalIdentityProvider modifyById(
      final S entity
  ) {
    if (entity == null || trimToNull(entity.getId()) == null) {
      throw new IllegalArgumentException("外部身份提供商 ID 不能为空");
    }
    ExternalIdentityProvider current = repository.findActiveById(entity.getId())
        .orElseThrow(() -> new NoSuchElementException("外部身份提供商不存在"));
    entity.setRegistrationId(current.getRegistrationId());
    applyPreset(entity, entity.getPreset() != current.getPreset());
    normalizeAndValidate(entity, current.getId(), false);

    String ciphertext = current.getClientSecretCiphertext();
    if (trimToNull(entity.getClientSecret()) != null) {
      ciphertext = credentialCipher.encrypt(entity.getClientSecret().trim());
    }
    entity.setClientSecret(null);
    entity.setClientSecretCiphertext(current.getClientSecretCiphertext());
    ExternalIdentityProvider updated = super.modifyById(entity);
    if (!Objects.equals(ciphertext, updated.getClientSecretCiphertext())) {
      updated.setClientSecretCiphertext(ciphertext);
      updated = repository.save(updated);
    }
    decorate(updated);
    return updated;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(final Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    for (String id : ids) {
      ExternalIdentityProvider provider = repository.findActiveById(id)
          .orElseThrow(() -> new NoSuchElementException("外部身份提供商不存在: " + id));
      if (linkRepository.existsActiveByProviderId(provider.getId())) {
        throw new IllegalStateException(
            "该身份提供商已有账号绑定，请先停用而不是删除: " + provider.getDisplayName()
        );
      }
    }
    super.removeByIds(ids);
  }

  @Override
  public List<ExternalIdentityProviderView> enabledProviders() {
    return repository.findAllActiveEnabled().stream()
        .map(provider -> new ExternalIdentityProviderView(
            provider.getRegistrationId(),
            provider.getDisplayName(),
            provider.getPreset(),
            provider.getSortOrder()
        ))
        .toList();
  }

  @Override
  public ResolvedExternalIdentityProvider resolve(final String registrationId) {
    ExternalIdentityProvider provider = repository.findActiveByRegistrationId(registrationId)
        .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
        .orElseThrow(() -> new NoSuchElementException(
            "外部身份提供商未启用或不存在: " + registrationId
        ));
    return toResolved(provider);
  }

  @Override
  public ExternalIdentityProviderConnectionTestResult testConnection(final String id) {
    ExternalIdentityProvider provider = repository.findActiveById(id)
        .orElseThrow(() -> new NoSuchElementException("外部身份提供商不存在: " + id));
    ClientRegistration registration = registrationFactory.build(toResolved(provider));
    ClientRegistration.ProviderDetails details = registration.getProviderDetails();
    return new ExternalIdentityProviderConnectionTestResult(
        true,
        "配置有效，身份提供商元数据可访问",
        details.getAuthorizationUri(),
        details.getTokenUri(),
        details.getUserInfoEndpoint().getUri(),
        details.getJwkSetUri(),
        callbackUri(provider.getRegistrationId())
    );
  }

  private String callbackUri(final String registrationId) {
    String issuer = environment.getProperty("server.oauth2.issuer-uri");
    if (trimToNull(issuer) == null) {
      issuer = environment.getProperty("spring.security.oauth2.authorizationserver.issuer");
    }
    String baseUrl = trimToNull(issuer);
    if (baseUrl == null) {
      return "/login/oauth2/code/" + registrationId;
    }
    return baseUrl.replaceAll("/+$", "") + "/login/oauth2/code/" + registrationId;
  }

  private ResolvedExternalIdentityProvider toResolved(
      final ExternalIdentityProvider provider
  ) {
    return new ResolvedExternalIdentityProvider(
        provider.getId(),
        provider.getRegistrationId(),
        provider.getDisplayName(),
        provider.getPreset(),
        provider.getProtocol(),
        provider.getIssuerUri(),
        provider.getAuthorizationUri(),
        provider.getTokenUri(),
        provider.getUserInfoUri(),
        provider.getJwkSetUri(),
        provider.getClientId(),
        credentialCipher.decrypt(provider.getClientSecretCiphertext()),
        provider.getClientAuthenticationMethod(),
        parseCsv(provider.getScopes()),
        provider.getUserNameAttribute(),
        provider.getSubjectClaim(),
        provider.getEmailClaim(),
        provider.getEmailVerifiedClaim(),
        provider.getMatchStrategy(),
        Boolean.TRUE.equals(provider.getRequireVerifiedEmail()),
        Boolean.TRUE.equals(provider.getAllowPrivateNetwork()),
        provider.getUpdatedAt()
    );
  }

  private void normalizeAndValidate(
      final ExternalIdentityProvider provider,
      final String currentId,
      final boolean creating
  ) {
    if (provider == null) {
      throw new IllegalArgumentException("外部身份提供商配置不能为空");
    }
    provider.setRegistrationId(
        require(provider.getRegistrationId(), "注册标识不能为空").toLowerCase(Locale.ROOT)
    );
    if (!REGISTRATION_ID.matcher(provider.getRegistrationId()).matches()) {
      throw new IllegalArgumentException(
          "注册标识只能包含小写字母、数字、点、下划线和短横线"
      );
    }
    repository.findActiveByRegistrationId(provider.getRegistrationId())
        .filter(existing -> !Objects.equals(existing.getId(), currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException(
              "注册标识已存在: " + provider.getRegistrationId()
          );
        });
    provider.setDisplayName(require(provider.getDisplayName(), "显示名称不能为空"));
    provider.setPreset(
        provider.getPreset() == null ? ExternalIdentityProviderPreset.CUSTOM : provider.getPreset()
    );
    if (provider.getProtocol() == null) {
      throw new IllegalArgumentException("协议类型不能为空");
    }
    provider.setClientId(require(provider.getClientId(), "Client ID 不能为空"));
    provider.setClientAuthenticationMethod(defaultString(
        provider.getClientAuthenticationMethod(),
        ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue()
    ));
    boolean publicClient = ClientAuthenticationMethod.NONE.getValue()
        .equals(provider.getClientAuthenticationMethod());
    if (creating && !publicClient && trimToNull(provider.getClientSecret()) == null) {
      throw new IllegalArgumentException("Client Secret 不能为空");
    }
    provider.setScopes(toCsv(parseCsv(provider.getScopes())));
    if (provider.getScopes().isBlank()) {
      throw new IllegalArgumentException("授权范围不能为空");
    }
    provider.setUserNameAttribute(defaultString(
        provider.getUserNameAttribute(),
        provider.getProtocol() == ExternalIdentityProviderProtocol.OIDC ? "sub" : "id"
    ));
    provider.setSubjectClaim(defaultString(
        provider.getSubjectClaim(),
        provider.getUserNameAttribute()
    ));
    provider.setEmailClaim(defaultString(provider.getEmailClaim(), "email"));
    provider.setEmailVerifiedClaim(trimToNull(provider.getEmailVerifiedClaim()));
    provider.setMatchStrategy(
        provider.getMatchStrategy() == null
            ? ExternalIdentityMatchStrategy.VERIFIED_EMAIL
            : provider.getMatchStrategy()
    );
    provider.setRequireVerifiedEmail(
        provider.getRequireVerifiedEmail() == null
            ? Boolean.TRUE
            : provider.getRequireVerifiedEmail()
    );
    provider.setAllowPrivateNetwork(
        provider.getAllowPrivateNetwork() == null
            ? Boolean.FALSE
            : provider.getAllowPrivateNetwork()
    );
    provider.setSortOrder(provider.getSortOrder() == null ? 100 : provider.getSortOrder());
    provider.setEnabled(provider.getEnabled() == null ? Boolean.TRUE : provider.getEnabled());
    provider.setDescription(trimToNull(provider.getDescription()));

    boolean allowPrivate = Boolean.TRUE.equals(provider.getAllowPrivateNetwork());
    provider.setIssuerUri(normalizeUrl(provider.getIssuerUri(), "Issuer URI", allowPrivate));
    provider.setAuthorizationUri(normalizeUrl(
        provider.getAuthorizationUri(), "Authorization URI", allowPrivate
    ));
    provider.setTokenUri(normalizeUrl(provider.getTokenUri(), "Token URI", allowPrivate));
    provider.setUserInfoUri(normalizeUrl(
        provider.getUserInfoUri(), "UserInfo URI", allowPrivate
    ));
    provider.setJwkSetUri(normalizeUrl(provider.getJwkSetUri(), "JWK Set URI", allowPrivate));

    if (provider.getProtocol() == ExternalIdentityProviderProtocol.OIDC
        && provider.getIssuerUri() == null) {
      throw new IllegalArgumentException("OIDC 身份提供商必须配置 Issuer URI");
    }
    if (provider.getProtocol() == ExternalIdentityProviderProtocol.OAUTH2
        && (provider.getAuthorizationUri() == null
        || provider.getTokenUri() == null
        || provider.getUserInfoUri() == null)) {
      throw new IllegalArgumentException(
          "OAuth2 身份提供商必须配置 Authorization、Token 和 UserInfo URI"
      );
    }
  }

  private void applyPreset(
      final ExternalIdentityProvider provider,
      final boolean force
  ) {
    if (provider == null || provider.getPreset() == null
        || provider.getPreset() == ExternalIdentityProviderPreset.CUSTOM) {
      return;
    }
    switch (provider.getPreset()) {
      case GOOGLE -> applyOidcPreset(
          provider, force, "Google", "https://accounts.google.com",
          "openid,profile,email", ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue()
      );
      case APPLE -> applyOidcPreset(
          provider, force, "Apple", "https://appleid.apple.com",
          "openid,name,email", ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue()
      );
      case MICROSOFT -> applyOidcPreset(
          provider, force, "Microsoft",
          "https://login.microsoftonline.com/common/v2.0",
          "openid,profile,email", ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue()
      );
      case GITHUB -> {
        setIfBlank(provider::setDisplayName, provider.getDisplayName(), "GitHub", false);
        provider.setProtocol(ExternalIdentityProviderProtocol.OAUTH2);
        setIfBlank(provider::setAuthorizationUri, provider.getAuthorizationUri(),
            "https://github.com/login/oauth/authorize", force);
        setIfBlank(provider::setTokenUri, provider.getTokenUri(),
            "https://github.com/login/oauth/access_token", force);
        setIfBlank(provider::setUserInfoUri, provider.getUserInfoUri(),
            "https://api.github.com/user", force);
        setIfBlank(provider::setScopes, provider.getScopes(), "read:user,user:email", force);
        setIfBlank(provider::setClientAuthenticationMethod,
            provider.getClientAuthenticationMethod(),
            ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue(), force);
        setIfBlank(provider::setUserNameAttribute, provider.getUserNameAttribute(), "id", force);
        setIfBlank(provider::setSubjectClaim, provider.getSubjectClaim(), "id", force);
        setIfBlank(provider::setEmailClaim, provider.getEmailClaim(), "email", force);
        setIfBlank(provider::setEmailVerifiedClaim,
            provider.getEmailVerifiedClaim(), "email_verified", force);
        provider.setRequireVerifiedEmail(true);
      }
      default -> {
        // Custom providers intentionally have no imposed defaults.
      }
    }
  }

  private static void applyOidcPreset(
      final ExternalIdentityProvider provider,
      final boolean force,
      final String displayName,
      final String issuerUri,
      final String scopes,
      final String authenticationMethod
  ) {
    setIfBlank(provider::setDisplayName, provider.getDisplayName(), displayName, false);
    provider.setProtocol(ExternalIdentityProviderProtocol.OIDC);
    setIfBlank(provider::setIssuerUri, provider.getIssuerUri(), issuerUri, force);
    setIfBlank(provider::setScopes, provider.getScopes(), scopes, force);
    setIfBlank(provider::setClientAuthenticationMethod,
        provider.getClientAuthenticationMethod(), authenticationMethod, force);
    setIfBlank(provider::setUserNameAttribute,
        provider.getUserNameAttribute(), "sub", force);
    setIfBlank(provider::setSubjectClaim, provider.getSubjectClaim(), "sub", force);
    setIfBlank(provider::setEmailClaim, provider.getEmailClaim(), "email", force);
    setIfBlank(provider::setEmailVerifiedClaim,
        provider.getEmailVerifiedClaim(), "email_verified", force);
    provider.setRequireVerifiedEmail(true);
  }

  private static void setIfBlank(
      final java.util.function.Consumer<String> setter,
      final String current,
      final String defaultValue,
      final boolean force
  ) {
    if (force || trimToNull(current) == null) {
      setter.accept(defaultValue);
    }
  }

  private static String normalizeUrl(
      final String value,
      final String label,
      final boolean allowPrivate
  ) {
    String normalized = trimToNull(value);
    ExternalIdentityProviderUrlValidator.validate(
        normalized,
        label,
        allowPrivate,
        false
    );
    return normalized;
  }

  private static Set<String> parseCsv(final String value) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (value == null) {
      return result;
    }
    for (String item : value.split("[,\\s]+")) {
      String normalized = trimToNull(item);
      if (normalized != null) {
        result.add(normalized);
      }
    }
    return result;
  }

  private static String toCsv(final Set<String> values) {
    return String.join(",", values);
  }

  private static String require(final String value, final String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String defaultString(final String value, final String fallback) {
    String normalized = trimToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private void decorate(final ExternalIdentityProvider provider) {
    provider.setClientSecret(null);
    provider.setClientSecretConfigured(
        trimToNull(provider.getClientSecretCiphertext()) != null
    );
  }
}

package org.simplepoint.cloud.oauth.server.client;

import java.time.Instant;
import java.util.Map;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityLink;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityMatchStrategy;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.repository.ExternalIdentityLinkRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Safely binds stable external subjects to existing local accounts. */
@Component
public class ExternalIdentityAccountLinker {

  private static final String ERROR_CODE = "external_account_not_linked";

  private final ExternalIdentityLinkRepository linkRepository;

  private final UsersService usersService;

  /**
   * Creates the account linker.
   */
  public ExternalIdentityAccountLinker(
      final ExternalIdentityLinkRepository linkRepository,
      final UsersService usersService
  ) {
    this.linkRepository = linkRepository;
    this.usersService = usersService;
  }

  /**
   * Resolves or creates a durable provider-subject binding.
   *
   * @param provider external provider
   * @param attributes trusted attributes returned by the provider
   * @return enabled local user
   */
  @Transactional(rollbackFor = Exception.class)
  public User link(
      final ResolvedExternalIdentityProvider provider,
      final Map<String, Object> attributes
  ) {
    String subject = attribute(attributes, provider.subjectClaim());
    if (subject == null) {
      throw failure("身份提供商未返回稳定的 subject");
    }
    ExternalIdentityLink existing = linkRepository
        .findActiveByProviderAndSubject(provider.id(), subject)
        .orElse(null);
    if (existing != null) {
      User user = loadById(existing.getUserId());
      existing.setLastLoginAt(Instant.now());
      linkRepository.save(existing);
      return requireUsable(user);
    }

    User matched = matchLocalUser(provider, attributes);
    ExternalIdentityLink link = new ExternalIdentityLink();
    link.setProviderId(provider.id());
    link.setExternalSubject(subject);
    link.setUserId(matched.getId());
    link.setEmailAtLink(attribute(attributes, provider.emailClaim()));
    link.setLastLoginAt(Instant.now());
    try {
      linkRepository.save(link);
    } catch (DataIntegrityViolationException ex) {
      ExternalIdentityLink concurrent = linkRepository
          .findActiveByProviderAndSubject(provider.id(), subject)
          .orElseThrow(() -> ex);
      matched = loadById(concurrent.getUserId());
    }
    return requireUsable(matched);
  }

  private User matchLocalUser(
      final ResolvedExternalIdentityProvider provider,
      final Map<String, Object> attributes
  ) {
    if (provider.matchStrategy() == ExternalIdentityMatchStrategy.USER_ID) {
      String userId = attribute(attributes, provider.subjectClaim());
      return loadById(userId);
    }

    String email = attribute(attributes, provider.emailClaim());
    if (email == null) {
      throw failure("外部账号没有可用于绑定的邮箱地址");
    }
    if (provider.requireVerifiedEmail()
        && !booleanAttribute(attributes, provider.emailVerifiedClaim())) {
      throw failure("外部账号邮箱尚未通过身份提供商验证");
    }
    try {
      return requireUsable((User) usersService.loadUserByUsername(email));
    } catch (UsernameNotFoundException ex) {
      throw failure("未找到与外部账号邮箱匹配的本地账号");
    }
  }

  private User loadById(final String userId) {
    if (userId == null || userId.isBlank()) {
      throw failure("外部账号没有可绑定的本地用户标识");
    }
    return usersService.findByIdForAuthorization(userId)
        .orElseThrow(() -> failure("外部账号绑定的本地账号不存在"));
  }

  private static User requireUsable(final User user) {
    if (user == null || !user.isEnabled() || !user.isAccountNonLocked()
        || !user.isAccountNonExpired() || !user.isCredentialsNonExpired()) {
      throw failure("本地账号已停用、锁定或过期");
    }
    return user;
  }

  private static String attribute(
      final Map<String, Object> attributes,
      final String name
  ) {
    if (attributes == null || name == null || name.isBlank()) {
      return null;
    }
    Object value = attributes.get(name);
    if (value == null) {
      return null;
    }
    String normalized = String.valueOf(value).trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static boolean booleanAttribute(
      final Map<String, Object> attributes,
      final String name
  ) {
    String value = attribute(attributes, name);
    return value != null
        && ("true".equalsIgnoreCase(value) || "1".equals(value));
  }

  private static OAuth2AuthenticationException failure(final String description) {
    return new OAuth2AuthenticationException(
        new OAuth2Error(ERROR_CODE, description, null),
        description
    );
  }
}

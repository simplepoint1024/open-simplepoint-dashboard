package org.simplepoint.cloud.oauth.server.client;

import java.util.Map;
import org.simplepoint.plugin.oidc.api.error.ExternalIdentityBindingException;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityBinding;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityMatchStrategy;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityAccountService;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

/** Safely binds stable external subjects to existing local accounts. */
@Component
public class ExternalIdentityAccountLinker {

  private static final String ERROR_CODE = "external_account_not_linked";

  private final ExternalIdentityAccountService accounts;

  private final UserManagementService usersService;

  /** Creates the account linker. */
  public ExternalIdentityAccountLinker(
      final ExternalIdentityAccountService accounts, final UserManagementService usersService) {
    this.accounts = accounts;
    this.usersService = usersService;
  }

  /**
   * Resolves or creates a durable provider-subject binding.
   *
   * @param provider external provider
   * @param attributes trusted attributes returned by the provider
   * @return enabled local user
   */
  public User link(
      final ResolvedExternalIdentityProvider provider, final Map<String, Object> attributes) {
    String subject = attribute(attributes, provider.subjectClaim());
    if (subject == null) {
      throw failure("身份提供商未返回稳定的 subject");
    }
    try {
      ExternalIdentityBinding existing = accounts.find(provider.id(), subject).orElse(null);
      if (existing != null) {
        User user = requireUsable(loadById(existing.userId()));
        accounts.recordLogin(existing);
        return user;
      }
      User matched = requireUsable(matchLocalUser(provider, attributes));
      return bindVerified(provider, subject, attribute(attributes, provider.emailClaim()), matched);
    } catch (ExternalIdentityBindingException ex) {
      throw bindingFailure(ex);
    }
  }

  /** Explicitly binds a subject after the browser flow has authenticated the target account. */
  public User linkToUser(
      final ResolvedExternalIdentityProvider provider,
      final String subject,
      final String email,
      final String userId) {
    String normalizedSubject = trimToNull(subject);
    if (normalizedSubject == null) {
      throw failure("身份提供商未返回稳定的 subject");
    }
    User target = requireUsable(loadById(userId));
    try {
      return bindVerified(provider, normalizedSubject, trimToNull(email), target);
    } catch (ExternalIdentityBindingException ex) {
      throw bindingFailure(ex);
    }
  }

  private User bindVerified(
      ResolvedExternalIdentityProvider provider, String subject, String email, User target) {
    var binding = accounts.bind(provider.id(), subject, email, target.getId());
    // Identity is authoritative. Separate stores do not pretend to share a transaction.
    User current = requireUsable(loadById(target.getId()));
    accounts.recordLogin(binding);
    return current;
  }

  private static OAuth2AuthenticationException bindingFailure(ExternalIdentityBindingException ex) {
    return failure(
        switch (ex.getReason()) {
          case SUBJECT_IN_USE -> "该外部账号已绑定到其他平台账号";
          case ACCOUNT_IN_USE -> "当前平台账号已绑定该身份提供商的其他账号";
          case PROVIDER_UNAVAILABLE -> "身份提供商已停用或不存在";
          case BINDING_REVOKED, NOT_LINKED -> "外部账号绑定已撤销，请重新登录";
          case INVALID_INPUT -> "外部账号绑定信息无效";
        });
  }

  private User matchLocalUser(
      final ResolvedExternalIdentityProvider provider, final Map<String, Object> attributes) {
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
    return usersService
        .findByIdForAuthorization(userId)
        .orElseThrow(() -> failure("外部账号绑定的本地账号不存在"));
  }

  private static User requireUsable(final User user) {
    if (user == null
        || user.getDeletedAt() != null
        || !user.isEnabled()
        || !user.isAccountNonLocked()
        || !user.isAccountNonExpired()
        || !user.isCredentialsNonExpired()) {
      throw failure("本地账号已停用、锁定或过期");
    }
    return user;
  }

  private static String attribute(final Map<String, Object> attributes, final String name) {
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

  private static boolean booleanAttribute(final Map<String, Object> attributes, final String name) {
    String value = attribute(attributes, name);
    return value != null && ("true".equalsIgnoreCase(value) || "1".equals(value));
  }

  static String attributeValue(final Map<String, Object> attributes, final String name) {
    return attribute(attributes, name);
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static OAuth2AuthenticationException failure(final String description) {
    return new OAuth2AuthenticationException(
        new OAuth2Error(ERROR_CODE, description, null), description);
  }
}

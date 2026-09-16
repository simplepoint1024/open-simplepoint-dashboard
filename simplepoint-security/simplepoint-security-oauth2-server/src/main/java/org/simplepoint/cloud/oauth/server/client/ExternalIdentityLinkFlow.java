package org.simplepoint.cloud.oauth.server.client;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.simplepoint.security.entity.User;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Component;

/** Maintains the short-lived, session-bound state needed for explicit account linking. */
@Component
public class ExternalIdentityLinkFlow {

  private static final String PENDING_ATTRIBUTE =
      ExternalIdentityLinkFlow.class.getName() + ".PENDING";

  private static final String RECENTLY_LINKED_ATTRIBUTE =
      ExternalIdentityLinkFlow.class.getName() + ".RECENTLY_LINKED";

  private static final Duration PENDING_TTL = Duration.ofMinutes(10);

  private final ExternalIdentityProviderService providerService;

  private final ExternalIdentityAccountLinker accountLinker;

  /**
   * Creates the external identity link flow.
   *
   * @param providerService external identity provider resolver
   * @param accountLinker external identity account linker
   */
  public ExternalIdentityLinkFlow(
      final ExternalIdentityProviderService providerService,
      final ExternalIdentityAccountLinker accountLinker
  ) {
    this.providerService = providerService;
    this.accountLinker = accountLinker;
  }

  /** Starts a link from the account-management page. */
  public void begin(
      final HttpSession session,
      final String userId,
      final ResolvedExternalIdentityProvider provider,
      final boolean gateway
  ) {
    session.setAttribute(PENDING_ATTRIBUTE, new PendingLink(
        provider.registrationId(), provider.displayName(), null, null,
        userId, true, gateway, Instant.now().plus(PENDING_TTL)
    ));
  }

  /** Resolves an external login, using an explicit target when this session requested one. */
  public User resolve(
      final HttpServletRequest request,
      final ResolvedExternalIdentityProvider provider,
      final Map<String, Object> attributes
  ) {
    HttpSession session = request.getSession();
    PendingLink pending = pending(session);
    String subject = ExternalIdentityAccountLinker.attributeValue(
        attributes, provider.subjectClaim()
    );
    String email = ExternalIdentityAccountLinker.attributeValue(
        attributes, provider.emailClaim()
    );
    if (pending != null && pending.explicit()
        && pending.registrationId().equals(provider.registrationId())) {
      User user = accountLinker.linkToUser(
          provider, subject, email, pending.targetUserId()
      );
      session.removeAttribute(PENDING_ATTRIBUTE);
      session.setAttribute(RECENTLY_LINKED_ATTRIBUTE, new RecentlyLinked(
          provider.registrationId(), pending.gateway(), true
      ));
      return user;
    }

    try {
      return accountLinker.link(provider, attributes);
    } catch (OAuth2AuthenticationException ex) {
      session.setAttribute(PENDING_ATTRIBUTE, new PendingLink(
          provider.registrationId(), provider.displayName(), subject, email,
          null, false, isGatewayRequest(request), Instant.now().plus(PENDING_TTL)
      ));
      throw ex;
    }
  }

  /** Completes a pending first-login link after the user proves local credentials. */
  public boolean completeAfterLocalLogin(
      final HttpSession session,
      final User user
  ) {
    PendingLink pending = pending(session);
    if (pending == null || pending.explicit() || pending.subject() == null) {
      return false;
    }
    ResolvedExternalIdentityProvider provider = providerService.resolve(
        pending.registrationId()
    );
    accountLinker.linkToUser(
        provider, pending.subject(), pending.email(), user.getId()
    );
    session.removeAttribute(PENDING_ATTRIBUTE);
    session.setAttribute(RECENTLY_LINKED_ATTRIBUTE, new RecentlyLinked(
        provider.registrationId(), false, false
    ));
    return true;
  }

  /**
   * Returns the valid pending link stored in the current session.
   *
   * @param session current HTTP session
   * @return pending link, or {@code null} when absent or expired
   */
  public PendingLink pending(final HttpSession session) {
    Object value = session.getAttribute(PENDING_ATTRIBUTE);
    if (!(value instanceof PendingLink pending) || pending.expired()) {
      session.removeAttribute(PENDING_ATTRIBUTE);
      return null;
    }
    return pending;
  }

  /**
   * Checks whether the current session contains an explicit account link request.
   *
   * @param session current HTTP session
   * @return {@code true} when an explicit account link is pending
   */
  public boolean hasExplicitLink(final HttpSession session) {
    PendingLink pending = pending(session);
    return pending != null && pending.explicit();
  }

  /**
   * Removes and returns the most recently completed external account link.
   *
   * @param session current HTTP session
   * @return completed link descriptor, or {@code null} when none is available
   */
  public RecentlyLinked consumeRecentlyLinked(final HttpSession session) {
    Object value = session.getAttribute(RECENTLY_LINKED_ATTRIBUTE);
    session.removeAttribute(RECENTLY_LINKED_ATTRIBUTE);
    return value instanceof RecentlyLinked linked ? linked : null;
  }

  /**
   * Clears the pending external account link from the current session.
   *
   * @param session current HTTP session
   */
  public void clear(final HttpSession session) {
    session.removeAttribute(PENDING_ATTRIBUTE);
  }

  private boolean isGatewayRequest(final HttpServletRequest request) {
    String forwardedPrefix = request.getHeader("X-Forwarded-Prefix");
    return (forwardedPrefix != null && forwardedPrefix.contains("/authorization"))
        || request.getServerPort() == 8080;
  }

  /** Serializable because production sessions may be persisted in Redis. */
  public record PendingLink(
      String registrationId,
      String providerName,
      String subject,
      String email,
      String targetUserId,
      boolean explicit,
      boolean gateway,
      Instant expiresAt
  ) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Checks whether the pending link has reached its expiry time.
     *
     * @return {@code true} when the link is expired or has no expiry time
     */
    public boolean expired() {
      return expiresAt == null || !expiresAt.isAfter(Instant.now());
    }
  }

  /** Describes the redirect needed after a successful callback. */
  public record RecentlyLinked(
      String registrationId,
      boolean gateway,
      boolean returnToSettings
  ) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
  }
}

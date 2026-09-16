package org.simplepoint.cloud.oauth.server.controller;

import jakarta.servlet.http.HttpSession;
import org.simplepoint.cloud.oauth.server.client.ExternalIdentityAccountManager;
import org.simplepoint.cloud.oauth.server.client.ExternalIdentityLinkFlow;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Personal security page for connecting and disconnecting external accounts. */
@Controller
@RequestMapping("/account/external-identities")
public class ExternalIdentityAccountController {

  private final ExternalIdentityAccountManager accountManager;

  private final ExternalIdentityLinkFlow linkFlow;

  /**
   * Creates the external identity account controller.
   *
   * @param accountManager external account query and unlink operations
   * @param linkFlow session-bound external account link flow
   */
  public ExternalIdentityAccountController(
      final ExternalIdentityAccountManager accountManager,
      final ExternalIdentityLinkFlow linkFlow
  ) {
    this.accountManager = accountManager;
    this.linkFlow = linkFlow;
  }

  /**
   * Displays the current user's external identity connections.
   *
   * @param authentication current authenticated principal
   * @param model view model populated with connection details
   * @param gateway whether the request is routed through the authorization gateway
   * @return external identity account view name
   */
  @GetMapping
  public String connections(
      final Authentication authentication,
      final Model model,
      @RequestParam(name = "gateway", defaultValue = "false") final boolean gateway
  ) {
    String userId = currentUserId(authentication);
    String prefix = gateway ? "/authorization" : "";
    String suffix = gateway ? "?gateway=true" : "";
    model.addAttribute("connections", accountManager.connections(userId));
    model.addAttribute("actionPrefix", prefix + "/account/external-identities/");
    model.addAttribute("actionSuffix", suffix);
    model.addAttribute("closeUrl", gateway ? "/profile" : "/");
    return "external-identities";
  }

  /**
   * Starts an explicit link flow for an external identity provider.
   *
   * @param registrationId external identity provider registration identifier
   * @param authentication current authenticated principal
   * @param session current HTTP session
   * @param gateway whether the request is routed through the authorization gateway
   * @return redirect to the provider authorization endpoint or account settings
   */
  @PostMapping("/{registrationId}/link")
  public String link(
      @PathVariable("registrationId") final String registrationId,
      final Authentication authentication,
      final HttpSession session,
      @RequestParam(name = "gateway", defaultValue = "false") final boolean gateway
  ) {
    String userId = currentUserId(authentication);
    if (accountManager.isLinked(userId, registrationId)) {
      return redirectToSettings(gateway, "alreadyLinked=" + registrationId);
    }
    ResolvedExternalIdentityProvider provider = accountManager.providerForLink(registrationId);
    linkFlow.begin(session, userId, provider, gateway);
    String prefix = gateway ? "/authorization" : "";
    return "redirect:" + prefix + "/oauth2/authorization/" + registrationId;
  }

  /**
   * Removes the current user's link to an external identity provider.
   *
   * @param registrationId external identity provider registration identifier
   * @param authentication current authenticated principal
   * @param gateway whether the request is routed through the authorization gateway
   * @return redirect to external identity account settings
   */
  @PostMapping("/{registrationId}/unlink")
  public String unlink(
      @PathVariable("registrationId") final String registrationId,
      final Authentication authentication,
      @RequestParam(name = "gateway", defaultValue = "false") final boolean gateway
  ) {
    accountManager.unlink(currentUserId(authentication), registrationId);
    return redirectToSettings(gateway, "unlinked=" + registrationId);
  }

  private String redirectToSettings(final boolean gateway, final String state) {
    String prefix = gateway ? "/authorization" : "";
    String query = gateway ? "?gateway=true&" + state : "?" + state;
    return "redirect:" + prefix + "/account/external-identities" + query;
  }

  private String currentUserId(final Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new IllegalStateException("当前用户尚未登录");
    }
    if (authentication.getPrincipal() instanceof User user && user.getId() != null) {
      return user.getId();
    }
    return authentication.getName();
  }
}

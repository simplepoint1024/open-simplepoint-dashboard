package org.simplepoint.cloud.oauth.server.controller;

import jakarta.servlet.http.HttpSession;
import org.simplepoint.cloud.oauth.server.client.ExternalIdentityLinkFlow;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Guides first-time external users through proving ownership of a local account. */
@Controller
public class ExternalIdentityLinkController {

  private final ExternalIdentityLinkFlow linkFlow;

  /**
   * Creates the external identity link controller.
   *
   * @param linkFlow session-bound external account link flow
   */
  public ExternalIdentityLinkController(final ExternalIdentityLinkFlow linkFlow) {
    this.linkFlow = linkFlow;
  }

  /**
   * Displays the local account verification page for a pending external login.
   *
   * @param session current HTTP session
   * @param model view model populated with pending link details
   * @param gateway whether the request is routed through the authorization gateway
   * @return external account link view name
   */
  @GetMapping("/external-account/link")
  public String linkPage(
      final HttpSession session,
      final Model model,
      @RequestParam(name = "gateway", defaultValue = "false") final boolean gateway
  ) {
    ExternalIdentityLinkFlow.PendingLink pending = linkFlow.pending(session);
    model.addAttribute("pending", pending);
    String prefix = gateway ? "/authorization" : "";
    model.addAttribute("loginUrl", prefix + "/login");
    String query = gateway ? "?gateway=true" : "";
    model.addAttribute("registerUrl", prefix + "/register" + query);
    model.addAttribute("cancelUrl", prefix + "/external-account/cancel" + query);
    return "external-account-link";
  }

  /**
   * Cancels a pending external account link flow.
   *
   * @param session current HTTP session
   * @param gateway whether the request is routed through the authorization gateway
   * @return redirect to the login page
   */
  @PostMapping("/external-account/cancel")
  public String cancel(
      final HttpSession session,
      @RequestParam(name = "gateway", defaultValue = "false") final boolean gateway
  ) {
    linkFlow.clear(session);
    String prefix = gateway ? "/authorization" : "";
    return "redirect:" + prefix + "/login?externalCancelled";
  }
}

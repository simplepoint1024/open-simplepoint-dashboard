package org.simplepoint.cloud.oauth.server.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.simplepoint.cloud.oauth.server.event.LoginAuditEventPublisher;
import org.simplepoint.cloud.oauth.server.handler.LoginAuthenticationSuccessHandler;
import org.simplepoint.cloud.oauth.server.provider.PendingMfaAuthentication;
import org.simplepoint.cloud.oauth.server.service.LoginSession;
import org.simplepoint.plugin.rbac.authentication.MfaVerificationService;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** A five-minute, credential-free challenge is the only accepted input identity. */
@Controller
public class TwoFactorController {
  private final MfaVerificationService verification;
  private final LoginAuthenticationSuccessHandler success;
  private final LoginAuditEventPublisher audit;

  /** Creates the challenge endpoint with shared verification and final login handling. */
  public TwoFactorController(
      MfaVerificationService verification,
      LoginAuthenticationSuccessHandler success,
      LoginAuditEventPublisher audit) {
    this.verification = verification;
    this.success = success;
    this.audit = audit;
  }

  /** Displays verification only for an outstanding challenge. */
  @GetMapping("/two-factor/verify")
  public String showVerificationPage() {
    return pending() == null ? "redirect:/login" : "two-factor-verify";
  }

  /** Verifies the current challenge without granting access on failure. */
  @PostMapping("/two-factor/verify")
  public String verify(
      @RequestParam("code") String code,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response)
      throws ServletException, IOException {
    PendingMfaAuthentication challenge = pending();
    if (challenge == null) {
      return "redirect:/login";
    }
    try {
      var user = verification.verify(challenge.challenge(), code == null ? "" : code.trim());
      success.complete(request, response, LoginSession.authenticated(user));
      return null;
    } catch (AuthenticationException ex) {
      audit.publishFailure(request, challenge, ex);
      model.addAttribute("error", "验证失败或尝试次数过多，请稍后重试");
      return "two-factor-verify";
    }
  }

  private PendingMfaAuthentication pending() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication instanceof PendingMfaAuthentication value && !value.expired()
        ? value
        : null;
  }
}

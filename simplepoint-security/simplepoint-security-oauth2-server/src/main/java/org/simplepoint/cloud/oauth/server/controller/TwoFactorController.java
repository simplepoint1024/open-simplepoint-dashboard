package org.simplepoint.cloud.oauth.server.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.cloud.oauth.server.handler.LoginAuthenticationSuccessHandler;
import org.simplepoint.cloud.oauth.server.provider.TwoFactorAuthenticationProvider;
import org.simplepoint.cloud.oauth.server.provider.TwoFactorAuthenticationToken;
import org.simplepoint.cloud.oauth.server.service.TotpService;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Controller for handling two-factor authentication verification during login.
 * Uses the global AuthenticationManager to delegate to TwoFactorAuthenticationProvider
 * for validating TOTP codes when 2FA is enabled for the current user.
 */
@Slf4j
@Controller
public class TwoFactorController {

  private final AuthenticationManager authenticationManager;

  private final LoginAuthenticationSuccessHandler authenticationSuccessHandler;

  /**
   * Constructs the TwoFactorController and registers the TwoFactorAuthenticationProvider
   * with the provided AuthenticationManager.
   *
   * @param authenticationManager the global authentication manager
   * @param totpService           the TOTP service for code verification
   */
  public TwoFactorController(
      AuthenticationManager authenticationManager,
      TotpService totpService,
      LoginAuthenticationSuccessHandler authenticationSuccessHandler
  ) {
    this.authenticationManager = authenticationManager;
    this.authenticationSuccessHandler = authenticationSuccessHandler;
    if (authenticationManager instanceof ProviderManager providerManager) {
      providerManager.getProviders().add(new TwoFactorAuthenticationProvider(totpService));
    }
  }

  /**
   * Display the 2FA verification page.
   */
  @GetMapping("/two-factor/verify")
  public String showVerificationPage() {
    return "two-factor-verify";
  }

  /**
   * Handle submission of the 2FA verification code.
   * For now, any non-empty code is treated as success so we don't block login
   * while stabilizing the integration.
   */
  @PostMapping("/two-factor/verify")
  public String verify(
      @RequestParam("code") String code,
      Model model,
      HttpServletRequest request,
      HttpServletResponse response
  ) {
    Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
    if (currentAuth == null || !currentAuth.isAuthenticated()) {
      return "redirect:/login";
    }

    Object principal = currentAuth.getPrincipal();
    if (!(principal instanceof User user)) {
      return "redirect:/login";
    }

    if (code == null || code.trim().isEmpty()) {
      model.addAttribute("error", "Code is required");
      return "two-factor-verify";
    }

    try {
      Authentication result = authenticationManager.authenticate(new TwoFactorAuthenticationToken(user, code.trim()));
      SecurityContextHolder.getContext().setAuthentication(result);
      authenticationSuccessHandler.onAuthenticationSuccessDelegate(request, response, currentAuth);
      return null;
    } catch (AuthenticationException ex) {
      log.error("AuthenticationException", ex);
      model.addAttribute("error", "Invalid authentication code");
      return "two-factor-verify";
    } catch (ServletException | IOException e) {
      throw new RuntimeException(e);
    }
  }
}

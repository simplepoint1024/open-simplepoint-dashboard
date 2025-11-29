package org.simplepoint.cloud.oauth.server.controller;

import lombok.RequiredArgsConstructor;
import org.simplepoint.cloud.oauth.server.service.TwoFactorSetupService;
import org.simplepoint.security.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Controller for enabling and configuring TOTP-based two-factor authentication
 * for the currently authenticated user.
 */
@Controller
@RequestMapping("/account/2fa")
@RequiredArgsConstructor
public class TwoFactorSetupController {

  private final TwoFactorSetupService twoFactorSetupService;

  /**
   * Show simple 2FA settings page (enabled/disabled + actions).
   */
  @GetMapping
  public String settings(Model model, Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
      return "redirect:/login";
    }
    model.addAttribute("twoFactorEnabled", Boolean.TRUE.equals(user.getTwoFactorEnabled()));
    return "two-factor-settings";
  }

  /**
   * Generate a new TOTP secret for the current user and show setup info.
   */
  @PostMapping("/enable")
  public String enable(Model model, Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
      return "redirect:/login";
    }
    var result = twoFactorSetupService.enable(user);

    model.addAttribute("secret", result.secret());
    model.addAttribute("otpauthUrl", result.otpauthUrl());
    return "two-factor-setup";
  }

  /**
   * Disable 2FA for the current user.
   */
  @PostMapping("/disable")
  public String disable(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
      return "redirect:/login";
    }
    twoFactorSetupService.disable(user);
    return "redirect:/account/2fa";
  }
}

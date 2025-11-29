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
import org.springframework.web.bind.annotation.RequestParam;

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
   * The user must scan the QR code and confirm with a valid code to finish enabling 2FA.
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
   * Confirm enabling 2FA by verifying the first TOTP code from the authenticator app.
   */
  @PostMapping("/confirm")
  public String confirm(@RequestParam("code") String code, Model model,
                        Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
      return "redirect:/login";
    }
    if (code == null || code.trim().isEmpty()) {
      model.addAttribute("error", "验证码不能为空");
      // re-render setup info
      var result = twoFactorSetupService.enable(user);
      model.addAttribute("secret", result.secret());
      model.addAttribute("otpauthUrl", result.otpauthUrl());
      return "two-factor-setup";
    }

    boolean success = twoFactorSetupService.confirm(user, code.trim());
    if (!success) {
      model.addAttribute("error", "验证码不正确，请重新输入");
      // 重新提供同一个 secret/otpauthUrl 供用户再次尝试
      var result = twoFactorSetupService.currentPending(user);
      if (result == null) {
        // 若不存在 pending，则重新生成一次
        result = twoFactorSetupService.enable(user);
      }
      model.addAttribute("secret", result.secret());
      model.addAttribute("otpauthUrl", result.otpauthUrl());
      return "two-factor-setup";
    }

    return "redirect:/account/2fa";
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

/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.controller;

import jakarta.servlet.http.HttpSession;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.cloud.oauth.server.client.ExternalIdentityLinkFlow;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Controller handling user self-registration page.
 * 处理用户自助注册的控制器
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class RegisterController {

  private final UsersService usersService;

  private final MessageSource messageSource;

  private final ExternalIdentityLinkFlow externalIdentityLinkFlow;

  /**
   * Serves the registration page.
   *
   * @return the name of the register view
   */
  @GetMapping("/register")
  public String registerPage(
      final Model model,
      final HttpSession session,
      @RequestParam(name = "gateway", defaultValue = "false") final boolean gateway
  ) {
    addPageModel(model, session, gateway);
    return "register";
  }

  /**
   * Handles registration form submission.
   * Validates inputs, checks email uniqueness, encodes password, and creates the user.
   *
   * @param email           the email address (used as unique identifier)
   * @param password        the plain-text password
   * @param confirmPassword confirmation of the password
   * @param nickname        optional display name
   * @param model           the Spring MVC model for returning error/success messages
   * @return redirect to login on success, or re-render register page on error
   */
  @PostMapping("/register")
  public String register(
      @RequestParam("email") final String email,
      @RequestParam("password") final String password,
      @RequestParam("confirmPassword") final String confirmPassword,
      @RequestParam(value = "nickname", required = false) final String nickname,
      @RequestParam(name = "gateway", defaultValue = "false") final boolean gateway,
      final HttpSession session,
      final Locale locale,
      final Model model
  ) {
    addPageModel(model, session, gateway);
    if (email == null || email.isBlank()) {
      model.addAttribute("error", messageSource.getMessage("register.error.emailRequired", null, locale));
      return "register";
    }
    if (!email.matches("^[\\w._%+\\-]+@[\\w.\\-]+\\.[A-Za-z]{2,}$")) {
      model.addAttribute("error", messageSource.getMessage("register.error.emailInvalid", null, locale));
      return "register";
    }
    if (password == null || password.length() < 6) {
      model.addAttribute("error", messageSource.getMessage("register.error.passwordLength", null, locale));
      return "register";
    }
    if (!password.equals(confirmPassword)) {
      model.addAttribute("error", messageSource.getMessage("register.error.passwordMismatch", null, locale));
      return "register";
    }

    try {
      User user = new User();
      user.setEmail(email);
      user.setPassword(password);
      if (nickname != null && !nickname.isBlank()) {
        user.setNickname(nickname);
      }
      usersService.create(user);
      String prefix = gateway ? "/authorization" : "";
      String query = gateway ? "?gateway=true&registered" : "?registered";
      return "redirect:" + prefix + "/login" + query;
    } catch (DataIntegrityViolationException e) {
      log.warn("Registration failed for email {}: duplicate entry", email);
      model.addAttribute("error", messageSource.getMessage("register.error.duplicate", null, locale));
      return "register";
    } catch (Exception e) {
      log.error("Registration error for email {}", email, e);
      model.addAttribute("error", messageSource.getMessage("register.error.failed", null, locale));
      return "register";
    }
  }

  private void addPageModel(
      final Model model,
      final HttpSession session,
      final boolean gateway
  ) {
    String prefix = gateway ? "/authorization" : "";
    String query = gateway ? "?gateway=true" : "";
    ExternalIdentityLinkFlow.PendingLink pending = externalIdentityLinkFlow.pending(session);
    model.addAttribute("suggestedEmail", pending == null ? null : pending.email());
    model.addAttribute("registerAction", prefix + "/register" + query);
    model.addAttribute("loginUrl", prefix + "/login" + query);
  }
}

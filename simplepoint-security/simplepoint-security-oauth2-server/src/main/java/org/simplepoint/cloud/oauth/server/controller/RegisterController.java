/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
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

  /**
   * Serves the registration page.
   *
   * @return the name of the register view
   */
  @GetMapping("/register")
  public String registerPage() {
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
      final Model model
  ) {
    if (email == null || email.isBlank()) {
      model.addAttribute("error", "邮箱不能为空");
      return "register";
    }
    if (!email.matches("^[\\w._%+\\-]+@[\\w.\\-]+\\.[A-Za-z]{2,}$")) {
      model.addAttribute("error", "邮箱格式不正确");
      return "register";
    }
    if (password == null || password.length() < 6) {
      model.addAttribute("error", "密码长度不能少于 6 位");
      return "register";
    }
    if (!password.equals(confirmPassword)) {
      model.addAttribute("error", "两次输入的密码不一致");
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
      return "redirect:/login?registered";
    } catch (DataIntegrityViolationException e) {
      log.warn("Registration failed for email {}: duplicate entry", email);
      model.addAttribute("error", "该邮箱已被注册，请直接登录");
      return "register";
    } catch (Exception e) {
      log.error("Registration error for email {}", email, e);
      model.addAttribute("error", "注册失败，请稍后重试");
      return "register";
    }
  }
}

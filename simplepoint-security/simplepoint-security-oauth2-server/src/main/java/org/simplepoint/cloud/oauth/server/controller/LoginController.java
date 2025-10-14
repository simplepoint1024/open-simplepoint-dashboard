package org.simplepoint.cloud.oauth.server.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller handling login page requests.
 * 处理登录页面请求的控制器
 */
@Controller
public class LoginController {

  /**
   * Serves the login page.
   *
   * @return the name of the login view
   */
  @GetMapping("/login")
  public String login() {
    return "login"; // 确保 templates/login.html 存在
  }
}

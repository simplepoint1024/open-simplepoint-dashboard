package org.simplepoint.cloud.oauth.server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

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

  /**
   * Ignore Chrome DevTools / other browser probes under .well-known/appspecific.
   * Return 204 so browser认为探测成功，不再干扰业务路由 / 日志。
   */
  @RequestMapping("/.well-known/appspecific/**")
  public ResponseEntity<Void> ignoreChromeProbe() {
    return ResponseEntity.noContent().build(); // 204 No Content
  }
}

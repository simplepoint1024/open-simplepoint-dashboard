package org.simplepoint.cloud.oauth.server.controller;

import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Controller handling login page requests.
 * 处理登录页面请求的控制器
 */
@Controller
public class LoginController {

  private final ExternalIdentityProviderService providerService;

  /**
   * Creates the login controller.
   *
   * @param providerService external identity-provider service
   */
  public LoginController(final ExternalIdentityProviderService providerService) {
    this.providerService = providerService;
  }

  /**
   * Serves the login page.
   *
   * @param model template model
   * @return the name of the login view
   */
  @GetMapping("/login")
  public String login(
      final Model model,
      @RequestParam(name = "gateway", defaultValue = "false") final boolean gateway
  ) {
    String prefix = gateway ? "/authorization" : "";
    model.addAttribute("identityProviders", providerService.enabledProviders());
    model.addAttribute("loginAction", prefix + "/login");
    model.addAttribute("providerAuthorizationPrefix", prefix + "/oauth2/authorization/");
    model.addAttribute("registerUrl", prefix + "/register" + (gateway ? "?gateway=true" : ""));
    return "login";
  }

  /**
   * Serves the user-code entry page defined by RFC 8628.
   *
   * @param userCode optional user code from verification_uri_complete
   * @param error optional verification error
   * @param model template model
   * @return device activation view
   */
  @GetMapping("/activate")
  public String activate(
      @RequestParam(name = "user_code", required = false) final String userCode,
      @RequestParam(name = "error", required = false) final String error,
      final Model model
  ) {
    model.addAttribute("userCode", userCode);
    model.addAttribute("deviceError", error != null);
    return "device-activate";
  }

  /**
   * Serves the terminal device authorization success page.
   */
  @GetMapping("/device-activated")
  public String deviceActivated() {
    return "device-activated";
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

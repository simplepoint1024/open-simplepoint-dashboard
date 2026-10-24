package org.simplepoint.cloud.oauth.server.browser;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.security.entity.User;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Browser projections coexist with the issuer's bearer-only OIDC UserInfo filter. */
@RestController
@ConditionalOnProperty(name = "simplepoint.security.browser.enabled", havingValue = "true")
public final class BrowserSessionController {
  public static final String USERINFO = "/userinfo";
  public static final String CSRF = "/csrf";
  public static final String LINKS = "/account/security-links";
  public static final String AUTHORIZE = "/simplepoint/authorize";

  /** Requires a modular host; enabling this feature in a legacy host is a configuration error. */
  public BrowserSessionController(ModulePlan modules) {
    java.util.Objects.requireNonNull(modules);
  }

  /** Returns only the browser profile contract, never an entity or credentials. */
  @GetMapping(USERINFO)
  public ResponseEntity<Map<String, Object>> userinfo(@AuthenticationPrincipal User user) {
    var claims = new LinkedHashMap<String, Object>();
    claims.put("sub", user.getId());
    claims.put("name", org.simplepoint.security.identity.UserDisplayName.fullName(user));
    claims.put("nickname", user.getNickname());
    claims.put("given_name", user.getGivenName());
    claims.put("family_name", user.getFamilyName());
    claims.put("preferred_username", user.getUsername());
    claims.put("picture", user.getPicture());
    claims.put("email", user.getEmail());
    claims.put("email_verified", user.getEmailVerified());
    claims.put("phone_number", user.getPhoneNumber());
    claims.put("twoFactorEnabled", Boolean.TRUE.equals(user.getTwoFactorEnabled()));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(claims);
  }

  /** Same masked token contract as the WebFlux browser host. */
  @GetMapping(CSRF)
  public ResponseEntity<Map<String, String>> csrf(CsrfToken token) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "token",
                token.getToken(),
                "headerName",
                token.getHeaderName(),
                "parameterName",
                token.getParameterName()));
  }

  /** Account pages share the same origin and login session in a modular servlet host. */
  @GetMapping(LINKS)
  public ResponseEntity<Map<String, String>> links(HttpServletRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            Map.of(
                "twoFactor", request.getContextPath() + "/account/2fa",
                "externalIdentities", request.getContextPath() + "/account/external-identities"));
  }

  /** Preserves the host's public sign-in entry without a loopback OAuth client. */
  @GetMapping(AUTHORIZE)
  public ResponseEntity<Void> authorize(HttpServletRequest request) {
    return ResponseEntity.status(302)
        .location(java.net.URI.create(request.getContextPath() + "/login"))
        .cacheControl(CacheControl.noStore())
        .build();
  }
}

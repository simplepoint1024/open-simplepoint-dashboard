package org.simplepoint.cloud.oauth.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.cloud.oauth.server.service.TwoFactorSetupService;
import org.simplepoint.cloud.oauth.server.service.TwoFactorSetupService.EnableResult;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;

@ExtendWith(MockitoExtension.class)
class TwoFactorSetupControllerTest {

  @Mock
  private TwoFactorSetupService service;

  private TwoFactorSetupController controller;

  private User user;

  private Authentication authentication;

  @BeforeEach
  void setUp() {
    controller = new TwoFactorSetupController(service);
    user = new User();
    user.setId("user-1");
    authentication = new UsernamePasswordAuthenticationToken(user, null);
  }

  @Test
  void settingsUsesGatewayAwareAssetsAndActions() {
    ExtendedModelMap model = new ExtendedModelMap();

    String view = controller.settings(model, authentication, true);

    assertThat(view).isEqualTo("two-factor-settings");
    assertThat(model.get("twoFactorCssUrl")).isEqualTo("/authorization/two-factor.css");
    assertThat(model.get("twoFactorEnableUrl"))
        .isEqualTo("/authorization/account/2fa/enable?gateway=true");
    assertThat(model.get("twoFactorDisableUrl"))
        .isEqualTo("/authorization/account/2fa/disable?gateway=true");
  }

  @Test
  void enableUsesDirectAuthorizationUrlsWithoutGatewayFlag() {
    when(service.enable(user)).thenReturn(new EnableResult("secret", "otpauth://demo"));
    ExtendedModelMap model = new ExtendedModelMap();

    String view = controller.enable(model, authentication, false);

    assertThat(view).isEqualTo("two-factor-setup");
    assertThat(model.get("twoFactorCssUrl")).isEqualTo("/two-factor.css");
    assertThat(model.get("twoFactorConfirmUrl")).isEqualTo("/account/2fa/confirm");
  }

  @Test
  void confirmReturnsToGatewaySettingsAfterSuccess() {
    when(service.confirm(user, "123456")).thenReturn(true);

    String view = controller.confirm(
        "123456",
        new ExtendedModelMap(),
        authentication,
        true
    );

    assertThat(view).isEqualTo("redirect:/authorization/account/2fa?gateway=true");
    verify(service).confirm(user, "123456");
  }
}

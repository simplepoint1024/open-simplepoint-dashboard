package org.simplepoint.plugin.rbac.authentication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthenticationModuleBoundaryTest {
  @Test
  void reusablePolicyDoesNotBringTheOauthServerOrBrowserChallengeIntoItsRuntime() {
    for (String type :
        new String[] {
          "org.simplepoint.cloud.oauth.server.configuration.AuthorizationServerConfiguration",
          "org.simplepoint.plugin.rbac.credentials.CredentialAuthenticationServiceImpl",
          "org.simplepoint.cloud.oauth.server.provider.PendingMfaAuthentication"
        }) {
      assertThatThrownBy(() -> Class.forName(type)).isInstanceOf(ClassNotFoundException.class);
    }
  }
}

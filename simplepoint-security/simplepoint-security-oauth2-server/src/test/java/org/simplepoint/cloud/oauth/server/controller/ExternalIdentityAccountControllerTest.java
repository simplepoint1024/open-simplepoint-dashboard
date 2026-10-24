package org.simplepoint.cloud.oauth.server.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplepoint.cloud.oauth.server.client.ExternalIdentityLinkFlow;
import org.simplepoint.plugin.oidc.api.error.ExternalIdentityBindingException;
import org.simplepoint.plugin.oidc.api.error.ExternalIdentityBindingException.Reason;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityAccountService;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.simplepoint.security.entity.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExternalIdentityAccountControllerTest {
  @ParameterizedTest
  @EnumSource(Reason.class)
  void bindingFailuresHaveStableCodesAndTranslationKeys(Reason reason) throws Exception {
    var accounts = mock(ExternalIdentityAccountService.class);
    doThrow(new ExternalIdentityBindingException(reason)).when(accounts).unlink("user", "example");
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new ExternalIdentityAccountController(
                    accounts,
                    mock(ExternalIdentityLinkFlow.class),
                    mock(ExternalIdentityProviderService.class)))
            .build();
    var user = new User();
    user.setId("user");
    var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
    int expected =
        switch (reason) {
          case INVALID_INPUT -> 400;
          case NOT_LINKED -> 404;
          default -> 409;
        };
    mvc.perform(post("/account/external-identities/example/unlink").principal(authentication))
        .andExpect(status().is(expected))
        .andExpect(jsonPath("$.code").value("OIDC_BINDING_" + reason.name()))
        .andExpect(jsonPath("$.messageKey").value("external-idp.binding." + reason.name()))
        .andExpect(jsonPath("$.message").doesNotExist());
  }
}

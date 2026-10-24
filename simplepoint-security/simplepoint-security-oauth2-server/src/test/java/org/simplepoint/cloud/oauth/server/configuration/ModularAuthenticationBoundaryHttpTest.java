package org.simplepoint.cloud.oauth.server.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.cloud.oauth.server.expansion.oidc.OidcConfigurerExpansion;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.http.servlet.ModuleHttpAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/** Runs the same login/MFA/CSRF tests with modular mounting and bounded security enabled. */
class ModularAuthenticationBoundaryHttpTest extends AuthenticationBoundaryHttpTest {
  @Override
  void configureContext(AnnotationConfigWebApplicationContext application) {
    var plan =
        ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("authorization-http"));
    application.addBeanFactoryPostProcessor(
        new org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor() {
          public void postProcessBeanDefinitionRegistry(
              org.springframework.beans.factory.support.BeanDefinitionRegistry beans) {
            beans.registerBeanDefinition(
                "modulePlan",
                org.springframework.beans.factory.support.BeanDefinitionBuilder
                    .genericBeanDefinition(ModulePlan.class, () -> plan)
                    .getBeanDefinition());
          }
        });
    application.register(ModuleHttpAutoConfiguration.class, Openid.class);
  }

  @Test
  void nativeOauthAndOpenidDiscoveryPathsMatchTheDeclaredProtocolContract() throws Exception {
    var plan = ModuleHttpPlan.from(context.getBean(ModulePlan.class));
    assertThat(plan.routes().get("authorization-http"))
        .contains(
            "/.well-known/openid-configuration",
            "/.well-known/oauth-authorization-server",
            "/oauth2/token",
            "/userinfo",
            "/login",
            "/identity.css");
    mvc.perform(get("/.well-known/openid-configuration"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.issuer").value("http://localhost"));
    mvc.perform(get("/.well-known/oauth-authorization-server"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token_endpoint").value("http://localhost/oauth2/token"));
  }

  @Test
  void authenticationChainsDoNotCaptureBusinessMcpOrHealthRequests() {
    var browser = context.getBean("defaultSecurityFilterChain", SecurityFilterChain.class);
    var protocol =
        context.getBean("authorizationServerSecurityFilterChain", SecurityFilterChain.class);
    for (String path :
        List.of("/login", "/two-factor/verify", "/register", "/identity.css", "/account/2fa")) {
      assertThat(matches(browser, path)).as(path).isTrue();
    }
    for (String path :
        List.of(
            "/common/users",
            "/app/forms",
            "/mcp/demo",
            "/actuator/health",
            "/.well-known/oauth-protected-resource/mcp/demo",
            "/.well-known/oauth-client/example")) {
      assertThat(matches(browser, path)).as(path).isFalse();
      assertThat(matches(protocol, path)).as(path).isFalse();
    }
  }

  private boolean matches(SecurityFilterChain chain, String path) {
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    return chain.matches(request);
  }

  @Configuration(proxyBeanMethods = false)
  static class Openid {
    @Bean
    OidcConfigurerExpansion openidProtocol() {
      return configurer -> {};
    }
  }
}

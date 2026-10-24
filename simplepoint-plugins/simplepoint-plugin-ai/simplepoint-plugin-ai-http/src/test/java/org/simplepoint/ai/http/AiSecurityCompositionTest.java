package org.simplepoint.ai.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.ai.core.rest.config.AiGatewaySecurityConfiguration;
import org.simplepoint.plugin.ai.mcp.api.properties.AiMcpProperties;
import org.simplepoint.plugin.ai.mcp.rest.config.AiMcpPublicationInternalSecurityConfiguration;
import org.simplepoint.plugin.ai.runtime.api.properties.AiRuntimeProperties;
import org.simplepoint.plugin.ai.runtime.rest.config.AiRuntimeInternalSecurityConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Real security chain matchers, without starting provider clients or external listeners. */
class AiSecurityCompositionTest {
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withUserConfiguration(
              Security.class,
              AiGatewaySecurityConfiguration.class,
              AiMcpPublicationInternalSecurityConfiguration.class,
              AiRuntimeInternalSecurityConfiguration.class)
          .withBean(AiMcpProperties.class, AiMcpProperties::new)
          .withBean(AiRuntimeProperties.class, AiRuntimeProperties::new);

  @Test
  void originalHostKeepsItsIndependentProtocolBoundaries() {
    verifyBoundaries(runner, "");
  }

  @Test
  void aggregateHostMovesEverySpecialChainWithItsOwnedControllers() {
    verifyBoundaries(
        runner.withBean(
            ModulePlan.class,
            () -> ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("ai-http"))),
        "/ai");
  }

  private void verifyBoundaries(WebApplicationContextRunner contextRunner, String prefix) {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          var gateway = context.getBean("aiGatewaySecurityFilterChain", SecurityFilterChain.class);
          var publications =
              context.getBean(
                  "aiMcpPublicationInternalSecurityFilterChain", SecurityFilterChain.class);
          var nodes =
              context.getBean("aiRuntimeInternalSecurityFilterChain", SecurityFilterChain.class);
          String generation = prefix + "/v1/responses";
          String publication = prefix + "/internal/mcp/publications/snapshot";
          String heartbeat = prefix + "/internal/runtime/nodes/example/heartbeat";
          assertThat(matches(gateway, generation)).isTrue();
          assertThat(matches(publications, publication)).isTrue();
          assertThat(matches(nodes, heartbeat)).isTrue();
          assertThat(matches(gateway, heartbeat)).isFalse();
          assertThat(matches(gateway, publication)).isFalse();
          assertThat(matches(publications, heartbeat)).isFalse();
          assertThat(matches(nodes, publication)).isFalse();
          for (var chain : List.of(gateway, publications, nodes)) {
            assertThat(matches(chain, prefix + "/workbench/providers")).isFalse();
            assertThat(matches(chain, "/foreign" + generation)).isFalse();
            assertThat(matches(chain, "/foreign" + publication)).isFalse();
            assertThat(matches(chain, "/foreign" + heartbeat)).isFalse();
          }
          if (!prefix.isEmpty()) {
            assertThat(matches(gateway, "/v1/responses")).isFalse();
            assertThat(matches(publications, "/internal/mcp/publications/snapshot")).isFalse();
            assertThat(matches(nodes, "/internal/runtime/nodes/example/heartbeat")).isFalse();
          }
        });
  }

  private boolean matches(SecurityFilterChain chain, String path) {
    var request = new MockHttpServletRequest("POST", path);
    request.setServletPath(path);
    return chain.matches(request);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebSecurity
  static class Security {}
}

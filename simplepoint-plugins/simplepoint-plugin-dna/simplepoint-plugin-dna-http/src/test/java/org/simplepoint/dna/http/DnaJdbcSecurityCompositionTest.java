package org.simplepoint.dna.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.dna.federation.rest.config.DnaJdbcDriverSecurityConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** The credential-owned driver chain follows the same mounted prefix as its HTTP module. */
class DnaJdbcSecurityCompositionTest {
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withUserConfiguration(Security.class, DnaJdbcDriverSecurityConfiguration.class);

  @Test
  void legacyChainDoesNotPermitUnrelatedManagementPaths() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          var chain = context.getBean(SecurityFilterChain.class);
          assertThat(matches(chain, "/dna/federation/jdbc-driver/session")).isTrue();
          assertThat(matches(chain, "/platform/dna/federation/jdbc-driver/session")).isTrue();
          assertThat(matches(chain, "/dna/federation/catalogs")).isFalse();
          assertThat(matches(chain, "/dna/dna/federation/jdbc-driver/session")).isFalse();
        });
  }

  @Test
  void aggregateChainMatchesOnlyItsSelectedLogicalNamespace() {
    runner
        .withBean(
            ModulePlan.class,
            () -> ModuleCatalog.discover(getClass().getClassLoader()).plan(List.of("dna-http")))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var chain = context.getBean(SecurityFilterChain.class);
              assertThat(matches(chain, "/dna/dna/federation/jdbc-driver/session")).isTrue();
              assertThat(matches(chain, "/dna/platform/dna/federation/jdbc-driver/session"))
                  .isTrue();
              assertThat(matches(chain, "/dna/federation/jdbc-driver/session")).isFalse();
              assertThat(matches(chain, "/dna/platform/dna/federation/catalogs")).isFalse();
              assertThat(matches(chain, "/other/dna/federation/jdbc-driver/session")).isFalse();
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

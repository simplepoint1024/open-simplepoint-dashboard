package org.simplepoint.platform.http.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.http.fixture.domain.DomainModule;
import org.simplepoint.http.fixture.sharedalpha.SharedAlphaModule;
import org.simplepoint.http.fixture.sharedbeta.SharedBetaModule;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleCompositionException;

class ModuleHttpPathsTest {
  @Test
  void legacyAdapterKeepsAlreadyStrippedPaths() {
    assertThat(ModuleHttpPaths.prefix(null, SharedAlphaModule.class)).isEmpty();
  }

  @Test
  void securityAndMvcUseTheSameSelectedPrefix() {
    var plan =
        new ModuleCatalog(List.of(SharedAlphaModule.class, SharedBetaModule.class))
            .plan(List.of("shared-alpha", "shared-beta"));
    assertThat(ModuleHttpPaths.prefix(plan, SharedAlphaModule.Accounts.class)).isEqualTo("/common");
    assertThat(ModuleHttpPaths.prefix(plan, SharedBetaModule.class)).isEqualTo("/common");
  }

  @Test
  void unselectedOrNonHttpModulesCannotGainAnUnprefixedSecurityChain() {
    var plan =
        new ModuleCatalog(List.of(SharedAlphaModule.class, DomainModule.class))
            .plan(List.of("shared-alpha", "domain"));
    assertThatThrownBy(() -> ModuleHttpPaths.prefix(plan, SharedBetaModule.class))
        .isInstanceOf(ModuleCompositionException.class)
        .hasMessageContaining("UNOWNED_HTTP_ADAPTER");
    assertThatThrownBy(() -> ModuleHttpPaths.prefix(plan, DomainModule.class))
        .isInstanceOf(ModuleCompositionException.class)
        .hasMessageContaining("UNOWNED_HTTP_ADAPTER");
  }
}

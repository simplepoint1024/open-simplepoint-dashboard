package org.simplepoint.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.bootstrap.service.PlatformContributionService;

class BootstrapExecutionTest {
  private final PlatformContributionService registry = mock(PlatformContributionService.class);
  private final AtomicInteger actions = new AtomicInteger();

  @Test
  void completionAcknowledgementFailureNeverRecordsSuccessfulBusinessWorkAsFailed() {
    var contribution = contribution(actions::incrementAndGet);
    when(registry.shouldApply(
            "module/example", "domain", "data", "seed", "1", "domain:data:seed:1"))
        .thenReturn(true);
    doThrow(new IllegalStateException("completion unavailable"))
        .when(registry)
        .markApplied("module/example", "domain", "data", "seed", "1", "domain:data:seed:1");
    assertThatThrownBy(() -> BootstrapExecution.apply("module/example", contribution, registry))
        .hasMessage("completion unavailable");
    assertThat(actions).hasValue(1);
    verify(registry, never())
        .markFailed(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString());
  }

  @Test
  void uncertainStartNeverExecutesOrOverwritesFailure() {
    when(registry.shouldApply(
            "module/example", "domain", "data", "seed", "1", "domain:data:seed:1"))
        .thenReturn(true);
    doThrow(new IllegalStateException("start unavailable"))
        .when(registry)
        .markRunning("module/example", "domain", "data", "seed", "1", "domain:data:seed:1");
    assertThatThrownBy(
            () ->
                BootstrapExecution.apply(
                    "module/example", contribution(actions::incrementAndGet), registry))
        .hasMessage("start unavailable");
    assertThat(actions).hasValue(0);
    verify(registry, never())
        .markFailed(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString());
  }

  @Test
  void actionFailurePreservesOriginalCauseAndDoesNotPersistExceptionPayload() {
    var original = new IllegalArgumentException("sensitive-input-must-not-be-stored");
    when(registry.shouldApply(
            "module/example", "domain", "data", "seed", "1", "domain:data:seed:1"))
        .thenReturn(true);
    doThrow(new IllegalStateException("registry down"))
        .when(registry)
        .markFailed(
            "module/example",
            "domain",
            "data",
            "seed",
            "1",
            "domain:data:seed:1",
            "IllegalArgumentException");
    assertThatThrownBy(
            () ->
                BootstrapExecution.apply(
                    "module/example",
                    contribution(
                        () -> {
                          throw original;
                        }),
                    registry))
        .isSameAs(original);
    assertThat(original.getSuppressed())
        .singleElement()
        .satisfies(suppressed -> assertThat(suppressed).hasMessage("registry down"));
    verify(registry, never())
        .markApplied(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void malformedRegistryResultDoesNotSilentlySkipRequiredInitialization() {
    when(registry.shouldApply(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(null);
    assertThatThrownBy(
            () ->
                BootstrapExecution.apply(
                    "module/example", contribution(actions::incrementAndGet), registry))
        .hasMessageContaining("BOOTSTRAP_REGISTRY_PROTOCOL");
    assertThat(actions).hasValue(0);
  }

  @Test
  void duplicateIdentityAndOversizedMetadataFailDuringPreparation() {
    var contribution = contribution(actions::incrementAndGet);
    assertThatThrownBy(() -> BootstrapExecution.prepare(List.of(contribution, contribution)))
        .hasMessageContaining("DUPLICATE_BOOTSTRAP_CONTRIBUTION");
    assertThatThrownBy(
            () ->
                BootstrapExecution.prepare(
                    List.of(
                        BootstrapContribution.versioned(
                            "domain", "data", "x".repeat(161), "1", 1, () -> {}))))
        .hasMessageContaining("contributionKey");
    assertThat(actions).hasValue(0);
  }

  @Test
  void moduleRegistryScopeCannotDependOnHostOrExceedThePersistedKeyBound() {
    assertThat(ModuleBootstrapScope.of("identity")).isEqualTo("module/identity");
    assertThat(ModuleBootstrapScope.isModule("common")).isFalse();
    assertThatThrownBy(() -> ModuleBootstrapScope.of("a".repeat(122)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ModuleBootstrapScope.isModule("module/../common"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private BootstrapContribution contribution(BootstrapContribution.Action action) {
    return BootstrapContribution.versioned("domain", "data", "seed", "1", 1, action);
  }
}

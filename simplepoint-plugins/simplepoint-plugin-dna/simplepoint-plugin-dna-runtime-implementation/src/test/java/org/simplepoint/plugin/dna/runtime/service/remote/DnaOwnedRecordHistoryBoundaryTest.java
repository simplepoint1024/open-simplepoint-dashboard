package org.simplepoint.plugin.dna.runtime.service.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordHistoryQuery;
import org.simplepoint.plugin.dna.runtime.configuration.DnaFormRuntimeRemoteProperties;
import org.simplepoint.plugin.dna.runtime.service.record.OwnedRecordService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class DnaOwnedRecordHistoryBoundaryTest {
  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @Test
  void rejectsBrowserAndUntrustedServiceBeforeAnySnapshotRead() {
    var records = mock(OwnedRecordService.class);
    final var provider = provider(records);
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("browser-user", null, "USER"));
    assertThatThrownBy(() -> provider.currentTargets(context(), null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> provider.snapshotTargets(context(), null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> provider.snapshotForParticipant(context(), null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> provider.observeMapping(context(), null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> provider.executeMapped(context(), null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> provider.previewMapped(context(), null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> provider.findMappedResult(context(), null))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> provider.snapshotMapped(context(), null))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(records);
  }

  @Test
  void trustedCallUsesViewerPolicyAndRestoresOuterContextWithoutImpersonation() {
    var records = mock(OwnedRecordService.class);
    final var provider = provider(records);
    final var query = mock(OwnedRecordHistoryQuery.class);
    var outer = new AuthorizationContext();
    outer.setUserId("outer");
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, outer);
    ResourcePolicyContext.set("outer-resource");
    var principal =
        new org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal(
            "app", Map.of("client_id", "app", "grant_type", "client_credentials"), List.of());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new TestingAuthenticationToken(principal, null, "SCOPE_service-router.invoke"));
    when(records.snapshotTargets(query))
        .thenAnswer(
            call -> {
              assertThat(AuthorizationContextHolder.getContext().getUserId()).isEqualTo("reviewer");
              assertThat(AuthorizationContextHolder.getContext().getIsAdministrator()).isFalse();
              assertThat(ResourcePolicyContext.get()).isEqualTo("source");
              return List.of();
            });
    when(records.snapshotForParticipant(query))
        .thenAnswer(
            call -> {
              assertThat(AuthorizationContextHolder.getContext().getUserId()).isEqualTo("reviewer");
              throw new IllegalStateException("read failed");
            });
    assertThat(provider.snapshotTargets(context(), query)).isEmpty();
    assertThatThrownBy(() -> provider.snapshotForParticipant(context(), query))
        .isInstanceOf(IllegalStateException.class);
    assertThat(AuthorizationContextHolder.getContext()).isSameAs(outer);
    assertThat(ResourcePolicyContext.get()).isEqualTo("outer-resource");
  }

  private static DnaOwnedRecordServiceImpl provider(OwnedRecordService records) {
    var properties = new DnaFormRuntimeRemoteProperties();
    properties.setAllowedCallers(Set.of("app"));
    return new DnaOwnedRecordServiceImpl(new DnaFormRuntimeCallerGuard(properties), records);
  }

  private static DnaInvocationContext context() {
    return new DnaInvocationContext(
        "tenant",
        "reviewer",
        null,
        "source",
        false,
        Set.of("source"),
        "SELF",
        Set.of(),
        false,
        List.of(),
        Map.of());
  }
}

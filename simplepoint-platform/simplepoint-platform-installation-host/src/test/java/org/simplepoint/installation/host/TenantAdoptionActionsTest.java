package org.simplepoint.installation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantLegacyAdoption;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantLegacyAdoptionMaintenance;

class TenantAdoptionActionsTest {
  @Test
  void inventoryPagesEveryTenantWithoutWritingAndRejectsStalledCursors() {
    var owner = mock(TenantLegacyAdoptionMaintenance.class);
    var first = IntStream.range(0, 100).mapToObj(i -> "tenant-%03d".formatted(i)).toList();
    when(owner.pending(null, 100)).thenReturn(first);
    when(owner.pending("tenant-099", 100)).thenReturn(List.of("tenant-100"));
    when(owner.preview(anyString())).thenAnswer(call -> preview(call.getArgument(0), 3));
    assertThat(TenantAdoptionActions.inventory(owner)).hasSize(101);
    verify(owner, times(0)).adopt(anyString());
    when(owner.pending("tenant-099", 100)).thenReturn(List.of("tenant-000"));
    assertThatThrownBy(() -> TenantAdoptionActions.inventory(owner)).hasMessageContaining("cursor did not advance");
  }

  @Test
  void actionRejectsDuplicateOrUnorderedTargetsBeforeCallingOwner() {
    var owner = mock(TenantLegacyAdoptionMaintenance.class);
    for (var targets : List.of(List.of("a", "a"), List.of("b", "a"), List.of(" "))) {
      assertThatThrownBy(() -> TenantAdoptionActions.prepare(owner, targets)).isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(owner);
  }

  @Test
  void partialCommitAndLostAcknowledgementResumeWithoutAdoptingCompletedTenantsAgain() throws Exception {
    var owner = mock(TenantLegacyAdoptionMaintenance.class);
    var committed = new HashMap<String, TenantLegacyAdoption>();
    when(owner.completed(anyString())).thenAnswer(call -> Optional.ofNullable(committed.get(call.<String>getArgument(0))));
    when(owner.adopt(anyString())).thenAnswer(call -> {
      String tenant = call.getArgument(0);
      var receipt = receipt(tenant);
      committed.put(tenant, receipt);
      if (tenant.equals("a")) {
        throw new IllegalStateException("response lost after commit");
      }
      return receipt;
    });
    var action = TenantAdoptionActions.prepare(owner, List.of("a", "b"));
    assertThatThrownBy(action::apply).hasMessageContaining("response lost");
    assertThat(action.completedReceipt()).isEmpty();
    String complete = action.apply();
    assertThat(action.completedReceipt()).contains(complete);
    assertThat(action.apply()).isEqualTo(complete);
    verify(owner).adopt("a");
    verify(owner).adopt("b");
  }

  @Test
  void mismatchedCompletionNeverMarksAnInstallationComplete() {
    var owner = mock(TenantLegacyAdoptionMaintenance.class);
    when(owner.completed("a")).thenReturn(Optional.of(receipt("foreign")));
    var action = TenantAdoptionActions.prepare(owner, List.of("a"));
    assertThatThrownBy(action::completedReceipt).hasMessageContaining("RECEIPT_CONFLICT");
    assertThatThrownBy(action::apply).hasMessageContaining("RECEIPT_CONFLICT");
    verify(owner, times(0)).adopt(anyString());
  }

  @Test
  void advisoryCountsDoNotBecomeResourceVersionsAndPreflightDoesNotWrite() {
    var owner = mock(TenantLegacyAdoptionMaintenance.class);
    when(owner.completed("a")).thenReturn(Optional.empty());
    when(owner.preview("a")).thenReturn(preview("a", 3), preview("a", 7));
    final var action = TenantAdoptionActions.prepare(owner, List.of("a"));
    String fingerprint = action.definition().inputFingerprint();
    TenantAdoptionActions.preflight(owner, List.of("a"));
    TenantAdoptionActions.preflight(owner, List.of("a"));
    assertThat(TenantAdoptionActions.prepare(owner, List.of("a")).definition().inputFingerprint()).isEqualTo(fingerprint);
    verify(owner, times(0)).adopt(anyString());
  }

  private static TenantLegacyAdoption.Preview preview(String tenant, int count) {
    return new TenantLegacyAdoption.Preview(tenant, count, count, 1, List.of());
  }

  private static TenantLegacyAdoption receipt(String tenant) {
    return new TenantLegacyAdoption(tenant, Instant.parse("2026-10-23T00:00:00Z"), "installation", 3, 1);
  }
}

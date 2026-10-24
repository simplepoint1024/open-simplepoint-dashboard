package org.simplepoint.plugin.rbac.tenant.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.api.model.ResourceEntitlementLedger.Credit;

class ResourceEntitlementLedgerTest {
  private static final Instant START = Instant.parse("2026-10-23T01:00:00.123456Z");

  @Test
  void revokingPartiallyConsumedFirstPurchasePreservesAllQueuedTime() {
    var history = List.of(new Credit("a", day(0), 30, day(20)), new Credit("b", day(10), 60, null));
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), day(20)).orElseThrow().expiresAt())
        .isEqualTo(day(80));
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), day(40)).orElseThrow().expiresAt())
        .isEqualTo(day(80));
  }

  @Test
  void revokingQueuedPurchaseDoesNotResetAlreadyConsumedTime() {
    var history = List.of(new Credit("a", day(0), 30, null), new Credit("b", day(10), 60, day(20)));
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), day(20)).orElseThrow().expiresAt())
        .isEqualTo(day(30));
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), day(30))).isEmpty();
  }

  @Test
  void revokingSpentPurchaseDoesNotShortenRenewalAfterAnExpiredGap() {
    var history = List.of(new Credit("a", day(0), 30, day(50)), new Credit("b", day(40), 60, null));
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), day(50)).orElseThrow().expiresAt())
        .isEqualTo(day(100));
  }

  @Test
  void finitePurchaseBehindUnlimitedRemainsAvailableAfterUnlimitedIsRevoked() {
    var history = List.of(new Credit("a", day(0), null, day(20)), new Credit("b", day(5), 10, null));
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), day(10)).orElseThrow().expiresAt()).isNull();
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), day(20)).orElseThrow().expiresAt())
        .isEqualTo(day(30));
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), day(30))).isEmpty();
  }

  @Test
  void multipleUnlimitedPurchasesRequireIndependentRevocation() {
    var history = List.of(new Credit("a", day(0), null, day(20)), new Credit("b", day(5), null, day(40)));
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), day(30)).orElseThrow().expiresAt()).isNull();
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), day(40))).isEmpty();
  }

  @Test
  void sameInstantRevocationConsumesNothingAndDoesNotAffectAnotherPurchase() {
    var history = List.of(new Credit("a", day(0), 30, day(0)), new Credit("b", day(0), 60, null));
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), day(0)).orElseThrow().expiresAt())
        .isEqualTo(day(60));
  }

  @Test
  void subsecondConsumptionPreservesDatabasePrecision() {
    Instant revoked = START.plusSeconds(5).plusNanos(123000);
    var history = List.of(new Credit("a", START, 1, revoked), new Credit("b", START.plusSeconds(1), 1, null));
    assertThat(ResourceEntitlementLedger.remaining(history.iterator(), revoked).orElseThrow().expiresAt())
        .isEqualTo(revoked.plus(Duration.ofDays(1)));
  }

  @Test
  void malformedOrUnorderedHistoryFailsInsteadOfIssuingCredit() {
    assertThatThrownBy(() -> new Credit("a", day(1), 1, day(0))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Credit("a", day(0), 0, null)).isInstanceOf(IllegalArgumentException.class);
    var future = List.of(new Credit("a", day(1), 1, null));
    assertThatThrownBy(() -> ResourceEntitlementLedger.remaining(future.iterator(), day(0)))
        .isInstanceOf(IllegalArgumentException.class);
    var unordered = List.of(new Credit("b", day(0), 1, null), new Credit("a", day(0), 1, null));
    assertThatThrownBy(() -> ResourceEntitlementLedger.remaining(unordered.iterator(), day(0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Instant day(long days) {
    return START.plus(Duration.ofDays(days));
  }
}

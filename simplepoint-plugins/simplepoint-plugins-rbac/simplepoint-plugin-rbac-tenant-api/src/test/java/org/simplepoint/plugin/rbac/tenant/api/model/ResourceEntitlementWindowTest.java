package org.simplepoint.plugin.rbac.tenant.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ResourceEntitlementWindowTest {
  private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

  @Test
  void overlappingPackagesAccumulatePerResourceInsteadOfTakingTheLongestExpiry() {
    var customer = ResourceEntitlementWindow.append(null, NOW, 30);
    var order = ResourceEntitlementWindow.append(null, NOW, 30);
    customer = ResourceEntitlementWindow.append(customer, NOW, 60);
    var report = ResourceEntitlementWindow.append(null, NOW, 60);
    assertThat(customer.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
    assertThat(order.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThat(report.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(60)));
  }

  @Test
  void laterActivationPreservesOnlyUnspentDurationAndExpiredActivationRestartsNow() {
    var initial = ResourceEntitlementWindow.append(null, NOW, 30);
    var active = ResourceEntitlementWindow.append(initial, NOW.plus(Duration.ofDays(10)), 60);
    assertThat(active.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
    var expired = ResourceEntitlementWindow.append(initial, NOW.plus(Duration.ofDays(40)), 60);
    assertThat(expired.startsAt()).isEqualTo(NOW.plus(Duration.ofDays(40)));
    assertThat(expired.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(100)));
  }

  @Test
  void expiryIsExclusiveAndRenewalAtTheExactBoundaryStartsAnotherInterval() {
    var initial = ResourceEntitlementWindow.append(null, NOW, 1);
    assertThat(initial.contains(NOW.minusNanos(1))).isFalse();
    assertThat(initial.contains(NOW)).isTrue();
    assertThat(initial.contains(initial.expiresAt().minusNanos(1))).isTrue();
    assertThat(initial.contains(initial.expiresAt())).isFalse();
    var renewed = ResourceEntitlementWindow.append(initial, initial.expiresAt(), 1);
    assertThat(renewed.startsAt()).isEqualTo(initial.expiresAt());
  }

  @Test
  void unlimitedIsExplicitAndIsNotShortenedByAnotherFiniteActivation() {
    var unlimited = ResourceEntitlementWindow.append(null, NOW, null);
    assertThat(unlimited.expiresAt()).isNull();
    assertThat(ResourceEntitlementWindow.append(unlimited, NOW.plusSeconds(1), 30)).isEqualTo(unlimited);
    var finite = ResourceEntitlementWindow.append(null, NOW, 30);
    assertThat(ResourceEntitlementWindow.append(finite, NOW.plusSeconds(1), null).expiresAt()).isNull();
  }

  @Test
  void invalidAndOverflowingDurationsFailWithoutProducingAnEntitlement() {
    for (int days : new int[] {0, -1, Integer.MAX_VALUE}) {
      assertThatThrownBy(() -> ResourceEntitlementWindow.append(null, NOW, days))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new ResourceEntitlementWindow(NOW, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    var existing = ResourceEntitlementWindow.append(null, NOW, 1);
    assertThatThrownBy(() -> ResourceEntitlementWindow.append(existing, NOW.minusSeconds(1), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void daysRemainExactDurationsAcrossCalendarAndDaylightSavingBoundaries() {
    var activation = Instant.parse("2026-03-08T06:30:00Z");
    var window = ResourceEntitlementWindow.append(null, activation, 1);
    assertThat(window.expiresAt()).isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
  }
}

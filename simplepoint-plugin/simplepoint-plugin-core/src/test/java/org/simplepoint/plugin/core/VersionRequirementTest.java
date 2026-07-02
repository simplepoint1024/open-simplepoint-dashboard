package org.simplepoint.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionRequirementTest {

  @Test
  void exactVersionMatchesOnlySameVersion() {
    VersionRequirement requirement = VersionRequirement.parse("1.2.3");

    assertThat(requirement.matches("1.2.3")).isTrue();
    assertThat(requirement.matches("1.2.4")).isFalse();
  }

  @Test
  void comparisonRangeMatchesAllChecks() {
    VersionRequirement requirement = VersionRequirement.parse(">=1.2.0 <2.0.0");

    assertThat(requirement.matches("1.2.0")).isTrue();
    assertThat(requirement.matches("1.9.9")).isTrue();
    assertThat(requirement.matches("2.0.0")).isFalse();
  }

  @Test
  void caretRangeUsesNextBreakingVersion() {
    VersionRequirement requirement = VersionRequirement.parse("^1.2.3");

    assertThat(requirement.matches("1.2.3")).isTrue();
    assertThat(requirement.matches("1.8.0")).isTrue();
    assertThat(requirement.matches("2.0.0")).isFalse();
  }

  @Test
  void tildeRangeUsesNextMinorVersion() {
    VersionRequirement requirement = VersionRequirement.parse("~1.2.3");

    assertThat(requirement.matches("1.2.9")).isTrue();
    assertThat(requirement.matches("1.3.0")).isFalse();
  }

  @Test
  void wildcardRangeMatchesWithinPrefix() {
    VersionRequirement requirement = VersionRequirement.parse("1.2.x");

    assertThat(requirement.matches("1.2.0")).isTrue();
    assertThat(requirement.matches("1.2.9")).isTrue();
    assertThat(requirement.matches("1.3.0")).isFalse();
  }

  @Test
  void blankRequirementMatchesAnyVersion() {
    VersionRequirement requirement = VersionRequirement.parse("");

    assertThat(requirement.matches("99.0.0")).isTrue();
  }
}

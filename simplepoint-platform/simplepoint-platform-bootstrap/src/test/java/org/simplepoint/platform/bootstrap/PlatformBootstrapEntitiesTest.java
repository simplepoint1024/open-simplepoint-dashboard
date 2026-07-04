package org.simplepoint.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.simplepoint.platform.bootstrap.entity.PlatformContributionRecord;
import org.simplepoint.platform.bootstrap.id.PlatformContributionRecordId;
import org.simplepoint.platform.bootstrap.properties.BootstrapContributionSettings;
import org.simplepoint.platform.bootstrap.properties.PlatformBootstrapProperties;

class PlatformBootstrapEntitiesTest {

  @Test
  void contributionSettings_defaultEnabled() {
    BootstrapContributionSettings settings = new BootstrapContributionSettings();

    assertThat(settings.isEnabled()).isTrue();
  }

  @Test
  void platformBootstrapProperties_defaultEnabled() {
    PlatformBootstrapProperties properties = new PlatformBootstrapProperties();

    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.isFailFast()).isTrue();
    assertThat(properties.isServiceEnabled("common")).isTrue();
    assertThat(properties.isServiceEnabled("authorization")).isFalse();
  }

  @Test
  void platformBootstrapProperties_resolvesContributionSettings() {
    PlatformBootstrapProperties properties = new PlatformBootstrapProperties();
    BootstrapContributionSettings settings = new BootstrapContributionSettings();
    settings.setEnabled(false);
    properties.getContributions().put("system-user", settings);
    BootstrapContribution contribution = BootstrapContribution.versioned(
        "rbac-core",
        "system",
        "system-user",
        "1",
        100,
        () -> {}
    );

    assertThat(properties.isContributionEnabled(contribution)).isFalse();
  }

  @Test
  void contributionRecord_statusConstants() {
    assertThat(PlatformContributionRecord.STATUS_RUNNING).isEqualTo("RUNNING");
    assertThat(PlatformContributionRecord.STATUS_APPLIED).isEqualTo("APPLIED");
    assertThat(PlatformContributionRecord.STATUS_FAILED).isEqualTo("FAILED");
  }

  @Test
  void contributionRecordId_settersGetters() {
    PlatformContributionRecordId id = new PlatformContributionRecordId();
    id.setServiceName("common");
    id.setModuleCode("rbac-core");
    id.setContributionType("system");
    id.setContributionKey("system-user");

    assertThat(id.getServiceName()).isEqualTo("common");
    assertThat(id.getModuleCode()).isEqualTo("rbac-core");
    assertThat(id.getContributionType()).isEqualTo("system");
    assertThat(id.getContributionKey()).isEqualTo("system-user");
  }
}

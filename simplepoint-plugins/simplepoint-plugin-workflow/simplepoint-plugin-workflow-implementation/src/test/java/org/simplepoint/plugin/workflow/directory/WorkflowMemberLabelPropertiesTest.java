package org.simplepoint.plugin.workflow.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class WorkflowMemberLabelPropertiesTest {
  @Test
  void derivesBoundedDefaultsWithoutDeploymentSpecificConfiguration() {
    var properties =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate(
                "simplepoint.workflow.member-labels", WorkflowMemberLabelProperties.class);
    assertEquals(8, properties.pagesPerRun());
    assertEquals(500, properties.pagesPerBuild());
    assertEquals(2, properties.concurrentRuns());
    assertEquals(Duration.ofSeconds(10), properties.runTimeout());
    assertEquals(Duration.ofSeconds(60), properties.leaseDuration());
  }

  @Test
  void rejectsUnboundedWorkAndLeaseIntervalsShorterThanTransactionAllowance() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowMemberLabelProperties(
                0, 500, 2, Duration.ofSeconds(10), Duration.ofSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowMemberLabelProperties(
                8, 10001, 2, Duration.ofSeconds(10), Duration.ofSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowMemberLabelProperties(
                8, 500, 17, Duration.ofSeconds(10), Duration.ofSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowMemberLabelProperties(
                8, 500, 2, Duration.ofMinutes(2), Duration.ofMinutes(3)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowMemberLabelProperties(
                8, 500, 2, Duration.ofSeconds(30), Duration.ofSeconds(35)));
  }
}

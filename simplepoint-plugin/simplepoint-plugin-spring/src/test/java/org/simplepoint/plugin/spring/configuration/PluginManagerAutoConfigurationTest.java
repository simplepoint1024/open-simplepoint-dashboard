package org.simplepoint.plugin.spring.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.PluginRuntimeCoordinator;
import org.simplepoint.plugin.api.PluginTaskStore;
import org.simplepoint.plugin.api.PluginsManager;
import org.simplepoint.plugin.api.manifest.PluginManifest;
import org.simplepoint.plugin.core.InMemoryPluginTaskStore;
import org.simplepoint.plugin.core.PluginArtifactVerifier;
import org.simplepoint.plugin.spring.coordination.JdbcPluginOperationEventRecorder;
import org.simplepoint.plugin.spring.coordination.JdbcPluginOperationEventRelay;
import org.simplepoint.plugin.spring.coordination.JdbcPluginOperationEventStore;
import org.simplepoint.plugin.spring.coordination.JdbcPluginRuntimeCoordinator;
import org.simplepoint.plugin.spring.task.JdbcPluginTaskStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PluginManagerAutoConfigurationTest {

  private static final String TRUSTED =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String OTHER =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(PluginManagerAutoConfiguration.class);

  @Test
  void trustedSha256VerifierUsesConfiguredProperties() {
    contextRunner
        .withPropertyValues(
            "plugin.trust.require-known-sha256=true",
            "plugin.trust.sha256[plugin][0]=" + TRUSTED
        )
        .run(context -> {
          assertThat(context).hasSingleBean(PluginsManager.class);
          PluginArtifactVerifier verifier =
              context.getBean("trustedSha256PluginArtifactVerifier", PluginArtifactVerifier.class);

          verifier.verify(artifact(TRUSTED), manifest("plugin"));
          assertThatThrownBy(() -> verifier.verify(artifact(OTHER), manifest("plugin")))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("SHA-256 digest is not trusted");
          assertThatThrownBy(() -> verifier.verify(artifact(OTHER), manifest("unconfigured")))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("does not have a trusted SHA-256 digest configured");
        });
  }

  @Test
  void taskStoreDefaultsToInMemory() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(PluginTaskStore.class);
      assertThat(context.getBean(PluginTaskStore.class)).isInstanceOf(InMemoryPluginTaskStore.class);
    });
  }

  @Test
  void taskStoreUsesJdbcWhenExplicitlyEnabledAndDataSourceExists() {
    contextRunner
        .withBean(DataSource.class, PluginManagerAutoConfigurationTest::dataSource)
        .withPropertyValues("plugin.task-store.jdbc.enabled=true")
        .run(context -> {
          assertThat(context).hasSingleBean(PluginTaskStore.class);
          assertThat(context.getBean(PluginTaskStore.class)).isInstanceOf(JdbcPluginTaskStore.class);
        });
  }

  @Test
  void runtimeCoordinatorUsesJdbcWhenExplicitlyEnabledAndDataSourceExists() {
    contextRunner
        .withBean(DataSource.class, PluginManagerAutoConfigurationTest::dataSource)
        .withPropertyValues("plugin.runtime-coordinator.jdbc.enabled=true")
        .run(context -> assertThat(context.getBeansOfType(PluginRuntimeCoordinator.class).values())
            .anyMatch(JdbcPluginRuntimeCoordinator.class::isInstance));
  }

  @Test
  void runtimeEventsUseJdbcWhenExplicitlyEnabledAndDataSourceExists() {
    contextRunner
        .withBean(DataSource.class, PluginManagerAutoConfigurationTest::dataSource)
        .withPropertyValues(
            "plugin.runtime-events.jdbc.enabled=true",
            "plugin.runtime-events.jdbc.relay-enabled=false",
            "plugin.runtime-events.jdbc.origin-id=node-a")
        .run(context -> {
          assertThat(context).hasSingleBean(JdbcPluginOperationEventStore.class);
          assertThat(context.getBeansOfType(PluginRuntimeCoordinator.class).values())
              .anyMatch(JdbcPluginOperationEventRecorder.class::isInstance);
          assertThat(context).doesNotHaveBean(JdbcPluginOperationEventRelay.class);
        });
  }

  @Test
  void runtimeEventRelayIsEnabledByDefaultWhenJdbcEventsAreEnabled() {
    contextRunner
        .withBean(DataSource.class, PluginManagerAutoConfigurationTest::dataSource)
        .withPropertyValues(
            "plugin.runtime-events.jdbc.enabled=true",
            "plugin.runtime-events.jdbc.origin-id=node-a",
            "plugin.runtime-events.jdbc.poll-interval=1h")
        .run(context -> assertThat(context).hasSingleBean(JdbcPluginOperationEventRelay.class));
  }

  private static PluginArtifact artifact(String sha256) {
    return new PluginArtifact(URI.create("file:/plugin.jar"), 10, sha256);
  }

  private static PluginManifest manifest(String pluginId) {
    PluginManifest manifest = new PluginManifest();
    manifest.setId(pluginId);
    return manifest;
  }

  private static JdbcDataSource dataSource() {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:plugin-autoconfig;MODE=MySQL;DB_CLOSE_DELAY=-1");
    return dataSource;
  }
}

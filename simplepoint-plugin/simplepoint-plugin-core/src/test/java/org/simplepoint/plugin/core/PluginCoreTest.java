package org.simplepoint.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginInstallBatchValidator;
import org.simplepoint.plugin.api.PluginInstallValidator;
import org.simplepoint.plugin.api.PluginInstanceHandler;
import org.simplepoint.plugin.api.PluginLifecycleHandler;
import org.simplepoint.plugin.api.PluginOperationCallback;
import org.simplepoint.plugin.api.PluginOperationContext;
import org.simplepoint.plugin.api.PluginOperationEvent;
import org.simplepoint.plugin.api.PluginRuntimeCoordinator;
import org.simplepoint.plugin.api.PluginStatus;
import org.simplepoint.plugin.api.PluginTaskStore;
import org.simplepoint.plugin.api.management.PluginInstallPlan;
import org.simplepoint.plugin.api.management.PluginInstallPlanIssueCode;
import org.simplepoint.plugin.api.management.PluginOperation;
import org.simplepoint.plugin.api.management.PluginOperationAudit;
import org.simplepoint.plugin.api.management.PluginOperationOutcome;
import org.simplepoint.plugin.api.management.PluginOverview;
import org.simplepoint.plugin.api.management.PluginRegistryView;
import org.simplepoint.plugin.api.management.PluginTaskSnapshot;
import org.simplepoint.plugin.api.management.PluginTaskStatus;
import org.simplepoint.plugin.api.management.PluginUpgradePlan;
import org.simplepoint.plugin.api.manifest.PluginManifest;

class PluginCoreTest {

  private static Plugin createPlugin(String pluginId) {
    PluginManifest manifest = new PluginManifest();
    manifest.setId(pluginId);
    manifest.setName(pluginId);
    manifest.setVersion("1.0.0");
    return new Plugin(URI.create("file:/plugins/" + pluginId + ".jar"), manifest, new HashMap<>());
  }

  // ---- MapPluginsStorage ----

  @Test
  void save_and_find() {
    MapPluginsStorage storage = new MapPluginsStorage();
    Plugin plugin = createPlugin("org.example.plugin");
    storage.save(plugin);
    assertThat(storage.find("org.example.plugin")).isSameAs(plugin);
  }

  @Test
  void list_returnsAllSaved() {
    MapPluginsStorage storage = new MapPluginsStorage();
    storage.save(createPlugin("org.example.alpha"));
    storage.save(createPlugin("org.example.beta"));
    assertThat(storage.list()).hasSize(2);
  }

  @Test
  void remove_deletesPlugin() {
    MapPluginsStorage storage = new MapPluginsStorage();
    storage.save(createPlugin("org.example.plugin"));
    storage.remove("org.example.plugin");
    assertThat(storage.find("org.example.plugin")).isNull();
  }

  @Test
  void find_unknownKey_returnsNull() {
    MapPluginsStorage storage = new MapPluginsStorage();
    assertThat(storage.find("no.such.plugin")).isNull();
  }

  @Test
  void save_overwritesExistingKey() {
    MapPluginsStorage storage = new MapPluginsStorage();
    Plugin first = createPlugin("org.example.plugin");
    Plugin second = createPlugin("org.example.plugin");
    storage.save(first);
    storage.save(second);
    assertThat(storage.list()).hasSize(1);
    assertThat(storage.find("org.example.plugin")).isSameAs(second);
  }

  // ---- PluginContextHolder ----

  private static PluginInstanceHandler handlerWithGroups(String... groupNames) {
    return new PluginInstanceHandler() {
      @Override
      public List<String> groups() {
        return List.of(groupNames);
      }

      @Override
      public void handle(Plugin.PluginInstance instance) {
      }

      @Override
      public void rollback(Plugin.PluginInstance instance) {
      }
    };
  }

  @Test
  void register_and_getHeaders() {
    PluginInstanceHandler handler = handlerWithGroups("controller", "service");
    PluginContextHolder.register(handler);
    try {
      Map<String, PluginInstanceHandler> headers = PluginContextHolder.getHeaders();
      assertThat(headers).containsKey("controller");
      assertThat(headers).containsKey("service");
      assertThat(headers.get("controller")).isSameAs(handler);
    } finally {
      PluginContextHolder.unregister("controller");
      PluginContextHolder.unregister("service");
    }
  }

  @Test
  void unregister_removesGroup() {
    PluginInstanceHandler handler = handlerWithGroups("mygroup");
    PluginContextHolder.register(handler);
    PluginContextHolder.unregister("mygroup");
    assertThat(PluginContextHolder.getHeaders()).doesNotContainKey("mygroup");
  }

  @Test
  void getHeaders_returnsUnmodifiableView() {
    Map<String, PluginInstanceHandler> headers = PluginContextHolder.getHeaders();
    assertThat(headers).isNotNull();
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        headers.put("test", handlerWithGroups("test")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void install_readsPluginYamlManifest() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.manifest
        name: Manifest Plugin
        version: "1.0.0"
        backend:
          packageScan: {}
          instances: {}
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    Plugin plugin = classloader.install(jarPath.toUri());

    assertThat(plugin.manifest().getId()).isEqualTo("org.example.manifest");
    assertThat(plugin.manifest().getName()).isEqualTo("Manifest Plugin");
    assertThat(plugin.status()).isEqualTo(PluginStatus.INSTALLED);
    assertThat(plugin.artifact().uri()).isEqualTo(jarPath.toUri());
    assertThat(plugin.artifact().size()).isEqualTo(Files.size(jarPath));
    assertThat(plugin.artifact().sha256()).hasSize(64);
    assertThat(plugin.registered()).isEmpty();
    assertThat(storage.find("org.example.manifest")).isSameAs(plugin);
  }

  @Test
  void install_rejectsUnknownInstanceGroup() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.unknown-group
        name: Unknown Group Plugin
        version: "1.0.0"
        backend:
          instances:
            scheduler:
              - name: java.lang.String
                className: java.lang.String
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.install(jarPath.toUri()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported plugin backend groups: scheduler");
    assertThat(storage.list()).isEmpty();
  }

  @Test
  void install_rejectsUnknownPackageScanGroup() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.unknown-scan-group
        name: Unknown Scan Group Plugin
        version: "1.0.0"
        backend:
          packageScan:
            scheduler: org.example.scheduler
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.install(jarPath.toUri()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported plugin backend groups: scheduler");
    assertThat(storage.list()).isEmpty();
  }

  @Test
  void install_usesExplicitInstanceGroupOverride() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.group-override
        name: Group Override Plugin
        version: "1.0.0"
        backend:
          instances:
            service:
              - name: java.lang.String
                className: java.lang.String
                group: endpoint
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    PluginInstanceHandler handler = handlerWithGroups("endpoint");
    PluginContextHolder.register(handler);
    try {
      Plugin plugin = classloader.install(jarPath.toUri());

      assertThat(plugin.registered()).containsOnlyKeys("endpoint");
      assertThat(plugin.registered().get("endpoint"))
          .extracting(Plugin.PluginInstance::getBeanGroup)
          .containsExactly("endpoint");
    } finally {
      PluginContextHolder.unregister("endpoint");
    }
  }

  @Test
  void install_rejectsDuplicateInstanceNamesWithinPlugin() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.duplicate-instance
        name: Duplicate Instance Plugin
        version: "1.0.0"
        backend:
          instances:
            service:
              - name: java.lang.String
                className: java.lang.String
              - name: java.lang.String
                className: java.lang.Integer
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    PluginInstanceHandler handler = handlerWithGroups("service");
    PluginContextHolder.register(handler);
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.install(jarPath.toUri()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Duplicate plugin instance names for org.example.duplicate-instance: java.lang.String");
      assertThat(storage.list()).isEmpty();
    } finally {
      PluginContextHolder.unregister("service");
    }
  }

  @Test
  void install_rejectsDuplicateInstanceNamesAcrossGroups() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.duplicate-instance-groups
        name: Duplicate Instance Groups Plugin
        version: "1.0.0"
        backend:
          instances:
            service:
              - name: java.lang.String
                className: java.lang.String
            endpoint:
              - name: java.lang.String
                className: java.lang.String
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    PluginInstanceHandler handler = handlerWithGroups("service", "endpoint");
    PluginContextHolder.register(handler);
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.install(jarPath.toUri()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(
              "Duplicate plugin instance names for org.example.duplicate-instance-groups: java.lang.String");
      assertThat(storage.list()).isEmpty();
    } finally {
      PluginContextHolder.unregister("service");
      PluginContextHolder.unregister("endpoint");
    }
  }

  @Test
  void submit_marksInstalledPluginEnabled() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.status
        name: Status Plugin
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    Plugin plugin = classloader.install(jarPath.toUri());

    assertThat(plugin.status()).isEqualTo(PluginStatus.INSTALLED);
    classloader.submit();
    assertThat(storage.find("org.example.status").status()).isEqualTo(PluginStatus.ENABLED);
  }

  @Test
  void operationAudits_recordInstallAndSubmitSuccess() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.audit
        name: Audit Plugin
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    classloader.install(jarPath.toUri());
    classloader.submit();

    assertThat(classloader.operationAudits())
        .extracting(PluginOperationAudit::operation)
        .containsExactly(PluginOperation.INSTALL, PluginOperation.SUBMIT);
    PluginOperationAudit installAudit = classloader.operationAudits().get(0);
    assertThat(installAudit.outcome()).isEqualTo(PluginOperationOutcome.SUCCESS);
    assertThat(installAudit.pluginId()).isEqualTo("org.example.audit");
    assertThat(installAudit.pluginVersion()).isEqualTo("1.0.0");
    assertThat(installAudit.artifact().sha256()).hasSize(64);
    assertThat(installAudit.durationMillis()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void operationTasks_trackPendingAndSucceededSubmitTasks() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.task-success
        name: Task Success Plugin
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    classloader.install(jarPath.toUri());

    assertThat(classloader.operationTasks())
        .singleElement()
        .satisfies(task -> {
          assertThat(task.pluginId()).isEqualTo("org.example.task-success");
          assertThat(task.operation()).isEqualTo(PluginOperation.SUBMIT);
          assertThat(task.status()).isEqualTo(PluginTaskStatus.PENDING);
          assertThat(task.attempts()).isZero();
          assertThat(task.createdAt()).isNotNull();
        });

    classloader.submit();

    assertThat(classloader.operationTasks())
        .singleElement()
        .satisfies(task -> {
          assertThat(task.status()).isEqualTo(PluginTaskStatus.SUCCEEDED);
          assertThat(task.attempts()).isEqualTo(1);
          assertThat(task.failure()).isNull();
          assertThat(task.updatedAt()).isAfterOrEqualTo(task.createdAt());
        });
  }

  @Test
  void runtimeCoordinator_wrapsInstallAndPublishesOperationEvents() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.coordinated
        name: Coordinated Plugin
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    RecordingRuntimeCoordinator coordinator = new RecordingRuntimeCoordinator();
    PluginClassloader classloader = new PluginClassloader(
        getClass().getClassLoader(),
        storage,
        PluginCompatibilityVerifier.defaults(),
        NoopPluginArtifactVerifier.INSTANCE,
        new InMemoryPluginOperationAuditRecorder(),
        coordinator);

    classloader.install(jarPath.toUri());
    classloader.submit();

    assertThat(coordinator.contexts)
        .extracting(PluginOperationContext::operation)
        .containsExactly(PluginOperation.INSTALL, PluginOperation.SUBMIT);
    assertThat(coordinator.events)
        .extracting(PluginOperationEvent::operation)
        .containsExactly(PluginOperation.INSTALL, PluginOperation.SUBMIT);
    assertThat(coordinator.events.get(0).outcome()).isEqualTo(PluginOperationOutcome.SUCCESS);
    assertThat(coordinator.events.get(0).pluginIds()).containsExactly("org.example.coordinated");
    assertThat(coordinator.events.get(0).operationId())
        .isEqualTo(coordinator.contexts.get(0).operationId());
  }

  @Test
  void operationTasks_areBackedByConfiguredTaskStore() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.custom-task-store
        name: Custom Task Store Plugin
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    RecordingTaskStore taskStore = new RecordingTaskStore();
    PluginClassloader classloader = new PluginClassloader(
        getClass().getClassLoader(),
        storage,
        PluginCompatibilityVerifier.defaults(),
        NoopPluginArtifactVerifier.INSTANCE,
        new InMemoryPluginOperationAuditRecorder(),
        NoopPluginRuntimeCoordinator.INSTANCE,
        taskStore);

    classloader.install(jarPath.toUri());
    classloader.submit();

    assertThat(taskStore.saved)
        .extracting(PluginTaskSnapshot::status)
        .contains(PluginTaskStatus.PENDING, PluginTaskStatus.RUNNING, PluginTaskStatus.SUCCEEDED);
    assertThat(classloader.operationTasks())
        .singleElement()
        .satisfies(task -> {
          assertThat(task.pluginId()).isEqualTo("org.example.custom-task-store");
          assertThat(task.status()).isEqualTo(PluginTaskStatus.SUCCEEDED);
        });
  }

  @Test
  void operationAudits_recordSubmitFailure() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.audit-failure
        name: Audit Failure Plugin
        version: "1.0.0"
        backend:
          instances:
            service:
              - name: java.lang.String
                className: java.lang.String
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    PluginInstanceHandler handler = new PluginInstanceHandler() {
      @Override
      public List<String> groups() {
        return List.of("service");
      }

      @Override
      public void handle(Plugin.PluginInstance instance) {
        throw new IllegalStateException("audit boom");
      }

      @Override
      public void rollback(Plugin.PluginInstance instance) {
      }
    };
    PluginContextHolder.register(handler);
    try {
      classloader.install(jarPath.toUri());

      org.assertj.core.api.Assertions.assertThatThrownBy(classloader::submit)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("audit boom");

      assertThat(classloader.operationAudits())
          .extracting(PluginOperationAudit::operation)
          .containsExactly(PluginOperation.INSTALL, PluginOperation.SUBMIT);
      PluginOperationAudit submitAudit = classloader.operationAudits().get(1);
      assertThat(submitAudit.outcome()).isEqualTo(PluginOperationOutcome.FAILURE);
      assertThat(submitAudit.pluginId()).isEqualTo("org.example.audit-failure");
      assertThat(submitAudit.failure()).isEqualTo("audit boom");
      assertThat(classloader.operationTasks())
          .filteredOn(task -> task.status() == PluginTaskStatus.FAILED)
          .singleElement()
          .satisfies(task -> {
            assertThat(task.pluginId()).isEqualTo("org.example.audit-failure");
            assertThat(task.attempts()).isEqualTo(10);
            assertThat(task.failure()).isEqualTo("audit boom");
          });
      assertThat(classloader.operationTasks())
          .extracting(task -> task.status())
          .contains(PluginTaskStatus.CANCELLED);
    } finally {
      PluginContextHolder.unregister("service");
    }
  }

  @Test
  void inMemoryOperationAuditRecorder_keepsLatestEntriesWithinLimit() {
    InMemoryPluginOperationAuditRecorder recorder = new InMemoryPluginOperationAuditRecorder(2);
    recorder.record(audit("one"));
    recorder.record(audit("two"));
    recorder.record(audit("three"));

    assertThat(recorder.list())
        .extracting(PluginOperationAudit::pluginId)
        .containsExactly("two", "three");
  }

  @Test
  void inMemoryPluginTaskStore_keepsLatestSnapshotsWithinLimit() {
    InMemoryPluginTaskStore store = new InMemoryPluginTaskStore(2);
    store.save(taskSnapshot("one"));
    store.save(taskSnapshot("two"));
    store.save(taskSnapshot("three"));

    assertThat(store.list())
        .extracting(PluginTaskSnapshot::id)
        .containsExactly("two", "three");
  }

  @Test
  void installAll_ordersPluginsByManifestDependencies() throws Exception {
    Path directory = Files.createTempDirectory("simplepoint-plugin-sort-test-");
    createManifestJar(directory, "a-child.jar", """
        id: child
        name: Child
        version: "1.0.0"
        dependencies:
          - id: parent
        """);
    createManifestJar(directory, "z-parent.jar", """
        id: parent
        name: Parent
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    List<Plugin> installed = classloader.installAll(directory.toFile());

    assertThat(installed).extracting(plugin -> plugin.manifest().getId()).containsExactly("parent", "child");
  }

  @Test
  void installAll_rejectsMissingRequiredDependencyBeforePartialInstall() throws Exception {
    Path directory = Files.createTempDirectory("simplepoint-plugin-missing-dependency-test-");
    createManifestJar(directory, "child.jar", """
        id: child
        name: Child
        version: "1.0.0"
        dependencies:
          - id: missing
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.installAll(directory.toFile()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires missing dependency missing");
    assertThat(storage.list()).isEmpty();
  }

  @Test
  void installAll_rejectsDependencyVersionMismatchBeforePartialInstall() throws Exception {
    Path directory = Files.createTempDirectory("simplepoint-plugin-dependency-version-test-");
    createManifestJar(directory, "a-child.jar", """
        id: child
        name: Child
        version: "1.0.0"
        dependencies:
          - id: parent
            version: ">=2.0.0"
        """);
    createManifestJar(directory, "z-parent.jar", """
        id: parent
        name: Parent
        version: "1.9.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.installAll(directory.toFile()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Plugin child requires dependency parent version >=2.0.0, but resolved version is 1.9.0");
    assertThat(storage.list()).isEmpty();
  }

  @Test
  void installAll_rejectsInvalidCandidateBeforePartialInstall() throws Exception {
    Path directory = Files.createTempDirectory("simplepoint-plugin-batch-invalid-test-");
    createManifestJar(directory, "a-valid.jar", """
        id: valid
        name: Valid
        version: "1.0.0"
        """);
    createManifestJar(directory, "z-invalid.jar", """
        id: invalid
        name: Invalid
        version: "1.0.0"
        backend:
          instances:
            scheduler:
              - name: java.lang.String
                className: java.lang.String
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.installAll(directory.toFile()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported plugin backend groups: scheduler");
    assertThat(storage.list()).isEmpty();
  }

  @Test
  void installAll_rejectsBatchInstallValidatorBeforePartialInstall() throws Exception {
    Path directory = Files.createTempDirectory("simplepoint-plugin-batch-validator-test-");
    createManifestJar(directory, "a-one.jar", """
        id: one
        name: One
        version: "1.0.0"
        """);
    createManifestJar(directory, "z-two.jar", """
        id: two
        name: Two
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    PluginInstallBatchValidator validator = plugins -> {
      throw new IllegalStateException("batch rejected " + plugins.size() + " plugins");
    };
    classloader.registerInstallBatchValidator(validator);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.installAll(directory.toFile()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("batch rejected 2 plugins");
    assertThat(storage.list()).isEmpty();
    assertThat(classloader.operationAudits()).isEmpty();
  }

  @Test
  void installAll_rollsBackInstalledCandidatesWhenLaterInstallFails() throws Exception {
    Path directory = Files.createTempDirectory("simplepoint-plugin-batch-rollback-test-");
    createManifestJar(directory, "a-valid.jar", """
        id: valid
        name: Valid
        version: "1.0.0"
        """);
    createManifestJar(directory, "z-missing-class.jar", """
        id: missing-class
        name: Missing Class
        version: "1.0.0"
        backend:
          instances:
            service:
              - name: org.example.MissingService
                className: org.example.MissingService
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    List<String> lifecycleEvents = new ArrayList<>();
    classloader.registerLifecycleHandler(new PluginLifecycleHandler() {
      @Override
      public void installed(Plugin plugin) {
        lifecycleEvents.add("installed:" + plugin.manifest().getId());
      }

      @Override
      public void uninstalling(Plugin plugin) {
        lifecycleEvents.add("uninstalling:" + plugin.manifest().getId());
      }
    });
    PluginContextHolder.register(handlerWithGroups("service"));
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.installAll(directory.toFile()))
          .isInstanceOf(ClassNotFoundException.class)
          .hasMessageContaining("org.example.MissingService");

      assertThat(storage.list()).isEmpty();
      assertThat(lifecycleEvents).isEmpty();
      assertThat(classloader.operationAudits())
          .extracting(PluginOperationAudit::operation)
          .containsExactly(PluginOperation.INSTALL, PluginOperation.INSTALL);
      assertThat(classloader.operationAudits())
          .extracting(PluginOperationAudit::outcome)
          .containsExactly(PluginOperationOutcome.SUCCESS, PluginOperationOutcome.FAILURE);
      classloader.submit();
      assertThat(storage.list()).isEmpty();
    } finally {
      PluginContextHolder.unregister("service");
    }
  }

  @Test
  void planInstallAll_reportsInstallOrderWithoutMutatingRuntimeState() throws Exception {
    Path directory = Files.createTempDirectory("simplepoint-plugin-plan-test-");
    createManifestJar(directory, "a-child.jar", """
        id: child
        name: Child
        version: "1.0.0"
        dependencies:
          - id: parent
        """);
    createManifestJar(directory, "z-parent.jar", """
        id: parent
        name: Parent
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    PluginInstallPlan plan = classloader.planInstallAll(directory.toFile());

    assertThat(plan.installable()).isTrue();
    assertThat(plan.issues()).isEmpty();
    assertThat(plan.plugins()).extracting(item -> item.id()).containsExactly("parent", "child");
    assertThat(plan.plugins()).extracting(item -> item.installOrder()).containsExactly(1, 2);
    assertThat(storage.list()).isEmpty();
    assertThat(classloader.operationAudits()).isEmpty();
  }

  @Test
  void planInstallAll_reportsBatchInstallValidatorIssueWithoutMutatingRuntimeState() throws Exception {
    Path directory = Files.createTempDirectory("simplepoint-plugin-plan-batch-validator-test-");
    createManifestJar(directory, "a-one.jar", """
        id: one
        name: One
        version: "1.0.0"
        """);
    createManifestJar(directory, "z-two.jar", """
        id: two
        name: Two
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.registerInstallBatchValidator(plugins -> {
      throw new IllegalStateException("batch rejected " + plugins.size() + " plugins");
    });

    PluginInstallPlan plan = classloader.planInstallAll(directory.toFile());

    assertThat(plan.installable()).isFalse();
    assertThat(plan.issues())
        .singleElement()
        .satisfies(issue -> {
          assertThat(issue.code()).isEqualTo(PluginInstallPlanIssueCode.INSTALL_VALIDATION_FAILED);
          assertThat(issue.message()).isEqualTo("batch rejected 2 plugins");
        });
    assertThat(plan.plugins()).extracting(item -> item.id()).containsExactly("one", "two");
    assertThat(storage.list()).isEmpty();
    assertThat(classloader.operationAudits()).isEmpty();
  }

  @Test
  void planInstallAll_reportsDependencyVersionIssueWithoutPartialInstall() throws Exception {
    Path directory = Files.createTempDirectory("simplepoint-plugin-plan-version-test-");
    createManifestJar(directory, "a-child.jar", """
        id: child
        name: Child
        version: "1.0.0"
        dependencies:
          - id: parent
            version: ">=2.0.0"
        """);
    createManifestJar(directory, "z-parent.jar", """
        id: parent
        name: Parent
        version: "1.9.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    PluginInstallPlan plan = classloader.planInstallAll(directory.toFile());

    assertThat(plan.installable()).isFalse();
    assertThat(plan.issues())
        .anySatisfy(issue -> {
          assertThat(issue.code()).isEqualTo(PluginInstallPlanIssueCode.DEPENDENCY_RESOLUTION_FAILED);
          assertThat(issue.message()).contains("requires dependency parent version >=2.0.0");
        });
    assertThat(plan.plugins()).extracting(item -> item.id()).contains("child", "parent");
    assertThat(plan.plugins()).filteredOn(item -> "child".equals(item.id()))
        .singleElement()
        .satisfies(child -> assertThat(child.dependencies())
            .singleElement()
            .satisfies(dependency -> {
              assertThat(dependency.id()).isEqualTo("parent");
              assertThat(dependency.versionRequirement()).isEqualTo(">=2.0.0");
              assertThat(dependency.resolvedVersion()).isEqualTo("1.9.0");
              assertThat(dependency.present()).isTrue();
              assertThat(dependency.candidate()).isTrue();
            }));
    assertThat(storage.list()).isEmpty();
  }

  @Test
  void planInstall_reportsInvalidArchiveAsIssue() throws Exception {
    Path invalidJar = Files.createTempFile("simplepoint-plugin-invalid-plan-", ".jar");
    Files.writeString(invalidJar, "not a jar", StandardCharsets.UTF_8);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    PluginInstallPlan plan = classloader.planInstall(invalidJar.toUri());

    assertThat(plan.installable()).isFalse();
    assertThat(plan.issues())
        .singleElement()
        .satisfies(issue -> assertThat(issue.code()).isEqualTo(PluginInstallPlanIssueCode.DESCRIPTOR_INVALID));
    assertThat(plan.plugins()).isEmpty();
    assertThat(storage.list()).isEmpty();
  }

  @Test
  void planInstall_reportsUnsupportedBackendGroupAsIssue() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.plan-unknown-group
        name: Plan Unknown Group Plugin
        version: "1.0.0"
        backend:
          instances:
            scheduler:
              - name: java.lang.String
                className: java.lang.String
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    PluginInstallPlan plan = classloader.planInstall(jarPath.toUri());

    assertThat(plan.installable()).isFalse();
    assertThat(plan.plugins()).singleElement().satisfies(item ->
        assertThat(item.id()).isEqualTo("org.example.plan-unknown-group"));
    assertThat(plan.issues())
        .singleElement()
        .satisfies(issue -> {
          assertThat(issue.pluginId()).isEqualTo("org.example.plan-unknown-group");
          assertThat(issue.code()).isEqualTo(PluginInstallPlanIssueCode.INSTALL_VALIDATION_FAILED);
          assertThat(issue.message()).contains("Unsupported plugin backend groups: scheduler");
        });
    assertThat(storage.list()).isEmpty();
  }

  @Test
  void planInstall_reportsDuplicateExplicitInstancesAsIssue() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.plan-duplicate-instance
        name: Plan Duplicate Instance Plugin
        version: "1.0.0"
        backend:
          instances:
            service:
              - name: java.lang.String
                className: java.lang.String
              - name: java.lang.String
                className: java.lang.Integer
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    PluginInstanceHandler handler = handlerWithGroups("service");
    PluginContextHolder.register(handler);
    try {
      PluginInstallPlan plan = classloader.planInstall(jarPath.toUri());

      assertThat(plan.installable()).isFalse();
      assertThat(plan.issues())
          .singleElement()
          .satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(PluginInstallPlanIssueCode.INSTALL_VALIDATION_FAILED);
            assertThat(issue.message()).contains(
                "Duplicate plugin instance names for org.example.plan-duplicate-instance: java.lang.String");
          });
      assertThat(storage.list()).isEmpty();
    } finally {
      PluginContextHolder.unregister("service");
    }
  }

  @Test
  void planInstall_reportsDuplicateExplicitAndPackageScannedInstancesAsIssue() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.plan-duplicate-scan-instance
        name: Plan Duplicate Scan Instance Plugin
        version: "1.0.0"
        backend:
          packageScan:
            service: org.example.scan
          instances:
            service:
              - name: org.example.scan.Duplicate
                className: org.example.scan.Duplicate
        """, "org/example/scan/Duplicate.class");
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    PluginInstanceHandler handler = handlerWithGroups("service");
    PluginContextHolder.register(handler);
    try {
      PluginInstallPlan plan = classloader.planInstall(jarPath.toUri());

      assertThat(plan.installable()).isFalse();
      assertThat(plan.issues())
          .singleElement()
          .satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(PluginInstallPlanIssueCode.INSTALL_VALIDATION_FAILED);
            assertThat(issue.message()).contains("org.example.scan.Duplicate");
          });
      assertThat(storage.list()).isEmpty();
    } finally {
      PluginContextHolder.unregister("service");
    }
  }

  @Test
  void planInstall_reportsInstallValidatorIssue() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.validator-plan
        name: Validator Plan Plugin
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.registerInstallValidator(plugin -> {
      throw new IllegalStateException("validator rejected " + plugin.manifest().getId());
    });

    PluginInstallPlan plan = classloader.planInstall(jarPath.toUri());

    assertThat(plan.installable()).isFalse();
    assertThat(plan.issues())
        .singleElement()
        .satisfies(issue -> {
          assertThat(issue.code()).isEqualTo(PluginInstallPlanIssueCode.INSTALL_VALIDATION_FAILED);
          assertThat(issue.message()).isEqualTo("validator rejected org.example.validator-plan");
        });
    assertThat(storage.list()).isEmpty();
  }

  @Test
  void install_rejectsInstallValidatorBeforeSavingPlugin() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.validator-install
        name: Validator Install Plugin
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    PluginInstallValidator validator = plugin -> {
      throw new IllegalStateException("validator rejected " + plugin.manifest().getId());
    };
    classloader.registerInstallValidator(validator);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.install(jarPath.toUri()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("validator rejected org.example.validator-install");
    assertThat(storage.list()).isEmpty();
    assertThat(classloader.operationAudits()).singleElement()
        .satisfies(audit -> assertThat(audit.outcome()).isEqualTo(PluginOperationOutcome.FAILURE));
  }

  @Test
  void planInstallAll_missingDirectoryDoesNotCreateDirectory() throws Exception {
    Path missingDirectory = Files.createTempDirectory("simplepoint-plugin-plan-missing-test-").resolve("missing");
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    PluginInstallPlan plan = classloader.planInstallAll(missingDirectory.toFile());

    assertThat(plan.installable()).isTrue();
    assertThat(plan.plugins()).isEmpty();
    assertThat(plan.issues()).isEmpty();
    assertThat(Files.exists(missingDirectory)).isFalse();
  }

  @Test
  void install_rejectsInstalledDependencyVersionMismatch() throws Exception {
    Path parentJar = createManifestJar("""
        id: parent
        name: Parent
        version: "1.9.0"
        """);
    Path childJar = createManifestJar("""
        id: child
        name: Child
        version: "1.0.0"
        dependencies:
          - id: parent
            version: ">=2.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.install(parentJar.toUri());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.install(childJar.toUri()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Plugin child requires dependency parent version >=2.0.0, but resolved version is 1.9.0");
    assertThat(storage.find("parent")).isNotNull();
    assertThat(storage.find("child")).isNull();
  }

  @Test
  void inspect_rejectsIncompatibleCoreVersion() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.incompatible
        name: Incompatible Plugin
        version: "1.0.0"
        coreVersion: ">=2.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(
        getClass().getClassLoader(),
        storage,
        new VersionCompatibilityVerifier(new PluginRuntimeVersions("1.5.0", null))
    );

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.inspect(jarPath.toUri()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires core version >=2.0.0");
  }

  @Test
  void inspect_rejectsInvalidDependencyVersionRequirement() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.invalid-dependency-version
        name: Invalid Dependency Version Plugin
        version: "1.0.0"
        dependencies:
          - id: org.example.parent
            version: ">=abc"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.inspect(jarPath.toUri()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Invalid version requirement '>=abc' for dependency org.example.parent "
                + "for plugin org.example.invalid-dependency-version");
  }

  @Test
  void install_allowsTrustedArtifactDigest() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.trusted
        name: Trusted Plugin
        version: "1.0.0"
        """);
    PluginArtifactHasher hasher = new Sha256PluginArtifactHasher();
    String sha256 = hasher.hash(jarPath.toUri()).sha256();
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(
        getClass().getClassLoader(),
        storage,
        PluginCompatibilityVerifier.defaults(),
        new TrustedSha256PluginArtifactVerifier(Map.of("org.example.trusted", List.of(sha256)), true)
    );

    Plugin plugin = classloader.install(jarPath.toUri());

    assertThat(plugin.artifact().sha256()).isEqualTo(sha256);
    assertThat(storage.find("org.example.trusted")).isSameAs(plugin);
  }

  @Test
  void inspect_rejectsUntrustedArtifactDigest() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.untrusted
        name: Untrusted Plugin
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(
        getClass().getClassLoader(),
        storage,
        PluginCompatibilityVerifier.defaults(),
        new TrustedSha256PluginArtifactVerifier(
            Map.of("org.example.untrusted",
                List.of("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")),
            true)
    );

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.inspect(jarPath.toUri()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHA-256 digest is not trusted");
    assertThat(storage.list()).isEmpty();
  }

  @Test
  void upgrade_replacesInstalledPluginWithNewVersion() throws Exception {
    Path oldJar = createManifestJar("""
        id: org.example.upgrade
        name: Upgrade Plugin
        version: "1.0.0"
        """);
    Path newJar = createManifestJar("""
        id: org.example.upgrade
        name: Upgrade Plugin
        version: "2.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.install(oldJar.toUri());
    classloader.submit();

    Plugin upgraded = classloader.upgrade(newJar.toUri());

    assertThat(upgraded.manifest().getVersion()).isEqualTo("2.0.0");
    assertThat(upgraded.status()).isEqualTo(PluginStatus.ENABLED);
    assertThat(storage.find("org.example.upgrade").path()).isEqualTo(newJar.toUri());
    assertThat(classloader.operationAudits())
        .extracting(PluginOperationAudit::operation)
        .contains(PluginOperation.UPGRADE);
  }

  @Test
  void planUpgrade_reportsUpgradeableCandidateWithoutMutatingRuntimeState() throws Exception {
    Path oldJar = createManifestJar("""
        id: org.example.plan-upgrade
        name: Plan Upgrade Plugin
        version: "1.0.0"
        """);
    Path newJar = createManifestJar("""
        id: org.example.plan-upgrade
        name: Plan Upgrade Plugin
        version: "2.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.install(oldJar.toUri());
    classloader.submit();

    PluginUpgradePlan plan = classloader.planUpgrade(newJar.toUri());

    assertThat(plan.upgradeable()).isTrue();
    assertThat(plan.issues()).isEmpty();
    assertThat(plan.current().version()).isEqualTo("1.0.0");
    assertThat(plan.current().status()).isEqualTo(PluginStatus.ENABLED);
    assertThat(plan.candidate().version()).isEqualTo("2.0.0");
    assertThat(plan.candidate().status()).isNull();
    assertThat(storage.find("org.example.plan-upgrade").manifest().getVersion()).isEqualTo("1.0.0");
    assertThat(classloader.operationAudits())
        .extracting(PluginOperationAudit::operation)
        .doesNotContain(PluginOperation.UPGRADE);
  }

  @Test
  void planUpgrade_reportsMissingInstalledPlugin() throws Exception {
    Path newJar = createManifestJar("""
        id: org.example.not-installed
        name: Missing Current Plugin
        version: "2.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    PluginUpgradePlan plan = classloader.planUpgrade(newJar.toUri());

    assertThat(plan.upgradeable()).isFalse();
    assertThat(plan.current()).isNull();
    assertThat(plan.candidate().id()).isEqualTo("org.example.not-installed");
    assertThat(plan.issues())
        .singleElement()
        .satisfies(issue -> {
          assertThat(issue.code()).isEqualTo(PluginInstallPlanIssueCode.UPGRADE_VALIDATION_FAILED);
          assertThat(issue.message()).contains("not installed");
        });
    assertThat(storage.list()).isEmpty();
  }

  @Test
  void planUpgrade_reportsDependentBlockers() throws Exception {
    Path parentJar = createManifestJar("""
        id: parent-plan
        name: Parent
        version: "1.0.0"
        """);
    Path childJar = createManifestJar("""
        id: child-plan
        name: Child
        version: "1.0.0"
        dependencies:
          - id: parent-plan
        """);
    Path newParentJar = createManifestJar("""
        id: parent-plan
        name: Parent
        version: "2.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.install(parentJar.toUri());
    classloader.install(childJar.toUri());

    PluginUpgradePlan plan = classloader.planUpgrade(newParentJar.toUri());

    assertThat(plan.upgradeable()).isFalse();
    assertThat(plan.blockingDependents()).containsExactly("child-plan");
    assertThat(plan.issues())
        .singleElement()
        .satisfies(issue -> {
          assertThat(issue.code()).isEqualTo(PluginInstallPlanIssueCode.UPGRADE_VALIDATION_FAILED);
          assertThat(issue.message()).contains("child-plan");
        });
    assertThat(storage.find("parent-plan").manifest().getVersion()).isEqualTo("1.0.0");
  }

  @Test
  void upgrade_rejectsInvalidCandidateBeforeUninstallingCurrentPlugin() throws Exception {
    Path oldJar = createManifestJar("""
        id: org.example.precheck-upgrade
        name: Precheck Upgrade Plugin
        version: "1.0.0"
        """);
    Path invalidJar = createManifestJar("""
        id: org.example.precheck-upgrade
        name: Precheck Upgrade Plugin
        version: "2.0.0"
        backend:
          instances:
            scheduler:
              - name: java.lang.String
                className: java.lang.String
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.install(oldJar.toUri());
    classloader.submit();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.upgrade(invalidJar.toUri()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported plugin backend groups: scheduler");

    Plugin current = storage.find("org.example.precheck-upgrade");
    assertThat(current.manifest().getVersion()).isEqualTo("1.0.0");
    assertThat(current.status()).isEqualTo(PluginStatus.ENABLED);
  }

  @Test
  void upgrade_restoresPreviousPluginWhenNewSubmitFails() throws Exception {
    Path oldJar = createManifestJar("""
        id: org.example.rollback
        name: Rollback Plugin
        version: "1.0.0"
        """);
    Path newJar = createManifestJar("""
        id: org.example.rollback
        name: Rollback Plugin
        version: "2.0.0"
        backend:
          instances:
            service:
              - name: java.lang.String
                className: java.lang.String
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.install(oldJar.toUri());
    classloader.submit();
    AtomicInteger handleAttempts = new AtomicInteger();
    PluginInstanceHandler handler = new PluginInstanceHandler() {
      @Override
      public List<String> groups() {
        return List.of("service");
      }

      @Override
      public void handle(Plugin.PluginInstance instance) {
        handleAttempts.incrementAndGet();
        throw new IllegalStateException("new plugin failed");
      }

      @Override
      public void rollback(Plugin.PluginInstance instance) {
      }
    };
    PluginContextHolder.register(handler);
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.upgrade(newJar.toUri()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("new plugin failed");

      Plugin restored = storage.find("org.example.rollback");
      assertThat(handleAttempts).hasValue(10);
      assertThat(restored.manifest().getVersion()).isEqualTo("1.0.0");
      assertThat(restored.status()).isEqualTo(PluginStatus.ENABLED);
      assertThat(restored.path()).isEqualTo(oldJar.toUri());
    } finally {
      PluginContextHolder.unregister("service");
    }
  }

  @Test
  void upgrade_rejectsPluginWithInstalledDependents() throws Exception {
    Path parentJar = createManifestJar("""
        id: parent
        name: Parent
        version: "1.0.0"
        """);
    Path childJar = createManifestJar("""
        id: child
        name: Child
        version: "1.0.0"
        dependencies:
          - id: parent
        """);
    Path newParentJar = createManifestJar("""
        id: parent
        name: Parent
        version: "2.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.install(parentJar.toUri());
    classloader.install(childJar.toUri());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.upgrade(newParentJar.toUri()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("被以下插件依赖");
  }

  @Test
  void registry_reportsDependencyGraphAndOperationAvailability() throws Exception {
    Path parentJar = createManifestJar("""
        id: parent
        name: Parent
        version: "1.0.0"
        """);
    Path childJar = createManifestJar("""
        id: child
        name: Child
        version: "1.0.0"
        dependencies:
          - id: parent
            version: ">=1.0.0 <2.0.0"
          - id: optional-helper
            version: ">=2.0.0"
            optional: true
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.install(parentJar.toUri());
    classloader.install(childJar.toUri());
    classloader.submit();

    PluginRegistryView registry = classloader.registry();

    PluginOverview parent = registry.plugins().stream()
        .filter(plugin -> "parent".equals(plugin.id()))
        .findFirst()
        .orElseThrow();
    PluginOverview child = registry.plugins().stream()
        .filter(plugin -> "child".equals(plugin.id()))
        .findFirst()
        .orElseThrow();
    assertThat(parent.dependents()).containsExactly("child");
    assertThat(parent.uninstallable()).isFalse();
    assertThat(parent.upgradeable()).isFalse();
    assertThat(parent.disableable()).isFalse();
    assertThat(parent.enableable()).isFalse();
    assertThat(child.requiredDependencies()).containsExactly("parent");
    assertThat(child.optionalDependencies()).containsExactly("optional-helper");
    assertThat(child.artifact().sha256()).hasSize(64);
    assertThat(child.artifact().size()).isGreaterThan(0);
    assertThat(child.uninstallable()).isTrue();
    assertThat(child.upgradeable()).isTrue();
    assertThat(child.disableable()).isTrue();
    assertThat(child.enableable()).isFalse();
    assertThat(registry.dependencies()).hasSize(2);
    assertThat(registry.dependencies())
        .anySatisfy(edge -> {
          assertThat(edge.sourcePluginId()).isEqualTo("child");
          assertThat(edge.targetPluginId()).isEqualTo("parent");
          assertThat(edge.optional()).isFalse();
          assertThat(edge.resolved()).isTrue();
          assertThat(edge.versionRequirement()).isEqualTo(">=1.0.0 <2.0.0");
          assertThat(edge.resolvedVersion()).isEqualTo("1.0.0");
          assertThat(edge.versionSatisfied()).isTrue();
        })
        .anySatisfy(edge -> {
          assertThat(edge.sourcePluginId()).isEqualTo("child");
          assertThat(edge.targetPluginId()).isEqualTo("optional-helper");
          assertThat(edge.optional()).isTrue();
          assertThat(edge.resolved()).isFalse();
          assertThat(edge.versionRequirement()).isEqualTo(">=2.0.0");
          assertThat(edge.resolvedVersion()).isNull();
          assertThat(edge.versionSatisfied()).isTrue();
        });
  }

  @Test
  void registry_reportsUnsatisfiedDependencyVersionForExistingState() throws Exception {
    PluginManifest parentManifest = new PluginManifest();
    parentManifest.setId("parent");
    parentManifest.setName("Parent");
    parentManifest.setVersion("1.0.0");
    PluginManifest childManifest = new PluginManifest();
    childManifest.setId("child");
    childManifest.setName("Child");
    childManifest.setVersion("1.0.0");
    PluginManifest.PluginDependency dependency = new PluginManifest.PluginDependency();
    dependency.setId("parent");
    dependency.setVersion(">=2.0.0");
    childManifest.setDependencies(List.of(dependency));
    MapPluginsStorage storage = new MapPluginsStorage();
    storage.save(new Plugin(URI.create("file:/plugins/parent.jar"), parentManifest, new HashMap<>()));
    storage.save(new Plugin(URI.create("file:/plugins/child.jar"), childManifest, new HashMap<>()));
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);

    PluginRegistryView registry = classloader.registry();

    assertThat(registry.dependencies())
        .singleElement()
        .satisfies(edge -> {
          assertThat(edge.sourcePluginId()).isEqualTo("child");
          assertThat(edge.targetPluginId()).isEqualTo("parent");
          assertThat(edge.resolved()).isTrue();
          assertThat(edge.versionRequirement()).isEqualTo(">=2.0.0");
          assertThat(edge.resolvedVersion()).isEqualTo("1.0.0");
          assertThat(edge.versionSatisfied()).isFalse();
        });
  }

  @Test
  void submitFailure_rollsBackPluginAndClearsQueuedTasks() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.failing
        name: Failing Plugin
        version: "1.0.0"
        backend:
          instances:
            service:
              - name: java.lang.String
                className: java.lang.String
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    AtomicInteger handleAttempts = new AtomicInteger();
    AtomicInteger rollbackAttempts = new AtomicInteger();
    PluginInstanceHandler handler = new PluginInstanceHandler() {
      @Override
      public List<String> groups() {
        return List.of("service");
      }

      @Override
      public void handle(Plugin.PluginInstance instance) {
        handleAttempts.incrementAndGet();
        throw new IllegalStateException("boom");
      }

      @Override
      public void rollback(Plugin.PluginInstance instance) {
        rollbackAttempts.incrementAndGet();
      }
    };
    PluginContextHolder.register(handler);
    try {
      classloader.install(jarPath.toUri());

      org.assertj.core.api.Assertions.assertThatThrownBy(classloader::submit)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("boom");

      assertThat(handleAttempts).hasValue(10);
      assertThat(rollbackAttempts).hasValue(1);
      assertThat(storage.find("org.example.failing")).isNull();
      classloader.submit();
    } finally {
      PluginContextHolder.unregister("service");
    }
  }

  @Test
  void submit_and_uninstall_publishLifecycleEvents() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.lifecycle
        name: Lifecycle Plugin
        version: "1.0.0"
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    List<String> events = new ArrayList<>();
    classloader.registerLifecycleHandler(new PluginLifecycleHandler() {
      @Override
      public void installed(Plugin plugin) {
        events.add("installed:" + plugin.manifest().getId());
      }

      @Override
      public void uninstalling(Plugin plugin) {
        events.add("uninstalling:" + plugin.manifest().getId());
      }
    });

    classloader.install(jarPath.toUri());
    classloader.submit();
    classloader.uninstall("org.example.lifecycle");

    assertThat(events).containsExactly(
        "installed:org.example.lifecycle",
        "uninstalling:org.example.lifecycle"
    );
    assertThat(classloader.operationAudits())
        .extracting(PluginOperationAudit::operation)
        .contains(PluginOperation.UNINSTALL);
  }

  @Test
  void disable_and_enable_toggleInstancesAndLifecycleContributions() throws Exception {
    Path jarPath = createManifestJar("""
        id: org.example.toggle
        name: Toggle Plugin
        version: "1.0.0"
        backend:
          instances:
            service:
              - name: java.lang.String
                className: java.lang.String
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    AtomicInteger handles = new AtomicInteger();
    AtomicInteger rollbacks = new AtomicInteger();
    List<String> events = new ArrayList<>();
    PluginInstanceHandler handler = new PluginInstanceHandler() {
      @Override
      public List<String> groups() {
        return List.of("service");
      }

      @Override
      public void handle(Plugin.PluginInstance instance) {
        handles.incrementAndGet();
        instance.instance(new Object());
      }

      @Override
      public void rollback(Plugin.PluginInstance instance) {
        rollbacks.incrementAndGet();
      }
    };
    classloader.registerLifecycleHandler(new PluginLifecycleHandler() {
      @Override
      public void installed(Plugin plugin) {
        events.add("installed:" + plugin.status());
      }

      @Override
      public void uninstalling(Plugin plugin) {
        events.add("uninstalling:" + plugin.status());
      }
    });
    PluginContextHolder.register(handler);
    try {
      classloader.install(jarPath.toUri());
      classloader.submit();
      Plugin enabled = storage.find("org.example.toggle");
      Plugin.PluginInstance instance = firstInstance(enabled);

      assertThat(enabled.status()).isEqualTo(PluginStatus.ENABLED);
      assertThat(handles).hasValue(1);
      assertThat(instance.getInstance()).isNotNull();
      assertThat(instance.getClazz()).isSameAs(String.class);

      Plugin disabled = classloader.disable("org.example.toggle");

      assertThat(disabled.status()).isEqualTo(PluginStatus.DISABLED);
      assertThat(rollbacks).hasValue(1);
      assertThat(instance.getInstance()).isNull();
      assertThat(instance.getClazz()).isSameAs(String.class);

      Plugin reenabled = classloader.enable("org.example.toggle");

      assertThat(reenabled.status()).isEqualTo(PluginStatus.ENABLED);
      assertThat(handles).hasValue(2);
      assertThat(firstInstance(reenabled).getInstance()).isNotNull();
      assertThat(events).containsExactly(
          "installed:INSTALLED",
          "uninstalling:DISABLED",
          "installed:INSTALLED"
      );
      assertThat(classloader.operationAudits())
          .extracting(PluginOperationAudit::operation)
          .contains(PluginOperation.DISABLE, PluginOperation.ENABLE);
    } finally {
      PluginContextHolder.unregister("service");
    }
  }

  @Test
  void disable_rejectsPluginWithActiveDependents() throws Exception {
    Path parentJar = createManifestJar("""
        id: parent
        name: Parent
        version: "1.0.0"
        """);
    Path childJar = createManifestJar("""
        id: child
        name: Child
        version: "1.0.0"
        dependencies:
          - id: parent
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.install(parentJar.toUri());
    classloader.install(childJar.toUri());
    classloader.submit();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.disable("parent"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已启用插件依赖");

    classloader.disable("child");
    assertThat(classloader.disable("parent").status()).isEqualTo(PluginStatus.DISABLED);
  }

  @Test
  void enable_rejectsPluginWhenRequiredDependencyIsDisabled() throws Exception {
    Path parentJar = createManifestJar("""
        id: parent
        name: Parent
        version: "1.0.0"
        """);
    Path childJar = createManifestJar("""
        id: child
        name: Child
        version: "1.0.0"
        dependencies:
          - id: parent
        """);
    MapPluginsStorage storage = new MapPluginsStorage();
    PluginClassloader classloader = new PluginClassloader(getClass().getClassLoader(), storage);
    classloader.install(parentJar.toUri());
    classloader.install(childJar.toUri());
    classloader.submit();
    classloader.disable("child");
    classloader.disable("parent");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> classloader.enable("child"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("必需依赖 parent 尚未启用");
  }

  private static Path createManifestJar(String manifest) throws Exception {
    Path jarPath = Files.createTempFile("simplepoint-plugin-test-", ".jar");
    writeManifestJar(jarPath, manifest);
    return jarPath;
  }

  private static Path createManifestJar(String manifest, String... entries) throws Exception {
    Path jarPath = Files.createTempFile("simplepoint-plugin-test-", ".jar");
    writeManifestJar(jarPath, manifest, entries);
    return jarPath;
  }

  private static Path createManifestJar(Path directory, String fileName, String manifest) throws Exception {
    Path jarPath = directory.resolve(fileName);
    writeManifestJar(jarPath, manifest);
    return jarPath;
  }

  private static void writeManifestJar(Path jarPath, String manifest) throws Exception {
    writeManifestJar(jarPath, manifest, new String[0]);
  }

  private static void writeManifestJar(Path jarPath, String manifest, String... entries) throws Exception {
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
      output.putNextEntry(new JarEntry("META-INF/plugin.yaml"));
      output.write(manifest.getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
      for (String entry : entries) {
        output.putNextEntry(new JarEntry(entry));
        output.closeEntry();
      }
    }
  }

  private static Plugin.PluginInstance firstInstance(Plugin plugin) {
    return plugin.registered().values().stream()
        .flatMap(Set::stream)
        .findFirst()
        .orElseThrow();
  }

  private static PluginOperationAudit audit(String pluginId) {
    Instant now = Instant.now();
    return new PluginOperationAudit(
        pluginId,
        PluginOperation.INSTALL,
        PluginOperationOutcome.SUCCESS,
        pluginId,
        "1.0.0",
        URI.create("file:/plugins/" + pluginId + ".jar"),
        null,
        now,
        now,
        0,
        null);
  }

  private static PluginTaskSnapshot taskSnapshot(String id) {
    Instant now = Instant.now();
    return new PluginTaskSnapshot(
        id,
        id,
        PluginOperation.SUBMIT,
        PluginTaskStatus.PENDING,
        0,
        now,
        now,
        null);
  }

  private static final class RecordingRuntimeCoordinator implements PluginRuntimeCoordinator {

    private final List<PluginOperationContext> contexts = new ArrayList<>();
    private final List<PluginOperationEvent> events = new ArrayList<>();

    @Override
    public <T> T coordinate(
        PluginOperationContext context,
        PluginOperationCallback<T> callback
    ) throws Exception {
      contexts.add(context);
      return callback.execute();
    }

    @Override
    public void publish(PluginOperationEvent event) {
      events.add(event);
    }
  }

  private static final class RecordingTaskStore implements PluginTaskStore {

    private final Map<String, PluginTaskSnapshot> current = new LinkedHashMap<>();
    private final List<PluginTaskSnapshot> saved = new ArrayList<>();

    @Override
    public void save(PluginTaskSnapshot snapshot) {
      current.put(snapshot.id(), snapshot);
      saved.add(snapshot);
    }

    @Override
    public List<PluginTaskSnapshot> list() {
      return new ArrayList<>(current.values());
    }
  }
}

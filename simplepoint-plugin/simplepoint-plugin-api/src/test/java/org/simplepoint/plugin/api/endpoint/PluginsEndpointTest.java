package org.simplepoint.plugin.api.endpoint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplepoint.api.data.Storage;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginInstallBatchValidator;
import org.simplepoint.plugin.api.PluginInstallValidator;
import org.simplepoint.plugin.api.PluginInstanceHandler;
import org.simplepoint.plugin.api.PluginLifecycleHandler;
import org.simplepoint.plugin.api.PluginsManager;
import org.simplepoint.plugin.api.exception.PluginExistException;
import org.simplepoint.plugin.api.management.PluginInstallPlan;
import org.simplepoint.plugin.api.management.PluginOperationAudit;
import org.simplepoint.plugin.api.management.PluginRegistryView;
import org.simplepoint.plugin.api.management.PluginTaskSnapshot;
import org.simplepoint.plugin.api.management.PluginUpgradePlan;
import org.simplepoint.plugin.api.manifest.PluginManifest;
import org.springframework.mock.web.MockMultipartFile;

class PluginsEndpointTest {

  @TempDir
  private Path pluginsDir;

  @Test
  void installPersistsUploadToAutoloaderDirectory() throws Exception {
    PluginManifest manifest = manifest("org.example.plugin", "1.0.0");
    StubPluginsManager manager = new StubPluginsManager(manifest);
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);
    byte[] content = "plugin jar".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile upload =
        new MockMultipartFile("plugin", "uploaded.jar", "application/java-archive", content);

    Response<Plugin> response = endpoint.install(upload);

    assertSame(manager.installedPlugin, response.getBody());
    Path target = pluginsDir.resolve("org.example.plugin-1.0.0.jar");
    assertTrue(Files.exists(target));
    assertArrayEquals(content, Files.readAllBytes(target));
    assertNotNull(manager.inspectedUri);
    assertEquals(target.toUri(), manager.installedUri);
    assertTrue(manager.submitted);
  }

  @Test
  void installRejectsAlreadyInstalledManifestIdBeforeReplacingJar() throws Exception {
    PluginManifest manifest = manifest("org.example.plugin", "1.0.0");
    StubPluginsManager manager = new StubPluginsManager(manifest);
    manager.storage.save(new Plugin(URI.create("file:/plugins/current.jar"), manifest, new HashMap<>()));
    Path target = pluginsDir.resolve("org.example.plugin-1.0.0.jar");
    Files.writeString(target, "current", StandardCharsets.UTF_8);
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);
    MockMultipartFile upload =
        new MockMultipartFile("plugin", "uploaded.jar", "application/java-archive",
            "new".getBytes(StandardCharsets.UTF_8));

    assertThrows(PluginExistException.class, () -> endpoint.install(upload));

    assertEquals("current", Files.readString(target, StandardCharsets.UTF_8));
    assertNull(manager.installedUri);
    assertFalse(manager.submitted);
  }

  @Test
  void installRemovesPersistedJarWhenManagerInstallFails() throws Exception {
    PluginManifest manifest = manifest("org.example.plugin", "1.0.0");
    StubPluginsManager manager = new StubPluginsManager(manifest);
    manager.installFailure = new IllegalStateException("missing dependency");
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);
    MockMultipartFile upload =
        new MockMultipartFile("plugin", "uploaded.jar", "application/java-archive",
            "new".getBytes(StandardCharsets.UTF_8));

    IllegalStateException failure = assertThrows(IllegalStateException.class, () -> endpoint.install(upload));

    assertSame(manager.installFailure, failure);
    assertEquals(pluginsDir.resolve("org.example.plugin-1.0.0.jar").toUri(), manager.installedUri);
    assertFalse(Files.exists(pluginsDir.resolve("org.example.plugin-1.0.0.jar")));
    assertFalse(manager.submitted);
  }

  @Test
  void planDelegatesTemporaryUploadWithoutPersistingOrInstalling() throws Exception {
    PluginManifest manifest = manifest("org.example.plugin", "1.0.0");
    StubPluginsManager manager = new StubPluginsManager(manifest);
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);
    MockMultipartFile upload =
        new MockMultipartFile("plugin", "uploaded.jar", "application/java-archive",
            "new".getBytes(StandardCharsets.UTF_8));

    Response<PluginInstallPlan> response = endpoint.plan(upload);

    assertSame(manager.installPlan, response.getBody());
    assertNotNull(manager.plannedUri);
    assertNull(manager.inspectedUri);
    assertNull(manager.installedUri);
    assertFalse(manager.submitted);
    assertFalse(Files.exists(pluginsDir.resolve("org.example.plugin-1.0.0.jar")));
  }

  @Test
  void planUpgradeDelegatesTemporaryUploadWithoutPersistingOrUpgrading() throws Exception {
    PluginManifest manifest = manifest("org.example.plugin", "2.0.0");
    StubPluginsManager manager = new StubPluginsManager(manifest);
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);
    MockMultipartFile upload =
        new MockMultipartFile("plugin", "uploaded.jar", "application/java-archive",
            "new".getBytes(StandardCharsets.UTF_8));

    Response<PluginUpgradePlan> response = endpoint.planUpgrade(upload);

    assertSame(manager.upgradePlan, response.getBody());
    assertNotNull(manager.plannedUpgradeUri);
    assertNull(manager.inspectedUri);
    assertNull(manager.upgradedUri);
    assertFalse(manager.submitted);
    assertFalse(Files.exists(pluginsDir.resolve("org.example.plugin-2.0.0.jar")));
  }

  @Test
  void upgradePersistsNewArtifactAndRemovesPreviousArtifact() throws Exception {
    PluginManifest oldManifest = manifest("org.example.plugin", "1.0.0");
    PluginManifest newManifest = manifest("org.example.plugin", "2.0.0");
    StubPluginsManager manager = new StubPluginsManager(newManifest);
    Path oldArtifact = pluginsDir.resolve("org.example.plugin-1.0.0.jar");
    Files.writeString(oldArtifact, "old", StandardCharsets.UTF_8);
    manager.storage.save(new Plugin(oldArtifact.toUri(), oldManifest, new HashMap<>()));
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);
    byte[] content = "new".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile upload =
        new MockMultipartFile("plugin", "uploaded.jar", "application/java-archive", content);

    Response<Plugin> response = endpoint.upgrade(upload);

    Path target = pluginsDir.resolve("org.example.plugin-2.0.0.jar");
    assertSame(manager.upgradedPlugin, response.getBody());
    assertTrue(Files.exists(target));
    assertArrayEquals(content, Files.readAllBytes(target));
    assertFalse(Files.exists(oldArtifact));
    assertEquals(target.toUri(), manager.upgradedUri);
  }

  @Test
  void upgradeRejectsUploadThatWouldOverwriteCurrentArtifact() throws Exception {
    PluginManifest manifest = manifest("org.example.plugin", "1.0.0");
    StubPluginsManager manager = new StubPluginsManager(manifest);
    Path oldArtifact = pluginsDir.resolve("org.example.plugin-1.0.0.jar");
    Files.writeString(oldArtifact, "old", StandardCharsets.UTF_8);
    manager.storage.save(new Plugin(oldArtifact.toUri(), manifest, new HashMap<>()));
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);
    MockMultipartFile upload =
        new MockMultipartFile("plugin", "uploaded.jar", "application/java-archive",
            "new".getBytes(StandardCharsets.UTF_8));

    assertThrows(IllegalStateException.class, () -> endpoint.upgrade(upload));

    assertEquals("old", Files.readString(oldArtifact, StandardCharsets.UTF_8));
    assertNull(manager.upgradedUri);
  }

  @Test
  void upgradeRemovesNewArtifactWhenManagerUpgradeFails() throws Exception {
    PluginManifest oldManifest = manifest("org.example.plugin", "1.0.0");
    PluginManifest newManifest = manifest("org.example.plugin", "2.0.0");
    StubPluginsManager manager = new StubPluginsManager(newManifest);
    manager.upgradeFailure = new IllegalStateException("upgrade failed");
    Path oldArtifact = pluginsDir.resolve("org.example.plugin-1.0.0.jar");
    Files.writeString(oldArtifact, "old", StandardCharsets.UTF_8);
    manager.storage.save(new Plugin(oldArtifact.toUri(), oldManifest, new HashMap<>()));
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);
    MockMultipartFile upload =
        new MockMultipartFile("plugin", "uploaded.jar", "application/java-archive",
            "new".getBytes(StandardCharsets.UTF_8));

    IllegalStateException failure = assertThrows(IllegalStateException.class, () -> endpoint.upgrade(upload));

    Path target = pluginsDir.resolve("org.example.plugin-2.0.0.jar");
    assertSame(manager.upgradeFailure, failure);
    assertEquals(target.toUri(), manager.upgradedUri);
    assertFalse(Files.exists(target));
    assertTrue(Files.exists(oldArtifact));
  }

  @Test
  void pluginsReturnsRegistryView() {
    PluginManifest manifest = manifest("org.example.plugin", "1.0.0");
    StubPluginsManager manager = new StubPluginsManager(manifest);
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);

    Response<PluginRegistryView> response = endpoint.plugins();

    assertSame(manager.registry, response.getBody());
  }

  @Test
  void operationsReturnsOperationAudits() {
    PluginManifest manifest = manifest("org.example.plugin", "1.0.0");
    StubPluginsManager manager = new StubPluginsManager(manifest);
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);

    Response<List<PluginOperationAudit>> response = endpoint.operations();

    assertSame(manager.operationAudits, response.getBody());
  }

  @Test
  void tasksReturnsRuntimeTaskSnapshots() {
    PluginManifest manifest = manifest("org.example.plugin", "1.0.0");
    StubPluginsManager manager = new StubPluginsManager(manifest);
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);

    Response<List<PluginTaskSnapshot>> response = endpoint.tasks();

    assertSame(manager.operationTasks, response.getBody());
  }

  @Test
  void enableDelegatesToManager() {
    PluginManifest manifest = manifest("org.example.plugin", "1.0.0");
    StubPluginsManager manager = new StubPluginsManager(manifest);
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);

    Response<Plugin> response = endpoint.enable("org.example.plugin");

    assertSame(manager.enabledPlugin, response.getBody());
    assertEquals("org.example.plugin", manager.enabledId);
  }

  @Test
  void disableDelegatesToManager() {
    PluginManifest manifest = manifest("org.example.plugin", "1.0.0");
    StubPluginsManager manager = new StubPluginsManager(manifest);
    PluginsEndpoint endpoint = new PluginsEndpoint(manager, pluginsDir.toString(), false);

    Response<Plugin> response = endpoint.disable("org.example.plugin");

    assertSame(manager.disabledPlugin, response.getBody());
    assertEquals("org.example.plugin", manager.disabledId);
  }

  private static PluginManifest manifest(String id, String version) {
    PluginManifest manifest = new PluginManifest();
    manifest.setId(id);
    manifest.setName(id);
    manifest.setVersion(version);
    return manifest;
  }

  private static final class StubPluginsManager implements PluginsManager {

    private final PluginManifest manifest;
    private final TestStorage storage = new TestStorage();
    private URI inspectedUri;
    private URI plannedUri;
    private URI installedUri;
    private boolean submitted;
    private Plugin installedPlugin;
    private URI upgradedUri;
    private Plugin upgradedPlugin;
    private String enabledId;
    private Plugin enabledPlugin;
    private String disabledId;
    private Plugin disabledPlugin;
    private RuntimeException installFailure;
    private RuntimeException upgradeFailure;
    private PluginInstallPlan installPlan = new PluginInstallPlan(null, true, List.of(), List.of());
    private URI plannedUpgradeUri;
    private PluginUpgradePlan upgradePlan = new PluginUpgradePlan(null, true, null, null, List.of(), List.of());
    private PluginRegistryView registry = new PluginRegistryView(List.of(), List.of());
    private List<PluginOperationAudit> operationAudits = List.of();
    private List<PluginTaskSnapshot> operationTasks = List.of();

    private StubPluginsManager(PluginManifest manifest) {
      this.manifest = manifest;
    }

    @Override
    public List<Plugin> installAll(File path) {
      return List.of();
    }

    @Override
    public PluginInstallPlan planInstallAll(File path) {
      return installPlan;
    }

    @Override
    public Plugin install(URI uri) {
      this.installedUri = uri;
      if (installFailure != null) {
        throw installFailure;
      }
      this.installedPlugin = new Plugin(uri, manifest, new HashMap<>());
      this.storage.save(this.installedPlugin);
      return this.installedPlugin;
    }

    @Override
    public PluginInstallPlan planInstall(URI uri) {
      this.plannedUri = uri;
      return installPlan;
    }

    @Override
    public Plugin upgrade(URI uri) {
      this.upgradedUri = uri;
      if (upgradeFailure != null) {
        throw upgradeFailure;
      }
      this.upgradedPlugin = new Plugin(uri, manifest, new HashMap<>());
      this.storage.save(this.upgradedPlugin);
      return this.upgradedPlugin;
    }

    @Override
    public PluginUpgradePlan planUpgrade(URI uri) {
      this.plannedUpgradeUri = uri;
      return upgradePlan;
    }

    @Override
    public Plugin enable(String pluginId) {
      this.enabledId = pluginId;
      this.enabledPlugin = new Plugin(URI.create("file:/plugins/" + pluginId + ".jar"), manifest, new HashMap<>());
      this.storage.save(this.enabledPlugin);
      return this.enabledPlugin;
    }

    @Override
    public Plugin disable(String pluginId) {
      this.disabledId = pluginId;
      this.disabledPlugin = new Plugin(URI.create("file:/plugins/" + pluginId + ".jar"), manifest, new HashMap<>());
      this.storage.save(this.disabledPlugin);
      return this.disabledPlugin;
    }

    @Override
    public PluginManifest inspect(URI uri) {
      this.inspectedUri = uri;
      return manifest;
    }

    @Override
    public PluginRegistryView registry() {
      return registry;
    }

    @Override
    public List<PluginOperationAudit> operationAudits() {
      return operationAudits;
    }

    @Override
    public List<PluginTaskSnapshot> operationTasks() {
      return operationTasks;
    }

    @Override
    public void submit() {
      this.submitted = true;
    }

    @Override
    public void uninstall(String pluginId) {
      this.storage.remove(pluginId);
    }

    @Override
    public void registerHandle(PluginInstanceHandler handle) {
    }

    @Override
    public void registerInstallValidator(PluginInstallValidator validator) {
    }

    @Override
    public void registerInstallBatchValidator(PluginInstallBatchValidator validator) {
    }

    @Override
    public void unregisterInstallValidator(PluginInstallValidator validator) {
    }

    @Override
    public void unregisterInstallBatchValidator(PluginInstallBatchValidator validator) {
    }

    @Override
    public void registerLifecycleHandler(PluginLifecycleHandler handler) {
    }

    @Override
    public void unregisterLifecycleHandler(PluginLifecycleHandler handler) {
    }

    @Override
    public Storage<Plugin> getStorage() {
      return storage;
    }
  }

  private static final class TestStorage implements Storage<Plugin> {

    private final HashMap<String, Plugin> plugins = new HashMap<>();

    @Override
    public Plugin save(Plugin data) {
      plugins.put(data.manifest().getId(), data);
      return data;
    }

    @Override
    public void remove(String key) {
      plugins.remove(key);
    }

    @Override
    public Plugin find(String key) {
      return plugins.get(key);
    }

    @Override
    public List<Plugin> list() {
      return new ArrayList<>(plugins.values());
    }
  }
}

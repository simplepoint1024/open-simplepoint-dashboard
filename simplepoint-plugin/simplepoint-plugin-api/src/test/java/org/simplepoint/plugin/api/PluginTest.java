package org.simplepoint.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.manifest.PluginManifest;

class PluginTest {

  private static PluginManifest manifest(String id) {
    PluginManifest manifest = new PluginManifest();
    manifest.setId(id);
    manifest.setName(id);
    manifest.setVersion("1.0.0");
    return manifest;
  }

  @Test
  void recordFieldsAccessible() {
    URI path = URI.create("file:///plugins/test.jar");
    PluginManifest manifest = manifest("test-plugin");
    Map<String, Set<Plugin.PluginInstance>> registered = new HashMap<>();
    Plugin plugin = new Plugin(path, manifest, registered);

    assertEquals(path, plugin.path());
    assertSame(manifest, plugin.manifest());
    assertNotNull(plugin.registered());
    assertEquals(PluginStatus.RESOLVED, plugin.status());
    assertNull(plugin.failure());
    assertEquals(path, plugin.artifact().uri());
    assertEquals(-1, plugin.artifact().size());
    assertNull(plugin.artifact().sha256());
  }

  @Test
  void registeredReturnsUnmodifiableView() {
    URI path = URI.create("file:///plugins/test.jar");
    Map<String, Set<Plugin.PluginInstance>> registered = new HashMap<>();
    registered.put("service", new HashSet<>());
    Plugin plugin = new Plugin(path, manifest("test-plugin"), registered);

    assertThrows(UnsupportedOperationException.class,
        () -> plugin.registered().put("another", new HashSet<>()));
  }

  @Test
  void pluginInstanceConstructorAndGetters() {
    Plugin.PluginInstance inst = new Plugin.PluginInstance("myBean", "com.example.MyBean", "service");
    assertEquals("myBean", inst.getName());
    assertEquals("com.example.MyBean", inst.getBeanClassName());
    assertEquals("service", inst.getBeanGroup());
    assertNull(inst.getInstance());
    assertNull(inst.getClazz());
  }

  @Test
  void pluginInstanceSetInstance_onlyFirst() {
    Plugin.PluginInstance inst = new Plugin.PluginInstance("bean", "com.Cls", "grp");
    Object obj1 = new Object();
    Object obj2 = new Object();
    inst.instance(obj1);
    inst.instance(obj2);
    assertSame(obj1, inst.getInstance());
  }

  @Test
  void pluginInstanceSetClazz_onlyFirst() {
    Plugin.PluginInstance inst = new Plugin.PluginInstance("bean", "com.Cls", "grp");
    inst.classes(String.class);
    inst.classes(Integer.class);
    assertEquals(String.class, inst.getClazz());
  }

  @Test
  void pluginInstanceClearInstance() {
    Plugin.PluginInstance inst = new Plugin.PluginInstance("bean", "com.Cls", "grp");
    inst.instance(new Object());
    inst.classes(String.class);
    inst.clearInstance();
    assertNull(inst.getInstance());
    assertNull(inst.getClazz());
  }

  @Test
  void pluginInstanceClearRuntimeInstancePreservesLoadedClass() {
    Plugin.PluginInstance inst = new Plugin.PluginInstance("bean", "com.Cls", "grp");
    inst.instance(new Object());
    inst.classes(String.class);
    inst.clearRuntimeInstance();
    assertNull(inst.getInstance());
    assertSame(String.class, inst.getClazz());
  }

  @Test
  void manifestEquality() {
    assertEquals(manifest("p1"), manifest("p1"));
  }

  @Test
  void withStatusCreatesCopyWithStatus() {
    Plugin plugin = new Plugin(URI.create("file:///plugins/test.jar"),
        manifest("test-plugin"), new HashMap<>());

    Plugin installed = plugin.withStatus(PluginStatus.INSTALLED);

    assertEquals(PluginStatus.INSTALLED, installed.status());
    assertNull(installed.failure());
    assertSame(plugin.manifest(), installed.manifest());
    assertSame(plugin.artifact(), installed.artifact());
  }

  @Test
  void withFailureCreatesFailedCopy() {
    Plugin plugin = new Plugin(URI.create("file:///plugins/test.jar"),
        manifest("test-plugin"), new HashMap<>());

    Plugin failed = plugin.withFailure("boom");

    assertEquals(PluginStatus.FAILED, failed.status());
    assertEquals("boom", failed.failure());
    assertSame(plugin.manifest(), failed.manifest());
    assertSame(plugin.artifact(), failed.artifact());
  }

  @Test
  void artifactConstructorUsesArtifactUriAsPath() {
    PluginArtifact artifact = new PluginArtifact(URI.create("file:///plugins/test.jar"), 42, "abc");

    Plugin plugin = new Plugin(artifact, manifest("test-plugin"), new HashMap<>());

    assertSame(artifact, plugin.artifact());
    assertEquals(artifact.uri(), plugin.path());
  }
}

package org.simplepoint.plugin.api;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginTest {

  @Test
  void recordFieldsAccessible() {
    URI path = URI.create("file:///plugins/test.jar");
    Plugin.PluginMetadata meta = new Plugin.PluginMetadata();
    meta.setPid("test-pid");
    Map<String, Set<Plugin.PluginInstance>> registered = new HashMap<>();
    Plugin plugin = new Plugin(path, meta, registered);

    assertEquals(path, plugin.path());
    assertSame(meta, plugin.metadata());
    assertNotNull(plugin.registered());
  }

  @Test
  void registeredReturnsUnmodifiableView() {
    URI path = URI.create("file:///plugins/test.jar");
    Map<String, Set<Plugin.PluginInstance>> registered = new HashMap<>();
    registered.put("service", new HashSet<>());
    Plugin plugin = new Plugin(path, new Plugin.PluginMetadata(), registered);

    assertThrows(UnsupportedOperationException.class,
        () -> plugin.registered().put("another", new HashSet<>()));
  }

  @Test
  void pluginMetadataSettersGetters() {
    Plugin.PluginMetadata m = new Plugin.PluginMetadata();
    m.setPid("pid1");
    m.setName("my-plugin");
    m.setVersion("1.0.0");
    m.setAuthor("Author");
    m.setDeclaration("decl");
    m.setEmail("a@b.com");
    m.setDocument("doc");
    m.setPhone("123456");
    m.setPackageName("com.example");
    m.setAutoRegister("true");
    m.setDependencies(List.of("dep1", "dep2"));

    assertEquals("pid1", m.getPid());
    assertEquals("my-plugin", m.getName());
    assertEquals("1.0.0", m.getVersion());
    assertEquals("Author", m.getAuthor());
    assertEquals("decl", m.getDeclaration());
    assertEquals("a@b.com", m.getEmail());
    assertEquals("doc", m.getDocument());
    assertEquals("123456", m.getPhone());
    assertEquals("com.example", m.getPackageName());
    assertEquals("true", m.getAutoRegister());
    assertEquals(List.of("dep1", "dep2"), m.getDependencies());
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
  void pluginMetadataEquality() {
    Plugin.PluginMetadata m1 = new Plugin.PluginMetadata();
    m1.setPid("p1");
    Plugin.PluginMetadata m2 = new Plugin.PluginMetadata();
    m2.setPid("p1");
    assertEquals(m1, m2);
  }
}

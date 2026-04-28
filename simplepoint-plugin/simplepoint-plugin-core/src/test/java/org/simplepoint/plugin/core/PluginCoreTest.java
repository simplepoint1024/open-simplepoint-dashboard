package org.simplepoint.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginInstanceHandler;

class PluginCoreTest {

  private static Plugin createPlugin(String packageName) {
    Plugin.PluginMetadata meta = new Plugin.PluginMetadata();
    meta.setPackageName(packageName);
    return new Plugin(URI.create("file:/plugins/" + packageName + ".jar"), meta, new java.util.HashMap<>());
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
      public void handle(Plugin.PluginInstance instance) {}
      @Override
      public void rollback(Plugin.PluginInstance instance) {}
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
}

package org.simplepoint.plugin.api.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URI;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.manifest.PluginManifest;

class PluginExistExceptionTest {

  private static Plugin plugin() {
    PluginManifest manifest = new PluginManifest();
    manifest.setId("test-plugin");
    manifest.setName("test-plugin");
    manifest.setVersion("1.0.0");
    return new Plugin(URI.create("file:///test.jar"), manifest, new HashMap<>());
  }

  @Test
  void messageIsPreserved() {
    Plugin plugin = plugin();
    PluginExistException ex = new PluginExistException("plugin already exists", plugin);
    assertEquals("plugin already exists", ex.getMessage());
  }

  @Test
  void pluginIsPreserved() {
    Plugin plugin = plugin();
    PluginExistException ex = new PluginExistException("msg", plugin);
    assertSame(plugin, ex.getPlugin());
  }

  @Test
  void isCheckedException() {
    assertInstanceOf(Exception.class,
        new PluginExistException("msg", plugin()));
  }

  @Test
  void nullPluginAllowed() {
    PluginExistException ex = new PluginExistException("no plugin", null);
    assertNull(ex.getPlugin());
    assertEquals("no plugin", ex.getMessage());
  }
}

package org.simplepoint.plugin.api.exception;

import java.net.URI;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.Plugin;

import static org.junit.jupiter.api.Assertions.*;

class PluginExistExceptionTest {

  @Test
  void messageIsPreserved() {
    Plugin plugin = new Plugin(URI.create("file:///test.jar"), new Plugin.PluginMetadata(), new HashMap<>());
    PluginExistException ex = new PluginExistException("plugin already exists", plugin);
    assertEquals("plugin already exists", ex.getMessage());
  }

  @Test
  void pluginIsPreserved() {
    Plugin plugin = new Plugin(URI.create("file:///test.jar"), new Plugin.PluginMetadata(), new HashMap<>());
    PluginExistException ex = new PluginExistException("msg", plugin);
    assertSame(plugin, ex.getPlugin());
  }

  @Test
  void isCheckedException() {
    assertInstanceOf(Exception.class,
        new PluginExistException("msg",
            new Plugin(URI.create("file:///test.jar"), new Plugin.PluginMetadata(), new HashMap<>())));
  }

  @Test
  void nullPluginAllowed() {
    PluginExistException ex = new PluginExistException("no plugin", null);
    assertNull(ex.getPlugin());
    assertEquals("no plugin", ex.getMessage());
  }
}

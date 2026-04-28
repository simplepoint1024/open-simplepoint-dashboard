package org.simplepoint.plugin.api.exception;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.Plugin;

import static org.junit.jupiter.api.Assertions.*;

class ClassExistExceptionTest {

  @Test
  void messageContainsClassName() {
    Map<String, Set<Plugin.PluginInstance>> data = new HashMap<>();
    data.put("service", new HashSet<>());
    ClassExistException ex = new ClassExistException("com.example.MyClass", data);
    assertEquals("class com.example.MyClass already exist!", ex.getMessage());
  }

  @Test
  void dataIsPreserved() {
    Map<String, Set<Plugin.PluginInstance>> data = new HashMap<>();
    ClassExistException ex = new ClassExistException("com.Foo", data);
    assertSame(data, ex.getData());
  }

  @Test
  void isRuntimeException() {
    assertInstanceOf(RuntimeException.class,
        new ClassExistException("com.Foo", new HashMap<>()));
  }
}

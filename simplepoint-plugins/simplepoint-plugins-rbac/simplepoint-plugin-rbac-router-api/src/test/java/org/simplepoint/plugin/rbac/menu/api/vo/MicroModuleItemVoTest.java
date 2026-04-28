package org.simplepoint.plugin.rbac.menu.api.vo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MicroModuleItemVoTest {

  @Test
  void defaultConstructorAndSetters() {
    MicroModuleItemVo vo = new MicroModuleItemVo();
    vo.setName("common");
    vo.setEntry("http://localhost:3001/mf.js");
    assertEquals("common", vo.getName());
    assertEquals("http://localhost:3001/mf.js", vo.getEntry());
  }

  @Test
  void parameterizedConstructor() {
    MicroModuleItemVo vo = new MicroModuleItemVo("host", "http://host/mf.js");
    assertEquals("host", vo.getName());
    assertEquals("http://host/mf.js", vo.getEntry());
  }

  @Test
  void equalityBasedOnFields() {
    MicroModuleItemVo a = new MicroModuleItemVo("x", "y");
    MicroModuleItemVo b = new MicroModuleItemVo("x", "y");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void notEqualWhenFieldsDiffer() {
    MicroModuleItemVo a = new MicroModuleItemVo("x", "y");
    MicroModuleItemVo b = new MicroModuleItemVo("x", "z");
    assertNotEquals(a, b);
  }

  @Test
  void toStringContainsFields() {
    MicroModuleItemVo vo = new MicroModuleItemVo("audit", "http://audit/mf.js");
    String str = vo.toString();
    assertTrue(str.contains("audit"));
  }
}

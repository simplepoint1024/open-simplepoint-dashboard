package org.simplepoint.data.datasource.context;

import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.datasource.exception.DataSourceNotFoundException;

import static org.junit.jupiter.api.Assertions.*;

class DataSourceContextHolderTest {

  @BeforeEach
  void setUp() throws Exception {
    DataSourceContextHolder.clear();
    resetDefaultKey();
    DataSourceContextHolder.getAllProperties().clear();
  }

  @AfterEach
  void tearDown() throws Exception {
    DataSourceContextHolder.clear();
    resetDefaultKey();
    DataSourceContextHolder.getAllProperties().clear();
  }

  private void resetDefaultKey() throws Exception {
    Field f = DataSourceContextHolder.class.getDeclaredField("defaultDataSourceKey");
    f.setAccessible(true);
    f.set(null, null);
  }

  @Test
  void setAndGet() {
    DataSourceContextHolder.set("tenant1");
    assertEquals("tenant1", DataSourceContextHolder.get());
  }

  @Test
  void clearRemovesThreadLocalValue() {
    DataSourceContextHolder.set("tenant1");
    DataSourceContextHolder.clear();
    assertNull(DataSourceContextHolder.get());
  }

  @Test
  void getReturnsNullWhenNotSet() {
    assertNull(DataSourceContextHolder.get());
  }

  @Test
  void setDefaultDataSourceKey_firstTime_succeeds() {
    DataSourceContextHolder.setDefaultDataSourceKey("primary");
    assertEquals("primary", DataSourceContextHolder.getDefaultDataSourceKey());
  }

  @Test
  void setDefaultDataSourceKey_twice_throws() {
    DataSourceContextHolder.setDefaultDataSourceKey("primary");
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> DataSourceContextHolder.setDefaultDataSourceKey("secondary"));
    assertTrue(ex.getMessage().contains("primary"));
  }

  @Test
  void getDefaultDataSourceKey_whenNotSet_throws() {
    assertThrows(IllegalStateException.class, DataSourceContextHolder::getDefaultDataSourceKey);
  }

  @Test
  void getAllProperties_returnsMap() {
    assertNotNull(DataSourceContextHolder.getAllProperties());
  }

  @Test
  void getDataSource_whenPropertiesNotFound_throwsDataSourceNotFoundException() {
    assertThrows(DataSourceNotFoundException.class,
        () -> DataSourceContextHolder.getDataSource("nonexistent"));
  }
}

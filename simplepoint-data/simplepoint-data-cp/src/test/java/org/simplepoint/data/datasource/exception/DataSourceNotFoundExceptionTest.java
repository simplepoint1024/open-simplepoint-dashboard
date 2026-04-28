package org.simplepoint.data.datasource.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataSourceNotFoundExceptionTest {

  @Test
  void defaultConstructorMessage() {
    DataSourceNotFoundException ex = new DataSourceNotFoundException();
    assertEquals("The expected data source was not found!", ex.getMessage());
  }

  @Test
  void customMessage() {
    DataSourceNotFoundException ex = new DataSourceNotFoundException("DataSource not found: tenant1");
    assertEquals("DataSource not found: tenant1", ex.getMessage());
  }

  @Test
  void isRuntimeException() {
    assertInstanceOf(RuntimeException.class, new DataSourceNotFoundException());
  }

  @Test
  void customMessageIsRuntime() {
    assertInstanceOf(RuntimeException.class, new DataSourceNotFoundException("msg"));
  }
}

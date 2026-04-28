package org.simplepoint.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.ApplicationClassLoader;
import org.simplepoint.core.ApplicationContextHolder;

class ApplicationContextHolderTest {

  @BeforeEach
  @AfterEach
  void resetStaticState() throws Exception {
    Field field = ApplicationContextHolder.class.getDeclaredField("classloader");
    field.setAccessible(true);
    field.set(null, null);
  }

  @Test
  void getClassloader_beforeSet_returnsNull() {
    assertThat(ApplicationContextHolder.getClassloader()).isNull();
  }

  @Test
  void setClassloader_storesLoader() throws Exception {
    ApplicationClassLoader loader = createLoader();
    ApplicationContextHolder.setClassloader(loader);
    assertThat(ApplicationContextHolder.getClassloader()).isSameAs(loader);
  }

  @Test
  void setClassloader_doesNotOverwriteOnceSet() throws Exception {
    ApplicationClassLoader first = createLoader();
    ApplicationClassLoader second = createLoader();
    ApplicationContextHolder.setClassloader(first);
    ApplicationContextHolder.setClassloader(second);
    assertThat(ApplicationContextHolder.getClassloader()).isSameAs(first);
  }

  @Test
  void setClassloader_withNull_doesNotSet() {
    ApplicationContextHolder.setClassloader(null);
    assertThat(ApplicationContextHolder.getClassloader()).isNull();
  }

  private ApplicationClassLoader createLoader() throws Exception {
    return new ApplicationClassLoader(new java.net.URL[0], Thread.currentThread().getContextClassLoader());
  }
}

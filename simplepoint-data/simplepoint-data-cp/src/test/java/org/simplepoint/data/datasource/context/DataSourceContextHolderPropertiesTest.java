package org.simplepoint.data.datasource.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.datasource.properties.SimpleDataSourceProperties;

/**
 * Additional tests for {@link DataSourceContextHolder} covering putProperties/getProperties,
 * refresh, remove, and ThreadLocal isolation.
 */
class DataSourceContextHolderPropertiesTest {

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

  // ── putProperties / getProperties ─────────────────────────────────────────

  @Test
  void putPropertiesShouldStoreAndGetPropertiesByName() {
    SimpleDataSourceProperties props = new SimpleDataSourceProperties();

    DataSourceContextHolder.putProperties("db1", props);

    assertEquals(props, DataSourceContextHolder.getProperties("db1"));
  }

  @Test
  void getPropertiesShouldReturnNullWhenNotRegistered() {
    assertNull(DataSourceContextHolder.getProperties("unknown"));
  }

  @Test
  void putPropertiesShouldOverwriteExistingEntry() {
    SimpleDataSourceProperties original = new SimpleDataSourceProperties();
    SimpleDataSourceProperties replacement = new SimpleDataSourceProperties();

    DataSourceContextHolder.putProperties("db1", original);
    DataSourceContextHolder.putProperties("db1", replacement);

    assertEquals(replacement, DataSourceContextHolder.getProperties("db1"));
  }

  @Test
  void getAllPropertiesShouldContainAllRegisteredEntries() {
    SimpleDataSourceProperties p1 = new SimpleDataSourceProperties();
    SimpleDataSourceProperties p2 = new SimpleDataSourceProperties();

    DataSourceContextHolder.putProperties("db1", p1);
    DataSourceContextHolder.putProperties("db2", p2);

    assertEquals(2, DataSourceContextHolder.getAllProperties().size());
    assertEquals(p1, DataSourceContextHolder.getAllProperties().get("db1"));
    assertEquals(p2, DataSourceContextHolder.getAllProperties().get("db2"));
  }

  // ── remove ────────────────────────────────────────────────────────────────

  @Test
  void removeShouldDeletePropertiesEntry() {
    SimpleDataSourceProperties props = new SimpleDataSourceProperties();
    DataSourceContextHolder.putProperties("db1", props);

    DataSourceContextHolder.remove("db1");

    assertNull(DataSourceContextHolder.getProperties("db1"));
  }

  @Test
  void removeShouldBeIdempotentWhenKeyDoesNotExist() {
    // Should not throw
    DataSourceContextHolder.remove("nonexistent");
  }

  @Test
  void removeShouldNotAffectOtherEntries() {
    SimpleDataSourceProperties p1 = new SimpleDataSourceProperties();
    SimpleDataSourceProperties p2 = new SimpleDataSourceProperties();
    DataSourceContextHolder.putProperties("db1", p1);
    DataSourceContextHolder.putProperties("db2", p2);

    DataSourceContextHolder.remove("db1");

    assertNull(DataSourceContextHolder.getProperties("db1"));
    assertEquals(p2, DataSourceContextHolder.getProperties("db2"));
  }

  // ── refresh ───────────────────────────────────────────────────────────────

  @Test
  void refreshShouldReplaceOldPropertiesWithNewOnes() {
    SimpleDataSourceProperties oldProps = new SimpleDataSourceProperties();
    SimpleDataSourceProperties newProps = new SimpleDataSourceProperties();
    DataSourceContextHolder.putProperties("db1", oldProps);

    DataSourceContextHolder.refresh("db1", newProps);

    assertEquals(newProps, DataSourceContextHolder.getProperties("db1"));
  }

  @Test
  void refreshShouldWorkForNewKeyNotPreviouslyRegistered() {
    SimpleDataSourceProperties newProps = new SimpleDataSourceProperties();

    DataSourceContextHolder.refresh("new-db", newProps);

    assertEquals(newProps, DataSourceContextHolder.getProperties("new-db"));
  }

  // ── ThreadLocal isolation ─────────────────────────────────────────────────

  @Test
  void lookupKeyShouldBeIsolatedAcrossThreads() throws InterruptedException {
    DataSourceContextHolder.set("main-thread-key");

    AtomicReference<String> childThreadValue = new AtomicReference<>();
    Thread child = new Thread(() -> childThreadValue.set(DataSourceContextHolder.get()));
    child.start();
    child.join();

    // Child thread should see null (its own ThreadLocal, not inherited)
    assertNull(childThreadValue.get());
    // Main thread should still see its own value
    assertEquals("main-thread-key", DataSourceContextHolder.get());
  }

  @Test
  void lookupKeyShouldBeIndependentPerThread() throws InterruptedException {
    AtomicReference<String> thread1Value = new AtomicReference<>();
    AtomicReference<String> thread2Value = new AtomicReference<>();

    Thread t1 = new Thread(() -> {
      DataSourceContextHolder.set("tenant-A");
      // Small sleep to ensure overlap with t2
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      thread1Value.set(DataSourceContextHolder.get());
    });

    Thread t2 = new Thread(() -> {
      DataSourceContextHolder.set("tenant-B");
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      thread2Value.set(DataSourceContextHolder.get());
    });

    t1.start();
    t2.start();
    t1.join();
    t2.join();

    assertEquals("tenant-A", thread1Value.get());
    assertEquals("tenant-B", thread2Value.get());
  }
}

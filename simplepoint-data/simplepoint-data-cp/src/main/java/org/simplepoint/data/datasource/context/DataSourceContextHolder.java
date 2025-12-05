package org.simplepoint.data.datasource.context;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.data.datasource.exception.DataSourceNotFoundException;
import org.simplepoint.data.datasource.properties.SimpleDataSourceProperties;

/**
 * Production-level dynamic DataSource manager.
 * Supports:
 * - Tenant-level DataSource caching
 * - Dynamic refresh (Consul / DB config)
 * - Multi-DB-type support
 * - Schema-per-tenant (optional)
 * - Safe connection pool shutdown
 */
@Slf4j
public class DataSourceContextHolder {
  private static String defaultDataSourceKey;

  /**
   * ThreadLocal to hold the current DataSource lookup key.
   */
  private static final ThreadLocal<String> lookupKey = new ThreadLocal<>();

  /**
   * Stores DataSource configuration (properties only).
   */
  private static final Map<String, SimpleDataSourceProperties> propertiesMap = new ConcurrentHashMap<>();

  /**
   * Cache of initialized DataSource instances.
   */
  private static final Map<String, DataSource> dataSourceCache = new ConcurrentHashMap<>();

  /**
   * Set the current DataSource lookup key.
   */
  public static void set(String key) {
    lookupKey.set(key);
  }

  /**
   * Get the current DataSource lookup key.
   */
  public static String get() {
    return lookupKey.get();
  }

  /**
   * Clear the current DataSource lookup key.
   */
  public static void clear() {
    lookupKey.remove();
  }

  /**
   * Store DataSource properties configuration.
   */
  public static void putProperties(String name, SimpleDataSourceProperties props) {
    propertiesMap.put(name, props);
  }

  /**
   * Get DataSource properties configuration.
   */
  public static SimpleDataSourceProperties getProperties(String name) {
    return propertiesMap.get(name);
  }

  /**
   * Get all DataSource properties configurations.
   */
  public static Map<String, SimpleDataSourceProperties> getAllProperties() {
    return propertiesMap;
  }


  /**
   * Get or create DataSource (cached).
   */
  public static DataSource getDataSource(String name) {
    return dataSourceCache.computeIfAbsent(name, key -> {
      SimpleDataSourceProperties props = propertiesMap.get(key);
      if (props == null) {
        throw new DataSourceNotFoundException("DataSource not found: " + key);
      }
      log.info("Creating DataSource for key: {}", key);
      return props.initializeDataSourceBuilder().build();
    });
  }

  /**
   * Remove DataSource and close connection pool safely.
   */
  public static void remove(String name) {
    DataSource ds = dataSourceCache.remove(name);
    if (ds != null) {
      closeDataSource(ds);
    }
    propertiesMap.remove(name);
    log.info("Removed DataSource: {}", name);
  }

  /**
   * Set default DataSource key.
   */
  public static void setDefaultDataSourceKey(String key) {
    if (defaultDataSourceKey == null) {
      defaultDataSourceKey = key;
      return;
    }
    throw new IllegalStateException("Default DataSource key is already set: " + defaultDataSourceKey);
  }

  /**
   * Get default DataSource key.
   */
  public static String getDefaultDataSourceKey() {
    if (defaultDataSourceKey == null) {
      throw new IllegalStateException("Default DataSource key is not set yet.");
    }
    return defaultDataSourceKey;
  }

  /**
   * Get default DataSource.
   */
  public static DataSource getDefaultDataSource() {
    return getDataSource(getDefaultDataSourceKey());
  }

  /**
   * Refresh DataSource (e.g., Consul config changed).
   */
  public static void refresh(String name, SimpleDataSourceProperties newProps) {
    log.info("Refreshing DataSource: {}", name);
    remove(name); // close old pool
    putProperties(name, newProps);
    // new DataSource will be created lazily on next get()
  }

  private static void closeDataSource(DataSource ds) {
    try {
      if (ds instanceof Closeable closeable) {
        closeable.close();
        return;
      }

      // HikariCP
      if (ds.getClass().getName().contains("HikariDataSource")) {
        ds.getClass().getMethod("close").invoke(ds);
        return;
      }

      // Druid
      if (ds.getClass().getName().contains("DruidDataSource")) {
        ds.getClass().getMethod("close").invoke(ds);
        return;
      }

      // Tomcat JDBC
      if (ds.getClass().getName().contains("org.apache.tomcat.jdbc.pool.DataSource")) {
        ds.getClass().getMethod("close").invoke(ds);
      }

    } catch (Exception e) {
      log.warn("Failed to close DataSource: {}", e.getMessage());
    }
  }
}

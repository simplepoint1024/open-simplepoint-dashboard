package org.simplepoint.plugin.dna.core.service.support;

import java.io.PrintWriter;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.simplepoint.core.ApplicationClassLoader;
import org.simplepoint.core.ApplicationContextHolder;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.simplepoint.data.datasource.properties.SimpleDataSourceProperties;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.spi.JdbcManagedDataSourceFactory;
import org.springframework.stereotype.Component;

/**
 * Default runtime datasource factory backed by SimpleDataSource.
 */
@Component
public class DefaultJdbcManagedDataSourceFactory implements JdbcManagedDataSourceFactory {

  private final JdbcDriverArtifactManager artifactManager;

  /**
   * Creates a default managed datasource factory.
   *
   * @param artifactManager driver artifact manager
   */
  public DefaultJdbcManagedDataSourceFactory(final JdbcDriverArtifactManager artifactManager) {
    this.artifactManager = artifactManager;
  }

  /** {@inheritDoc} */
  @Override
  public boolean supports(final JdbcDriverDefinition driver) {
    return true;
  }

  /** {@inheritDoc} */
  @Override
  public SimpleDataSource create(
      final JdbcDriverDefinition driver,
      final JdbcDataSourceDefinition dataSource
  ) {
    JdbcDriverDefinition resolvedDriver = artifactManager.ensureDownloaded(driver);
    ApplicationClassLoader classLoader = new ApplicationClassLoader(
        artifactManager.resolveArtifactUrls(resolvedDriver),
        resolveParentClassLoader()
    );
    SimpleDataSourceProperties properties = new SimpleDataSourceProperties();
    properties.setBeanClassLoader(classLoader);
    properties.setDriverClassName(resolvedDriver.getDriverClassName());
    properties.setUrl(dataSource.getJdbcUrl());
    properties.setUsername(dataSource.getUsername());
    properties.setPassword(dataSource.getPassword());
    DataSource delegate = withContextClassLoader(classLoader, () -> properties.initializeDataSourceBuilder().build());
    applyConnectionProperties(delegate, dataSource.getConnectionProperties());
    return new SimpleDataSource(new ClassLoaderAwareDataSource(delegate, classLoader));
  }

  private static ClassLoader resolveParentClassLoader() {
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    if (context != null) {
      return context;
    }
    ClassLoader application = ApplicationContextHolder.getClassloader();
    if (application != null) {
      return application;
    }
    return DefaultJdbcManagedDataSourceFactory.class.getClassLoader();
  }

  private static void applyConnectionProperties(final DataSource delegate, final String rawProperties) {
    String normalized = trimToNull(rawProperties);
    if (normalized == null) {
      return;
    }
    Properties properties = parseConnectionProperties(normalized);
    if (properties.isEmpty()) {
      return;
    }
    if (invoke(delegate, "setDataSourceProperties", new Class<?>[]{Properties.class}, properties)) {
      return;
    }
    boolean applied = false;
    for (String name : properties.stringPropertyNames()) {
      applied = invoke(delegate, "addDataSourceProperty", new Class<?>[]{String.class, Object.class}, name,
          properties.getProperty(name)) || applied;
    }
    if (applied) {
      return;
    }
    if (invoke(delegate, "setConnectionProperties", new Class<?>[]{String.class}, normalized)) {
      return;
    }
    throw new IllegalStateException("当前数据源实现不支持额外连接属性");
  }

  private static Properties parseConnectionProperties(final String rawProperties) {
    Properties properties = new Properties();
    try (StringReader reader = new StringReader(rawProperties)) {
      properties.load(reader);
      return properties;
    } catch (Exception ex) {
      throw new IllegalArgumentException("连接属性格式不正确，请使用 Java Properties 格式", ex);
    }
  }

  private static boolean invoke(
      final Object target,
      final String methodName,
      final Class<?>[] parameterTypes,
      final Object... arguments
  ) {
    try {
      Method method = target.getClass().getMethod(methodName, parameterTypes);
      method.invoke(target, arguments);
      return true;
    } catch (NoSuchMethodException ex) {
      return false;
    } catch (Exception ex) {
      throw new IllegalStateException("设置连接属性失败: " + ex.getMessage(), ex);
    }
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static <T> T withContextClassLoader(
      final ClassLoader classLoader,
      final CheckedSupplier<T> supplier
  ) {
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(classLoader);
    try {
      return supplier.get();
    } catch (Exception ex) {
      throw new IllegalStateException("构建数据源失败: " + ex.getMessage(), ex);
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  @FunctionalInterface
  private interface CheckedSupplier<T> {
    T get() throws Exception;
  }

  private static final class ClassLoaderAwareDataSource implements DataSource {

    private final DataSource delegate;

    private final ClassLoader classLoader;

    private ClassLoaderAwareDataSource(final DataSource delegate, final ClassLoader classLoader) {
      this.delegate = delegate;
      this.classLoader = classLoader;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return withContextClassLoader(classLoader, delegate::getConnection);
    }

    @Override
    public Connection getConnection(final String username, final String password) throws SQLException {
      return withContextClassLoader(classLoader, () -> delegate.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(final PrintWriter out) throws SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(final int seconds) throws SQLException {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) throws SQLException {
      return delegate.isWrapperFor(iface);
    }

    private static <T> T withContextClassLoader(
        final ClassLoader classLoader,
        final SqlSupplier<T> supplier
    ) throws SQLException {
      ClassLoader original = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(classLoader);
      try {
        return supplier.get();
      } finally {
        Thread.currentThread().setContextClassLoader(original);
      }
    }
  }

  @FunctionalInterface
  private interface SqlSupplier<T> {
    T get() throws SQLException;
  }
}

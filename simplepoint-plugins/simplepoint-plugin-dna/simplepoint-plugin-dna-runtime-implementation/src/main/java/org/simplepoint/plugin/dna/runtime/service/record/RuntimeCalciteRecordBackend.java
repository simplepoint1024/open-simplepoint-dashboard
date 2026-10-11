package org.simplepoint.plugin.dna.runtime.service.record;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.simplepoint.data.calcite.core.query.CalciteQueryEngine;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.CalciteUpdateResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider;
import org.simplepoint.plugin.dna.runtime.service.schema.ManagedRuntimeDataSourceProvider.ManagedRuntimeDataSource;
import org.simplepoint.plugin.dna.runtime.service.schema.RuntimeSchemaDialect;
import org.springframework.stereotype.Component;

/** Executes all Record SQL through Calcite and owns physical transaction boundaries. */
@Component
public class RuntimeCalciteRecordBackend {

  private static final String CATALOG = "runtime";

  private final ManagedRuntimeDataSourceProvider dataSourceProvider;
  private final CalciteQueryEngine queryEngine;

  /** Creates the Calcite backend over platform-managed datasources. */
  public RuntimeCalciteRecordBackend(
      final ManagedRuntimeDataSourceProvider dataSourceProvider,
      final CalciteQueryEngine queryEngine
  ) {
    this.dataSourceProvider = dataSourceProvider;
    this.queryEngine = queryEngine;
  }

  /** Executes one bounded read against a transient model-only Calcite catalog. */
  CalciteQueryResult query(
      final RuntimeRecordModel model,
      final RecordSelectPlan plan,
      final int timeoutMs
  ) {
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(model.target())) {
      return queryEngine.execute(
          new CalciteQueryRequest(
              plan.sql(),
              null,
              plan.maxRows(),
              timeoutMs,
              plan.parameters()
          ),
          catalog(new QueryConnectionDataSource(handle.dataSource(), handle.runtimeDialect()), handle)
      );
    } catch (DnaRuntimeException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw executionFailed("Calcite record query failed", exception);
    }
  }

  /** Executes every statement on one physical connection and commits only after all match. */
  RecordBatchExecutionResult execute(
      final RuntimeRecordModel model,
      final List<RecordSqlStatement> statements,
      final int timeoutMs
  ) {
    long startedAt = System.nanoTime();
    try (ManagedRuntimeDataSource handle = dataSourceProvider.open(model.target());
         Connection physical = handle.dataSource().getConnection()) {
      boolean originalAutoCommit = physical.getAutoCommit();
      int originalIsolation = physical.getTransactionIsolation();
      try {
        physical.setAutoCommit(false);
        handle.runtimeDialect().prepareRecordTransaction(physical);
        DataSource transactionDataSource = new SingleConnectionDataSource(physical);
        CalciteSchemaConfigurer catalog = catalog(transactionDataSource, handle);
        List<Integer> affected = new ArrayList<>(statements.size());
        int pushedStatements = 0;
        for (int index = 0; index < statements.size(); index++) {
          RecordSqlStatement statement = statements.get(index);
          CalciteUpdateResult result = queryEngine.executeUpdate(
              new CalciteUpdateRequest(
                  statement.sql(),
                  null,
                  timeoutMs,
                  statement.parameters()
              ),
              catalog
          );
          if (result.affectedRows() != 1) {
            throw new RecordMutationMissException(index);
          }
          affected.add(result.affectedRows());
          pushedStatements += result.pushedSqls().size();
        }
        physical.commit();
        return new RecordBatchExecutionResult(
            affected,
            elapsed(startedAt),
            pushedStatements
        );
      } catch (RuntimeException | SQLException exception) {
        rollback(physical, exception);
        throw exception;
      } finally {
        restore(physical, originalAutoCommit, originalIsolation);
      }
    } catch (RecordMutationMissException | DnaRuntimeException exception) {
      throw exception;
    } catch (RuntimeException | SQLException exception) {
      throw executionFailed("Calcite record transaction failed", exception);
    }
  }

  private static CalciteSchemaConfigurer catalog(
      final DataSource dataSource,
      final ManagedRuntimeDataSource handle
  ) {
    return root -> root.add(
        CATALOG,
        JdbcSchema.create(
            root,
            CATALOG,
            dataSource,
            handle.metadataCatalog(),
            handle.metadataSchema()
        )
    );
  }

  private static void rollback(final Connection connection, final Throwable failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  private static void restore(
      final Connection connection,
      final boolean autoCommit,
      final int isolation
  ) {
    try {
      if (connection.getTransactionIsolation() != isolation) {
        connection.setTransactionIsolation(isolation);
      }
      connection.setAutoCommit(autoCommit);
    } catch (SQLException ignored) {
      // Closing the physical connection is the final safety boundary after a completed rollback.
    }
  }

  private static long elapsed(final long startedAt) {
    return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
  }

  private static DnaRuntimeException executionFailed(
      final String message,
      final Throwable cause
  ) {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_EXECUTION_FAILED,
        message,
        cause
    );
  }

  /** DataSource view whose borrowed connection belongs to this backend transaction. */
  private static final class SingleConnectionDataSource implements DataSource {

    private final Connection connection;

    private SingleConnectionDataSource(final Connection connection) {
      this.connection = connection;
    }

    @Override
    public Connection getConnection() {
      return closeSuppressing(connection);
    }

    @Override
    public Connection getConnection(final String username, final String password) {
      return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(final PrintWriter out) {
      // No independent pool is configured by this transaction-scoped adapter.
    }

    @Override
    public void setLoginTimeout(final int seconds) {
      // The underlying managed datasource owns connection acquisition policy.
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
      if (iface.isInstance(this)) {
        return iface.cast(this);
      }
      throw new SQLException("Not a wrapper for " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) {
      return iface.isInstance(this);
    }

    private static Connection closeSuppressing(final Connection physical) {
      return (Connection) Proxy.newProxyInstance(
          Connection.class.getClassLoader(),
          new Class<?>[] {Connection.class},
          (proxy, method, arguments) -> {
            if (method.getName().equals("close") && method.getParameterCount() == 0) {
              return null;
            }
            if (method.getName().equals("isClosed") && method.getParameterCount() == 0) {
              return physical.isClosed();
            }
            try {
              return method.invoke(physical, arguments);
            } catch (InvocationTargetException exception) {
              throw exception.getCause();
            }
          }
      );
    }
  }

  /** Applies and restores dialect session state around each Calcite query connection. */
  private static final class QueryConnectionDataSource implements DataSource {

    private final DataSource delegate;
    private final RuntimeSchemaDialect dialect;

    private QueryConnectionDataSource(
        final DataSource delegate,
        final RuntimeSchemaDialect dialect
    ) {
      this.delegate = delegate;
      this.dialect = dialect;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return configure(delegate.getConnection());
    }

    @Override
    public Connection getConnection(final String username, final String password)
        throws SQLException {
      return configure(delegate.getConnection(username, password));
    }

    private Connection configure(final Connection physical) throws SQLException {
      try {
        dialect.prepareRecordQueryConnection(physical);
        return resetOnClose(physical, dialect);
      } catch (SQLException | RuntimeException exception) {
        try {
          physical.close();
        } catch (SQLException closeFailure) {
          exception.addSuppressed(closeFailure);
        }
        throw exception;
      }
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

    private static Connection resetOnClose(
        final Connection physical,
        final RuntimeSchemaDialect dialect
    ) {
      AtomicBoolean closed = new AtomicBoolean();
      return (Connection) Proxy.newProxyInstance(
          Connection.class.getClassLoader(),
          new Class<?>[] {Connection.class},
          (proxy, method, arguments) -> {
            if (method.getName().equals("close") && method.getParameterCount() == 0) {
              if (closed.compareAndSet(false, true)) {
                try {
                  dialect.resetRecordQueryConnection(physical);
                } finally {
                  physical.close();
                }
              }
              return null;
            }
            if (method.getName().equals("isClosed") && method.getParameterCount() == 0) {
              return closed.get() || physical.isClosed();
            }
            try {
              return method.invoke(physical, arguments);
            } catch (InvocationTargetException exception) {
              throw exception.getCause();
            }
          }
      );
    }
  }
}

package org.simplepoint.plugin.dna.federation.service.impl;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.schema.SchemaPlus;
import org.simplepoint.data.calcite.core.query.CalciteSchemaConfigurer;
import org.simplepoint.data.calcite.core.query.CalciteWriteAdmission;
import org.simplepoint.data.calcite.core.query.CalciteWritePlan;
import org.simplepoint.data.ownership.PhysicalOwnershipSession;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException;
import org.simplepoint.plugin.dna.federation.api.error.FederationSqlWriteException.Code;
import org.simplepoint.plugin.dna.federation.service.support.FederationCalciteCatalogAssembler.FederationCalciteCatalogAssembly;
import org.springframework.stereotype.Component;

/**
 * Console-owned physical ownership boundary. This direct-target check is not the complete native
 * dependency/side-effect policy; relation publication remains gated until those paths are covered.
 */
@Component
final class FederationSqlWriteOwnership {
  CalciteScope openCalcite(
      FederationCalciteCatalogAssembly assembly, FederationDmlPlan original, String sourceCode) {
    return new CalciteScope(assembly, original, sourceCode);
  }

  FederationNativeWriteScope openNative(
      Connection connection, FederationDmlPlan plan, String sourceCode) {
    try {
      var objects = new FederationNativeSqlObjects(connection, sourceCode);
      return FederationNativeWriteScope.open(connection, () -> objects.resolve(plan));
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  FederationNativeWriteScope openNative(
      Connection connection, FederationDdlPlan plan, String sourceCode) {
    try {
      var objects = new FederationNativeSqlObjects(connection, sourceCode);
      return FederationNativeWriteScope.open(connection, () -> objects.resolve(plan));
    } catch (SQLException failure) {
      throw unavailable(failure);
    }
  }

  /** The executor retains this scope until the engine completes and before its assembly closes. */
  static final class CalciteScope implements CalciteSchemaConfigurer, AutoCloseable {
    private final FederationCalciteCatalogAssembly assembly;
    private final CalciteSchemaConfigurer delegate;
    private final FederationDmlPlan original;
    private final String sourceCode;
    private final List<PhysicalOwnershipSession> sessions = new ArrayList<>();
    private final List<NativeCheck> nativeChecks = new ArrayList<>();
    private boolean attempted;
    private boolean verified;
    private boolean closed;

    private CalciteScope(
        FederationCalciteCatalogAssembly assembly, FederationDmlPlan original, String sourceCode) {
      this.assembly = Objects.requireNonNull(assembly, "assembly");
      this.delegate = assembly.schemaConfigurer();
      this.original = Objects.requireNonNull(original, "original");
      this.sourceCode = Objects.requireNonNull(sourceCode, "sourceCode");
      try {
        for (var connection : assembly.writeConnections()) {
          // Never commit an inherited transaction, nor borrow another connection to inspect it.
          if (!connection.getAutoCommit()) {
            throw unavailable(null);
          }
        }
      } catch (SQLException failure) {
        throw unavailable(failure);
      }
    }

    @Override
    public void configure(SchemaPlus root) {
      delegate.configure(root);
    }

    @Override
    public Class<? extends RelDataTypeSystem> typeSystem() {
      return delegate.typeSystem();
    }

    @Override
    public ZoneId timeZone() {
      return delegate.timeZone();
    }

    @Override
    public CalciteWriteAdmission writeAdmission() {
      var previous = delegate.writeAdmission();
      return new CalciteWriteAdmission() {
        @Override
        public void verify(CalciteWritePlan plan) {
          CalciteScope.this.verify(plan, previous);
        }

        @Override
        public void beforeExecution() {
          requireVerified();
          if (previous != null) {
            previous.beforeExecution();
          }
          requireVerified();
        }
      };
    }

    private void verify(CalciteWritePlan plan, CalciteWriteAdmission previous) {
      if (closed || attempted) {
        throw unavailable(null);
      }
      attempted = true;
      try {
        FederationCalciteObjectBindings.verify(original, plan, assembly, sourceCode);
        var namespaces = new ArrayList<Namespace>();
        var byConnection = new IdentityHashMap<Connection, LinkedHashMap<String, Namespace>>();
        for (var use : plan.tables()) {
          var connection = assembly.writeConnection(use.dataSource());
          var coordinate = namespace(connection, use);
          var objects = new FederationNativeSqlObjects(connection, sourceCode);
          FederationNativeWriteScope.Resolver resolver =
              () ->
                  objects.resolveBound(
                      coordinate.name(), use.table(), use.role() == CalciteWritePlan.Role.TARGET);
          nativeChecks.add(new NativeCheck(resolver, resolver.resolve()));
          var bound = byConnection.computeIfAbsent(connection, ignored -> new LinkedHashMap<>());
          var namespace = bound.get(coordinate.name());
          if (namespace == null) {
            namespace = coordinate;
            bound.put(namespace.name(), namespace);
            namespaces.add(namespace);
          }
          namespace.tables().add(use);
        }
        namespaces.sort(Comparator.comparing(Namespace::catalog).thenComparing(Namespace::name));
        for (var namespace : namespaces) {
          var session = PhysicalOwnershipSession.open(namespace.connection(), namespace.name());
          sessions.add(session);
          for (var use : namespace.tables()) {
            if (use.role() == CalciteWritePlan.Role.TARGET
                && session.owner(use.table(), true).isPresent()) {
              throw new FederationSqlWriteException(Code.SQL_WRITE_PROTECTED);
            }
          }
        }
        if (previous != null) {
          previous.verify(plan);
        }
        verifyLocks();
        verifyObjects();
        verified = true;
      } catch (SQLException failure) {
        throw unavailable(failure);
      }
    }

    /** Do not report successful completion without admission evidence and retained locks. */
    void requireVerified() {
      if (closed || !verified) {
        throw unavailable(null);
      }
      try {
        verifyLocks();
        verifyObjects();
        verifyLocks();
      } catch (SQLException failure) {
        throw unavailable(failure);
      }
    }

    private void verifyLocks() throws SQLException {
      for (var session : sessions) {
        session.verifyExecutionSession();
      }
    }

    private void verifyObjects() throws SQLException {
      for (var check : nativeChecks) {
        if (!check.expected().equals(check.resolver().resolve())) {
          throw unavailable(null);
        }
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      FederationSqlWriteException failure = null;
      for (var session : sessions.reversed()) {
        try {
          session.close();
        } catch (SQLException | RuntimeException closeFailure) {
          if (failure == null) {
            failure = unavailable(closeFailure);
          } else {
            failure.addSuppressed(closeFailure);
          }
        }
      }
      sessions.clear();
      nativeChecks.clear();
      if (failure != null) {
        throw failure;
      }
    }

    private record NativeCheck(
        FederationNativeWriteScope.Resolver resolver,
        List<FederationNativeSqlObjects.Use> expected) {}
  }

  private static Namespace namespace(Connection connection, CalciteWritePlan.TableUse use)
      throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName();
    String currentCatalog = connection.getCatalog();
    String catalog = use.catalog() == null ? currentCatalog : use.catalog();
    String name;
    if ("PostgreSQL".equals(product)) {
      if (!Objects.equals(catalog, currentCatalog)) {
        throw unavailable(null);
      }
      // A null schema could resolve through search_path instead of the inspected namespace.
      name = use.schema();
    } else if ("MySQL".equals(product)) {
      if (use.schema() != null && !use.schema().isBlank()) {
        throw unavailable(null);
      }
      name = catalog;
    } else {
      throw unavailable(null);
    }
    if (catalog == null || catalog.isBlank() || name == null || name.isBlank()) {
      throw unavailable(null);
    }
    return new Namespace(connection, catalog, name, new ArrayList<>());
  }

  static FederationSqlWriteException unavailable(Throwable cause) {
    var failure = new FederationSqlWriteException(Code.SQL_WRITE_OWNERSHIP_UNAVAILABLE);
    if (cause != null) {
      failure.initCause(cause);
    }
    return failure;
  }

  private record Namespace(
      Connection connection, String catalog, String name, List<CalciteWritePlan.TableUse> tables) {}
}

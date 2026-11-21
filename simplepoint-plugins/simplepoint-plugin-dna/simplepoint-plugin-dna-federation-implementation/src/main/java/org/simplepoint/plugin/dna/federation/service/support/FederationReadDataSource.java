package org.simplepoint.plugin.dna.federation.service.support;

import org.simplepoint.data.calcite.core.jdbc.ReadOnlyJdbcDataSource;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;

/** Keeps the catalog pool lifetime while giving each query borrow its own read-only transaction. */
final class FederationReadDataSource extends SimpleDataSource {
  private final SimpleDataSource source;

  private FederationReadDataSource(SimpleDataSource source) {
    super(new ReadOnlyJdbcDataSource(source));
    this.source = source;
  }

  static SimpleDataSource protect(SimpleDataSource source, String databaseProduct) {
    // Managed DNA relation storage currently supports PostgreSQL/MySQL. Other query dialects keep
    // their existing adapters; they are not certified by this transaction protocol.
    return ReadOnlyJdbcDataSource.supports(databaseProduct)
        ? new FederationReadDataSource(source)
        : source;
  }

  @Override
  public void close() {
    source.close();
  }
}

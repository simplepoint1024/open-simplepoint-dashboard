package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.List;
import java.util.function.Function;

/** Native transaction-lock infrastructure SQL; business Record queries and DML still use Calcite. */
public record RecordWriteFenceSql(String initialize, String lockShared, String lockExclusive, String advance) {

  /** Compiles fixed columns using the selected database dialect's identifier quoting. */
  static RecordWriteFenceSql compile(final String table, final Function<String, String> quote,
      final String duplicateSuffix) {
    String columns = String.join(", ", List.of("id", "tenant_id", "record_version", "created_by", "created_at",
        "updated_by", "updated_at", "model_id", "write_revision", "previous_revision", "publication_fingerprint")
        .stream().map(quote).toList());
    String identity = quote.apply("id") + " = ? AND " + quote.apply("tenant_id") + " = ? AND "
        + quote.apply("model_id") + " = ? AND " + quote.apply("deleted_at") + " IS NULL";
    String select = "SELECT " + quote.apply("write_revision") + ", " + quote.apply("publication_fingerprint")
        + ", " + quote.apply("previous_revision")
        + " FROM " + table + " WHERE " + identity;
    String update = "UPDATE " + table + " SET " + quote.apply("write_revision") + " = ?, "
        + quote.apply("publication_fingerprint") + " = ?, " + quote.apply("previous_revision") + " = ?, "
        + quote.apply("updated_by") + " = ?, "
        + quote.apply("updated_at") + " = ?, " + quote.apply("record_version") + " = "
        + quote.apply("record_version") + " + 1 WHERE " + identity + " AND " + quote.apply("write_revision") + " = ?";
    return new RecordWriteFenceSql("INSERT INTO " + table + " (" + columns
        + ") VALUES (?, ?, 0, ?, ?, ?, ?, ?, 0, 0, ?)" + duplicateSuffix,
        select + " FOR SHARE", select + " FOR UPDATE", update);
  }
}

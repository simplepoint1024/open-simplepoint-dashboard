package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.function.Function;

/** Bounded integrity probes and transaction locks; business queries and DML still use Calcite. */
public record RecordReferenceSql(String value, String lockTarget, String incoming, String lockMutation) {
  static RecordReferenceSql compile(String table, String column, Function<String, String> quote) {
    String identity = quote.apply("tenant_id") + " = ? AND " + quote.apply("id") + " = ?";
    return new RecordReferenceSql(
        "SELECT " + quote.apply(column) + " FROM " + table + " WHERE " + identity,
        "SELECT " + quote.apply("deleted_at") + " FROM " + table + " WHERE " + identity + " FOR SHARE",
        "SELECT 1 FROM " + table + " WHERE " + quote.apply("tenant_id") + " = ? AND "
            + quote.apply(column) + " = ? LIMIT 1",
        "SELECT " + quote.apply("deleted_at") + " FROM " + table + " WHERE " + identity + " FOR UPDATE");
  }
}

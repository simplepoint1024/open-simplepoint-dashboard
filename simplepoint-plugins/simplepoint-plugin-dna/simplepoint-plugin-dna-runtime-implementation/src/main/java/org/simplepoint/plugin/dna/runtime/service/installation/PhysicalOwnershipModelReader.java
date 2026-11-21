package org.simplepoint.plugin.dna.runtime.service.installation;

import static org.simplepoint.plugin.dna.runtime.service.installation.PhysicalOwnershipModelReview.reject;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory;

/** Domain-owned bounded reads inside the adapter's repeatable-read, RLS-checked transaction. */
final class PhysicalOwnershipModelReader {
  private PhysicalOwnershipModelReader() {}

  static PhysicalOwnershipModelReview read(
      Connection connection, PhysicalBindingInventory inventory) throws SQLException {
    var definitions = new ArrayList<PhysicalOwnershipModelReview.Definition>();
    String versions =
        "SELECT id,physical_model_id,tenant_id,version_number,contract_version,CASE WHEN"
            + " octet_length(schema_json)<=? THEN schema_json END AS"
            + " schema_json,schema_checksum,deleted_at FROM simpoint_dna_physical_model_versions"
            + " ORDER BY id COLLATE \"C\" LIMIT ?";
    int bytes = 0;
    try (var statement = connection.prepareStatement(versions)) {
      statement.setQueryTimeout(30);
      statement.setFetchSize(100);
      statement.setInt(1, PhysicalOwnershipModelReview.MAX_SCHEMA_BYTES);
      statement.setInt(2, PhysicalOwnershipModelReview.MAX_ENTRIES + 1);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          if (definitions.size() == PhysicalOwnershipModelReview.MAX_ENTRIES) {
            throw reject("LIMIT");
          }
          String json = rows.getString("schema_json");
          if (json == null) {
            throw reject("LIMIT");
          }
          bytes += json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
          if (bytes > PhysicalOwnershipModelReview.MAX_TOTAL_SCHEMA_BYTES) {
            throw reject("LIMIT");
          }
          definitions.add(
              new PhysicalOwnershipModelReview.Definition(
                  rows.getString("id"),
                  rows.getString("physical_model_id"),
                  rows.getString("tenant_id"),
                  rows.getInt("version_number"),
                  rows.getString("contract_version"),
                  json,
                  rows.getString("schema_checksum"),
                  instant(rows, "deleted_at")));
        }
      }
    }
    var publications = new ArrayList<PhysicalOwnershipModelReview.Publication>();
    String migrations =
        "SELECT id,physical_model_id,tenant_id,from_version,target_version,"
            + "target_schema_checksum,target_physical_checksum,verified_schema_checksum,status,"
            + "dialect_id,lock_version,finished_at,deleted_at FROM simpoint_dna_schema_migrations"
            + " ORDER BY id COLLATE \"C\" LIMIT ?";
    try (var statement = connection.prepareStatement(migrations)) {
      statement.setQueryTimeout(30);
      statement.setFetchSize(100);
      statement.setInt(1, PhysicalOwnershipModelReview.MAX_ENTRIES + 1);
      try (var rows = statement.executeQuery()) {
        while (rows.next()) {
          if (publications.size() == PhysicalOwnershipModelReview.MAX_ENTRIES) {
            throw reject("LIMIT");
          }
          String expected = rows.getString("target_physical_checksum");
          if (!"ACTIVE".equals(rows.getString("status"))
              || expected == null
              || !expected.equals(rows.getString("verified_schema_checksum"))) {
            throw reject("UNSETTLED_PUBLICATION");
          }
          publications.add(
              new PhysicalOwnershipModelReview.Publication(
                  rows.getString("id"),
                  rows.getString("physical_model_id"),
                  rows.getString("tenant_id"),
                  rows.getObject("from_version", Integer.class),
                  rows.getInt("target_version"),
                  rows.getString("target_schema_checksum"),
                  expected,
                  rows.getString("dialect_id"),
                  rows.getLong("lock_version"),
                  instant(rows, "finished_at"),
                  instant(rows, "deleted_at")));
        }
      }
    }
    try (var statement =
        connection.prepareStatement(
            "SELECT 1 FROM simpoint_dna_relation_guards WHERE NOT released LIMIT 1")) {
      statement.setQueryTimeout(30);
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          throw reject("MIGRATION_GUARD");
        }
      }
    }
    return new PhysicalOwnershipModelReview(1, inventory, definitions, publications);
  }

  private static Instant instant(ResultSet rows, String column) throws SQLException {
    var value = rows.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}

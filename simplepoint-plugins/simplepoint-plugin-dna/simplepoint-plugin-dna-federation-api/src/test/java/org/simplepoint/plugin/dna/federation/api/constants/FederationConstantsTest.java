package org.simplepoint.plugin.dna.federation.api.constants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FederationConstantsTest {

  // ---- DnaFederationPaths ----

  @Test
  void paths_baseValues() {
    assertThat(DnaFederationPaths.BASE).isEqualTo("/dna/federation");
    assertThat(DnaFederationPaths.PLATFORM_BASE).isEqualTo("/platform/dna/federation");
    assertThat(DnaFederationPaths.CATALOGS).isEqualTo("/dna/federation/catalogs");
    assertThat(DnaFederationPaths.SQL_CONSOLE).isEqualTo("/dna/federation/sql-console");
    assertThat(DnaFederationPaths.DASHBOARD).isEqualTo("/dna/dashboard");
  }

  @Test
  void paths_platformDerivedValues() {
    assertThat(DnaFederationPaths.PLATFORM_CATALOGS).startsWith(DnaFederationPaths.PLATFORM_BASE);
    assertThat(DnaFederationPaths.PLATFORM_SCHEMAS).startsWith(DnaFederationPaths.PLATFORM_BASE);
    assertThat(DnaFederationPaths.PLATFORM_JDBC_DRIVER).startsWith(DnaFederationPaths.PLATFORM_BASE);
  }

  // ---- FederationCatalogTypes ----

  @Test
  void catalogTypes_normalize_nullOrBlankDefaultsToVirtual() {
    assertThat(FederationCatalogTypes.normalize(null)).isEqualTo(FederationCatalogTypes.VIRTUAL);
    assertThat(FederationCatalogTypes.normalize("")).isEqualTo(FederationCatalogTypes.VIRTUAL);
    assertThat(FederationCatalogTypes.normalize("  ")).isEqualTo(FederationCatalogTypes.VIRTUAL);
  }

  @Test
  void catalogTypes_normalize_uppercasesAndTrims() {
    assertThat(FederationCatalogTypes.normalize(" data_source ")).isEqualTo("DATA_SOURCE");
    assertThat(FederationCatalogTypes.normalize("virtual")).isEqualTo("VIRTUAL");
  }

  @Test
  void catalogTypes_isVirtual() {
    assertThat(FederationCatalogTypes.isVirtual(null)).isTrue();
    assertThat(FederationCatalogTypes.isVirtual("VIRTUAL")).isTrue();
    assertThat(FederationCatalogTypes.isVirtual("DATA_SOURCE")).isFalse();
  }

  @Test
  void catalogTypes_isDataSource() {
    assertThat(FederationCatalogTypes.isDataSource("DATA_SOURCE")).isTrue();
    assertThat(FederationCatalogTypes.isDataSource("data_source")).isTrue();
    assertThat(FederationCatalogTypes.isDataSource(null)).isFalse();
  }

  // ---- FederationJdbcOperation ----

  @Test
  void jdbcOperation_fromCode_resolves() {
    assertThat(FederationJdbcOperation.fromCode("QUERY")).isEqualTo(FederationJdbcOperation.QUERY);
    assertThat(FederationJdbcOperation.fromCode("query")).isEqualTo(FederationJdbcOperation.QUERY);
    assertThat(FederationJdbcOperation.fromCode("METADATA")).isEqualTo(FederationJdbcOperation.METADATA);
    assertThat(FederationJdbcOperation.fromCode("ddl")).isEqualTo(FederationJdbcOperation.DDL);
  }

  @Test
  void jdbcOperation_fromCode_null_throwsIllegalArgument() {
    assertThatThrownBy(() -> FederationJdbcOperation.fromCode(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jdbcOperation_fromCode_blank_throwsIllegalArgument() {
    assertThatThrownBy(() -> FederationJdbcOperation.fromCode("  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jdbcOperation_fromCode_unknown_throwsIllegalArgument() {
    assertThatThrownBy(() -> FederationJdbcOperation.fromCode("UNKNOWN"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jdbcOperation_normalizeCodes_emptyOrNull_returnsEmpty() {
    assertThat(FederationJdbcOperation.normalizeCodes(null)).isEmpty();
    assertThat(FederationJdbcOperation.normalizeCodes(List.of())).isEmpty();
  }

  @Test
  void jdbcOperation_normalizeCodes_normalizesAndDeduplicates() {
    Set<String> result = FederationJdbcOperation.normalizeCodes(List.of("query", "QUERY", "metadata"));
    assertThat(result).containsExactlyInAnyOrder("QUERY", "METADATA");
  }

  @Test
  void jdbcOperation_readOnlyDefaults_containsMetadataAndQuery() {
    Set<String> defaults = FederationJdbcOperation.readOnlyDefaults();
    assertThat(defaults).containsExactlyInAnyOrder("METADATA", "QUERY");
  }
}

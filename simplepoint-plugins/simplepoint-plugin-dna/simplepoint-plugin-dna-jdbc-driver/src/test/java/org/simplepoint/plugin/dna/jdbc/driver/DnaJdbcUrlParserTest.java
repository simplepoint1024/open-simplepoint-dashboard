package org.simplepoint.plugin.dna.jdbc.driver;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DnaJdbcUrlParserTest {

  @Test
  void parsesUrlAndProperties() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", "alice@example.com");
    properties.setProperty("password", "secret");

    DnaJdbcModels.ConnectionConfig config = DnaJdbcUrlParser.parse(
        "jdbc:simplepoint:dna://dna.example.com:15432?catalogCode=analytics&tenantId=tenant-a&schema=reporting",
        properties
    );

    assertThat(config.baseUri()).hasToString("tcp://dna.example.com:15432");
    assertThat(config.loginSubject()).isEqualTo("alice@example.com");
    assertThat(config.password()).isEqualTo("secret");
    assertThat(config.catalogCode()).isEqualTo("analytics");
    assertThat(config.tenantId()).isEqualTo("tenant-a");
    assertThat(config.schema()).isEqualTo("reporting");
  }

  @Test
  void propertiesOverrideQueryParameters() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", "override@example.com");
    properties.setProperty("password", "override-secret");
    properties.setProperty("catalogCode", "override-catalog");

    DnaJdbcModels.ConnectionConfig config = DnaJdbcUrlParser.parse(
        "jdbc:simplepoint:dna://dna.example.com:15432?user=demo&password=demo&catalogCode=query-catalog",
        properties
    );

    assertThat(config.loginSubject()).isEqualTo("override@example.com");
    assertThat(config.password()).isEqualTo("override-secret");
    assertThat(config.catalogCode()).isEqualTo("override-catalog");
  }

  @Test
  void allowsMissingCatalogCode() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", "alice@example.com");
    properties.setProperty("password", "secret");

    DnaJdbcModels.ConnectionConfig config = DnaJdbcUrlParser.parse(
        "jdbc:simplepoint:dna://dna.example.com:15432",
        properties
    );

    assertThat(config.catalogCode()).isNull();
  }
}

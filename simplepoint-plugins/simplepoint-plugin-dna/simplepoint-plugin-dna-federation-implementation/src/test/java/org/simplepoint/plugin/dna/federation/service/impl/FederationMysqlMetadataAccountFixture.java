package org.simplepoint.plugin.dna.federation.service.impl;

import java.net.URI;
import java.sql.SQLException;
import java.util.UUID;
import org.simplepoint.data.datasource.jdbc.SimpleDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Disposable backend account with metadata privileges; business table writes remain explicit. */
final class FederationMysqlMetadataAccountFixture implements AutoCloseable {
  private final FederationSqlOwnershipFixture fixture;
  final String name = "tv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
  final String user = "`" + name + "`@`%`";
  final String role = "`r_" + name + "`@`%`";
  final SimpleDataSource source;
  private boolean userCreated;
  private boolean roleCreated;

  FederationMysqlMetadataAccountFixture(FederationSqlOwnershipFixture fixture) throws Exception {
    this.fixture = fixture;
    String password = UUID.randomUUID().toString();
    try {
      try (var connection = fixture.pool.getConnection();
          var statement = connection.prepareStatement("CREATE USER " + user + " IDENTIFIED BY ?")) {
        statement.setString(1, password);
        statement.execute();
        userCreated = true;
      }
      fixture.execute("CREATE ROLE " + role);
      roleCreated = true;
      fixture.execute("GRANT PROCESS ON *.* TO " + user);
      fixture.execute("GRANT SELECT ON " + fixture.name + ".* TO " + user);
      fixture.execute("GRANT INSERT ON " + fixture.name + ".ordinary TO " + user);
      fixture.execute(
          "GRANT TRIGGER ON " + fixture.name + ".simpoint_physical_object_owners_v1 TO " + user);
      URI endpoint = URI.create(fixture.base.getUrl().substring("jdbc:".length()));
      String url =
          "jdbc:"
              + new URI(
                  endpoint.getScheme(),
                  endpoint.getUserInfo(),
                  endpoint.getHost(),
                  endpoint.getPort(),
                  "/" + fixture.name,
                  endpoint.getQuery(),
                  endpoint.getFragment());
      source = new SimpleDataSource(new DriverManagerDataSource(url, name, password));
    } catch (Exception failure) {
      try {
        close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  @Override
  public void close() throws SQLException {
    try {
      if (userCreated) {
        fixture.execute("DROP USER " + user);
        userCreated = false;
      }
    } finally {
      if (roleCreated) {
        fixture.execute("DROP ROLE " + role);
        roleCreated = false;
      }
    }
  }
}

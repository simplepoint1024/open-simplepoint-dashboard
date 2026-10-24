package org.simplepoint.plugin.rbac.authentication.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplepoint.security.authentication.AuthenticationSecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AuthenticationSecretMigrationTest {
  @TempDir Path directory;

  @Test
  void migrationIsResumableAndNeverLeavesPlaintextOrAcceptsWrongOwner() throws Exception {
    var ds = new DriverManagerDataSource("jdbc:h2:mem:migration;DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "create table simpoint_ac_users (id varchar(36) primary key, two_factor_secret"
            + " varchar(512))");
    String seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    jdbc.update("insert into simpoint_ac_users values (?,?)", "alice", seed);
    var key = directory.resolve("key-ring.json");
    Files.writeString(
        key,
        "{\"activeKeyId\":\"k1\",\"keys\":{\"k1\":\""
            + java.util.Base64.getEncoder().encodeToString(new byte[32])
            + "\"}}");
    var cipher = new AuthenticationSecretCipher(key.toString());
    var migration =
        new AuthenticationSecretMigration(jdbc, cipher, new DataSourceTransactionManager(ds));
    migration.run(null);
    migration.run(null);
    String stored =
        jdbc.queryForObject(
            "select two_factor_secret from simpoint_ac_users where id='alice'", String.class);
    assertThat(stored).startsWith("totp:v1:k1:").doesNotContain(seed);
    assertThat(cipher.decrypt("alice", stored)).isEqualTo(seed);
    assertThatThrownBy(() -> cipher.decrypt("bob", stored))
        .isInstanceOf(IllegalStateException.class);
  }
}

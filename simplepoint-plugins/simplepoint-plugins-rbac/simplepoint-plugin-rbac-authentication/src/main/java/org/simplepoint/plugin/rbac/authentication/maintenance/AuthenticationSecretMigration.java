package org.simplepoint.plugin.rbac.authentication.maintenance;

import java.util.List;
import java.util.Map;
import org.simplepoint.security.authentication.AuthenticationSecretCipher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit, resumable maintenance operation for legacy seeds and encryption-key rotation. */
@Component
@ConditionalOnProperty(
    name = "simplepoint.security.authentication.migrate-secrets",
    havingValue = "true")
public class AuthenticationSecretMigration implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final AuthenticationSecretCipher cipher;
  private final TransactionTemplate transaction;

  /** Creates the explicit maintenance operation using the owning database transaction manager. */
  public AuthenticationSecretMigration(
      JdbcTemplate jdbc, AuthenticationSecretCipher cipher, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.transaction = new TransactionTemplate(manager);
  }

  @Override
  public void run(ApplicationArguments args) {
    cipher.requireKey();
    String after = "";
    while (true) {
      List<Map<String, Object>> rows =
          jdbc.queryForList(
              "select id, two_factor_secret from simpoint_ac_users where id > ? "
                  + "and two_factor_secret is not null order by id limit 100",
              after);
      if (rows.isEmpty()) {
        return;
      }
      transaction.executeWithoutResult(
          status -> {
            for (Map<String, Object> row : rows) {
              String id = (String) row.get("id");
              String stored = (String) row.get("two_factor_secret");
              String encrypted = cipher.migrate(id, stored);
              int count =
                  jdbc.update(
                      "update simpoint_ac_users set two_factor_secret = ? "
                          + "where id = ? and two_factor_secret = ?",
                      encrypted,
                      id,
                      stored);
              if (count != 1) {
                throw new IllegalStateException("Authentication data changed during migration");
              }
            }
          });
      after = (String) rows.getLast().get("id");
    }
  }
}

package org.simplepoint.data.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MysqlPrivilegeGrantsTest {
  @Test
  void columnSelectDoesNotCertifyCompleteColumnMetadata() throws Exception {
    assertEquals(
        List.of(
            new MysqlPrivilegeGrants.Scope(false, "db", null, false),
            new MysqlPrivilegeGrants.Scope(false, "db", "whole", true)),
        MysqlPrivilegeGrants.forPrivilege(
            List.of(
                "GRANT SELECT (`id`) ON `db`.`partial` TO `u`@`%`",
                "GRANT TRIGGER ON `db`.* TO `u`@`%`", "GRANT SELECT ON `db`.`whole` TO `u`@`%`"),
            MysqlPrivilegeGrants.Privilege.SELECT));
  }

  @Test
  void matchingDatabaseRowsWithoutTriggerRemainVisibleToTheAuthorityCheck() throws Exception {
    assertEquals(
        List.of(
            new MysqlPrivilegeGrants.Scope(false, "db%", null, true),
            new MysqlPrivilegeGrants.Scope(false, "db", null, false)),
        MysqlPrivilegeGrants.triggers(
            List.of("GRANT TRIGGER ON `db%`.* TO `u`@`%`", "GRANT SELECT ON `db`.* TO `u`@`%`")));
  }

  @Test
  void retainsGlobalDatabaseTableAndPartialRevokeScopes() throws Exception {
    assertEquals(
        List.of(
            new MysqlPrivilegeGrants.Scope(false, null, null, true),
            new MysqlPrivilegeGrants.Scope(true, "private", null, true),
            new MysqlPrivilegeGrants.Scope(false, "db", "orders", true),
            new MysqlPrivilegeGrants.Scope(false, "业务", null, true)),
        MysqlPrivilegeGrants.triggers(
            List.of(
                "GRANT SELECT, TRIGGER ON *.* TO `u`@`%`",
                "REVOKE TRIGGER ON `private`.* FROM `u`@`%`",
                "GRANT ALL PRIVILEGES ON `db`.`orders` TO `u`@`%`",
                "GRANT TRIGGER ON `业务`.* TO `u`@`%` WITH GRANT OPTION")));
  }

  @Test
  void roleNamesColumnsAndQuotedWildcardsDoNotBecomePrivileges() throws Exception {
    assertEquals(
        List.of(
            new MysqlPrivilegeGrants.Scope(false, "db", "*", true),
            new MysqlPrivilegeGrants.Scope(false, "a`b", null, true)),
        MysqlPrivilegeGrants.triggers(
            List.of(
                "GRANT `TRIGGER`@`%` TO `u`@`%`",
                "GRANT SELECT (`TRIGGER`) ON `db`.`orders` TO `u`@`%`",
                "GRANT TRIGGER ON `db`.`*` TO `u`@`%`",
                "GRANT TRIGGER ON `a``b`.* TO `u`@`%`")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "GRANT TRIGGER ON",
        "GRANT TRIGGER ON db.* TO `u`@`%`",
        "REVOKE TRIGGER ON `db`.`table` FROM `u`@`%`",
        "GRANT TRIGGER ON *.`table` TO `u`@`%`",
        "GRANT TRIGGER ON `unclosed.* TO `u`@`%`",
        "unknown"
      })
  void unknownPrivilegeMetadataFailsClosed(String value) {
    assertThrows(
        PhysicalOwnershipException.class, () -> MysqlPrivilegeGrants.triggers(List.of(value)));
  }
}

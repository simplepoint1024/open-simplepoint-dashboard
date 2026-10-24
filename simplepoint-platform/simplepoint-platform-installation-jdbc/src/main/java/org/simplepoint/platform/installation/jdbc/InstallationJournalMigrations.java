package org.simplepoint.platform.installation.jdbc;

import java.util.List;
import org.simplepoint.data.migration.ModuleMigrationPlan;

/** Installer-owned bootstrap schema; reuse the platform migration engine and checksums. */
public final class InstallationJournalMigrations {
  private InstallationJournalMigrations() {}

  /** The installer explicitly binds its own store before opening deployment operations. */
  public static ModuleMigrationPlan plan(String store) {
    return new ModuleMigrationPlan(
        List.of(
            ModuleMigrationPlan.loadSchema(
                "installation-journal",
                store,
                InstallationJournalMigrations.class.getClassLoader())));
  }
}

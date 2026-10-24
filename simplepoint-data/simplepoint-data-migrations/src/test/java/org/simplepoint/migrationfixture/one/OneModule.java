package org.simplepoint.migrationfixture.one;

import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;

/** Independent module with migration-owned tables. */
@ModuleMigrations
@PlatformModule(id = "one", packages = "org.simplepoint.migrationfixture.one")
public final class OneModule {}

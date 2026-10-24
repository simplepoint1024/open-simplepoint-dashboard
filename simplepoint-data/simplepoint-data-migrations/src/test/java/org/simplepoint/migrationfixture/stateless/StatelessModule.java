package org.simplepoint.migrationfixture.stateless;

import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;

/** A module explicitly owning no SQL schema must not need a dummy table or migration. */
@ModuleMigrations(ownsSchema = false)
@PlatformModule(id = "stateless", packages = "org.simplepoint.migrationfixture.stateless")
public final class StatelessModule {}

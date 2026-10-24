package org.simplepoint.migrationfixture.two;

import org.simplepoint.platform.composition.ModuleDependency;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;

/** Depends on the first module, retaining a separate migration identity. */
@ModuleMigrations
@PlatformModule(
    id = "two",
    packages = "org.simplepoint.migrationfixture.two",
    requires = @ModuleDependency(id = "one"))
public final class TwoModule {}

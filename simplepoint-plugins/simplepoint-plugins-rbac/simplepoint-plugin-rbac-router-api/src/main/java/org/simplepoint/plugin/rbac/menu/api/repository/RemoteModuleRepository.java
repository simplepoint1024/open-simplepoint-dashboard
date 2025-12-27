package org.simplepoint.plugin.rbac.menu.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.security.entity.MicroModule;

/**
 * Repository interface for managing remote modules in the RBAC (Role-Based Access Control) system.
 *
 * <p>This interface is intended to be used for remote module management operations,
 * but currently does not define any specific methods.</p>
 *
 * @since 1.0
 */
public interface RemoteModuleRepository extends BaseRepository<MicroModule, String> {
}

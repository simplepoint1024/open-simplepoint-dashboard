package org.simplepoint.plugin.rbac.menu.base.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.RemoteModuleRepository;
import org.simplepoint.security.entity.MicroModule;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing remote modules in the RBAC (Role-Based Access Control) system.
 *
 * <p>This interface extends {@link BaseRepository} {@link RemoteModuleRepository} to provide CRUD operations for {@link MicroModule} entities.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Repository
public interface JpaRemoteModuleRepository extends BaseRepository<MicroModule, String>,
    RemoteModuleRepository {
}

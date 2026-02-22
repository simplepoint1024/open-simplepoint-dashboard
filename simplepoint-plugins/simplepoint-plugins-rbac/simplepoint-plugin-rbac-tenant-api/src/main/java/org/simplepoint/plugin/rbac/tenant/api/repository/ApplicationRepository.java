package org.simplepoint.plugin.rbac.tenant.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;

/**
 * ApplicationRepository provides an interface for managing Application entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 */
public interface ApplicationRepository extends BaseRepository<Application, String> {
}

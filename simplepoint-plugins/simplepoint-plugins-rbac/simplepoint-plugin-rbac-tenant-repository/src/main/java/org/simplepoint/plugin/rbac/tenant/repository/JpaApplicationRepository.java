package org.simplepoint.plugin.rbac.tenant.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.tenant.api.entity.Application;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationRepository;
import org.springframework.stereotype.Repository;

/**
 * JpaApplicationRepository provides an interface for managing Application entities.
 * It extends the BaseRepository to inherit basic CRUD operations and the ApplicationRepository
 * to include specific methods for handling application data.
 */
@Repository
public interface JpaApplicationRepository extends BaseRepository<Application, String>, ApplicationRepository {
}

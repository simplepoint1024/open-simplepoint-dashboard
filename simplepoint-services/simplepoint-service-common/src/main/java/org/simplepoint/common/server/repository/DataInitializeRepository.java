package org.simplepoint.common.server.repository;

import org.simplepoint.data.initialize.entity.DataInitialize;
import org.simplepoint.data.initialize.entity.id.DataInitializeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for DataInitialize entities.
 */
@Repository
public interface DataInitializeRepository extends JpaRepository<DataInitialize, DataInitializeId> {
  /**
   * Finds the first DataInitialize entity by service name and module name.
   *
   * @param serviceName the name of the service
   * @param moduleName  the name of the module
   * @return the found DataInitialize entity, or null if not found
   */
  DataInitialize findFirstByServiceNameAndModuleName(String serviceName, String moduleName);
}

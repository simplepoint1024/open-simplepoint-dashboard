package org.simplepoint.plugin.rbac.menu.base.repository;

import java.util.List;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.ButtonRepository;
import org.simplepoint.security.entity.Actions;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for managing Button entities.
 * This interface extends BaseRepository to provide CRUD operations
 * for Button entities.
 *
 * @author JinxuLiu
 * @since 1.0
 */

@Repository
public interface JpaButtonRepository extends BaseRepository<Actions, String>, ButtonRepository {
  @Override
  List<Actions> findByAccessValue(String accessValue);
}

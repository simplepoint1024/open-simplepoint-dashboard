package org.simplepoint.plugin.rbac.menu.api.repository;

import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.security.entity.Actions;

/**
 * Repository interface for {@link Actions} entity.
 *
 * <p>Provides data access operations for buttons, extending {@link BaseRepository}
 * to support standard CRUD functionality.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
public interface ButtonRepository extends BaseRepository<Actions, String> {
  /**
   * Finds buttons by their access value.
   *
   * @param accessValue the access value to filter buttons
   * @return a list of buttons matching the specified access value
   */
  List<Actions> findByAccessValue(String accessValue);
}

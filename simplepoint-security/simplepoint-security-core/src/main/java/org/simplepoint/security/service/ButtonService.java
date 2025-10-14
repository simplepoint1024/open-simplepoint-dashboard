package org.simplepoint.security.service;

import java.util.List;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.security.entity.Actions;

/**
 * Service interface for managing {@link Actions} entities.
 *
 * <p>Provides abstraction for button-related operations and extends the base service
 * with CRUD functionalities.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@AmqpRemoteClient(to = "security.button")
public interface ButtonService extends BaseService<Actions, String> {
  /**
   * Finds buttons by their access value.
   *
   * @param accessValue the access value to filter buttons
   * @return a list of buttons matching the specified access value
   */
  List<Actions> findByAccessValue(String accessValue);
}

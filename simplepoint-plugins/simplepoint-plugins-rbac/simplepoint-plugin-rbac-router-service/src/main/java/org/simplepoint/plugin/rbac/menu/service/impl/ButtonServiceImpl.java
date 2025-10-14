package org.simplepoint.plugin.rbac.menu.service.impl;

import java.util.List;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.rbac.menu.api.repository.ButtonRepository;
import org.simplepoint.security.entity.Actions;
import org.simplepoint.security.service.ButtonService;

/**
 * Implementation of {@link ButtonService} providing business logic for button management.
 *
 * <p>This service handles CRUD operations for buttons by interacting with {@link ButtonRepository}.
 * It extends {@link BaseServiceImpl} to inherit standard data operations.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@AmqpRemoteService
public class ButtonServiceImpl extends BaseServiceImpl<ButtonRepository, Actions, String>
    implements ButtonService {

  /**
   * Constructs a ButtonServiceImpl with the specified repository and metadata sync service.
   *
   * @param repository the repository used for button operations
   */
  public ButtonServiceImpl(
      final ButtonRepository repository
  ) {
    super(repository);
  }

  @Override
  public List<Actions> findByAccessValue(String accessValue) {
    return getRepository().findByAccessValue(accessValue);
  }
}

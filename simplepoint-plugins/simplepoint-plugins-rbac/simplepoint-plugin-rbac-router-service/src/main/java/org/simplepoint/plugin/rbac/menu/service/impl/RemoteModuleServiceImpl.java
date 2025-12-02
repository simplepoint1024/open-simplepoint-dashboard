package org.simplepoint.plugin.rbac.menu.service.impl;

import jakarta.transaction.Transactional;
import java.util.Set;
import org.hibernate.service.spi.ServiceException;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.rbac.menu.api.repository.RemoteModuleRepository;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.RemoteModule;
import org.simplepoint.security.service.MenuService;
import org.simplepoint.security.service.RemoteModuleService;

/**
 * Implementation of {@link RemoteModuleService} providing business logic for remote module management.
 *
 * <p>This service handles CRUD operations for remote modules by interacting with {@link RemoteModuleRepository}.
 * It extends {@link BaseServiceImpl} to inherit standard data operations.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@AmqpRemoteService
public class RemoteModuleServiceImpl
    extends BaseServiceImpl<RemoteModuleRepository, RemoteModule, String>
    implements RemoteModuleService {

  private final MenuService menuService;

  /**
   * Constructs a new instance of {@link RemoteModuleServiceImpl}.
   *
   * @param repository  the repository for remote module data access
   * @param menuService the service for managing menus associated with remote modules
   */
  public RemoteModuleServiceImpl(
      final RemoteModuleRepository repository,
      final MenuService menuService
  ) {
    super(repository);
    this.menuService = menuService;
  }

  /**
   * Registers a remote module along with its associated menus.
   *
   * @param module the remote module to register
   * @param menus  the set of menus associated with the remote module
   */
  @Override
  @Transactional(rollbackOn = Exception.class)
  public void register(RemoteModule module, Set<Menu> menus) {
    try {
      if (module == null) {
        return;
      }
      persist(module);
      if (menus != null && !menus.isEmpty()) {
        menuService.persist(menus);
      }
    } catch (Exception e) {
      throw new ServiceException("Failed to register remote module", e);
    }
  }
}

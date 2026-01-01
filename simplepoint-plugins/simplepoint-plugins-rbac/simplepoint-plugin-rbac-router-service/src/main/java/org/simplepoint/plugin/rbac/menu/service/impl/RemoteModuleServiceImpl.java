package org.simplepoint.plugin.rbac.menu.service.impl;

import jakarta.transaction.Transactional;
import java.util.Set;
import org.hibernate.service.spi.ServiceException;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.core.context.UserContext;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.rbac.menu.api.repository.RemoteModuleRepository;
import org.simplepoint.plugin.rbac.menu.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.menu.api.vo.MicroModuleItemVo;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.MicroModule;
import org.simplepoint.security.service.MenuService;

/**
 * Implementation of {@link MicroAppService} providing business logic for remote module management.
 *
 * <p>This service handles CRUD operations for remote modules by interacting with {@link RemoteModuleRepository}.
 * It extends {@link BaseServiceImpl} to inherit standard data operations.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@AmqpRemoteService
public class RemoteModuleServiceImpl
    extends BaseServiceImpl<RemoteModuleRepository, MicroModule, String>
    implements MicroAppService {

  private final MenuService menuService;

  /**
   * Constructor initializing the service with required dependencies.
   *
   * @param repository             the remote module repository
   * @param userContext            the user context
   * @param detailsProviderService the details provider service
   * @param menuService            the menu service
   */
  public RemoteModuleServiceImpl(
      RemoteModuleRepository repository,
      UserContext<BaseUser> userContext,
      DetailsProviderService detailsProviderService,
      MenuService menuService
  ) {
    super(repository, userContext, detailsProviderService);
    this.menuService = menuService;
  }

  /**
   * Loads all registered remote modules.
   *
   * @return a set of {@link MicroModuleItemVo} representing the loaded remote modules
   */
  @Override
  public Set<MicroModuleItemVo> loadApps() {
    return Set.of();
  }
}

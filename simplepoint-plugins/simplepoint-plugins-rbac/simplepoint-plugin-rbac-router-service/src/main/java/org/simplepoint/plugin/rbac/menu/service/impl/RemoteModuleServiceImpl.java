package org.simplepoint.plugin.rbac.menu.service.impl;

import jakarta.transaction.Transactional;
import java.util.Set;
import org.hibernate.service.spi.ServiceException;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.plugin.rbac.menu.api.repository.RemoteModuleRepository;
import org.simplepoint.plugin.rbac.menu.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.menu.api.vo.MicroModuleItemVo;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.MicroModule;
import org.simplepoint.security.service.MenuService;
import org.springframework.beans.factory.annotation.Autowired;

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

  /**
   * Constructs a new RemoteModuleServiceImpl with the specified repository and authorization context holder.
   *
   * @param repository                 the repository for remote module data access
   * @param authorizationContextHolder the holder for authorization context, can be null if not required
   * @param detailsProviderService     the service for providing user details, used for authorization checks
   */
  public RemoteModuleServiceImpl(
      RemoteModuleRepository repository,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, detailsProviderService);
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

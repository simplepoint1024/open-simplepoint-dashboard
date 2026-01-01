package org.simplepoint.plugin.rbac.menu.api.service;

import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.plugin.rbac.menu.api.vo.MicroModuleItemVo;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.MicroModule;

/**
 * Service interface for managing remote modules in the RBAC (Role-Based Access Control) system.
 *
 * <p>This interface extends {@link BaseService} to provide CRUD operations for {@link MicroModule} entities.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
public interface MicroAppService extends BaseService<MicroModule, String> {

  /**
   * Loads all registered remote modules.
   *
   * @return a set of {@link MicroModuleItemVo} representing the loaded remote modules
   */
  Set<MicroModuleItemVo> loadApps();
}

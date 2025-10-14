package org.simplepoint.security.service;

import java.util.Set;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.RemoteModule;

/**
 * Service interface for managing remote modules in the RBAC (Role-Based Access Control) system.
 *
 * <p>This interface extends {@link BaseService} to provide CRUD operations for {@link RemoteModule} entities.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@AmqpRemoteClient(to = "remote.module")
public interface RemoteModuleService extends BaseService<RemoteModule, String> {

  /**
   * Registers a remote module along with its associated menus.
   *
   * @param module the remote module to register
   * @param menus  the set of menus associated with the remote module
   */
  void register(RemoteModule module, Set<Menu> menus);
}

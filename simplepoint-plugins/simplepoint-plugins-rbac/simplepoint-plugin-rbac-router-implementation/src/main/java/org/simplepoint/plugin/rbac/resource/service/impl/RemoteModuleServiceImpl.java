package org.simplepoint.plugin.rbac.resource.service.impl;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.resource.api.repository.RemoteModuleRepository;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.resource.api.vo.MicroModuleItemVo;
import org.simplepoint.plugin.rbac.resource.service.support.RemoteEntryVersioner;
import org.simplepoint.remoting.RemoteProvider;
import org.simplepoint.security.entity.MicroModule;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link MicroAppService} providing business logic for remote module management.
 *
 * <p>This service handles CRUD operations for remote modules by interacting with {@link RemoteModuleRepository}.
 * It extends {@link BaseServiceImpl} to inherit standard data operations.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Service
@RemoteProvider
public class RemoteModuleServiceImpl
    extends BaseServiceImpl<RemoteModuleRepository, MicroModule, String>
    implements MicroAppService {

  private final RemoteEntryVersioner remoteEntryVersioner = new RemoteEntryVersioner();

  /**
   * Constructs a new RemoteModuleServiceImpl with the specified repository and authorization context holder.
   *
   * @param repository                 the repository for remote module data access
   * @param detailsProviderService     the service for providing user details, used for authorization checks
   */
  public RemoteModuleServiceImpl(
      RemoteModuleRepository repository,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, detailsProviderService);
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  /**
   * Loads all registered remote modules.
   *
   * @return a set of {@link MicroModuleItemVo} representing the loaded remote modules
   */
  @Override
  public Set<MicroModuleItemVo> loadApps() {
    Set<MicroModuleItemVo> remotes = new LinkedHashSet<>();
    for (MicroModule module : getRepository().findAll(Map.of())) {
      String serviceName = trimToNull(module.getServiceName());
      if (serviceName == null) {
        continue;
      }
      remotes.add(new MicroModuleItemVo(serviceName, versionedEntry(module)));
    }
    return remotes;
  }

  private String versionedEntry(MicroModule module) {
    return remoteEntryVersioner.versioned(
        module.getEntry(),
        module.getPluginId(),
        module.getPluginVersion(),
        module.getRemoteVersion(),
        module.getPluginArtifactSha256());
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}

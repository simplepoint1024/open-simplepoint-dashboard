package org.simplepoint.plugin.rbac.core.api.repository;

import java.util.Optional;
import org.simplepoint.security.entity.PlatformIdentity;

public interface PlatformIdentityRepository {
  Optional<PlatformIdentity> findById(String userId);
}

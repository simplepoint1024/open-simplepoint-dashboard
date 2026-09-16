package org.simplepoint.plugin.rbac.core.base.repository;

import org.simplepoint.security.entity.PlatformIdentity;
import org.simplepoint.plugin.rbac.core.api.repository.PlatformIdentityRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPlatformIdentityRepository extends JpaRepository<PlatformIdentity, String>, PlatformIdentityRepository { }

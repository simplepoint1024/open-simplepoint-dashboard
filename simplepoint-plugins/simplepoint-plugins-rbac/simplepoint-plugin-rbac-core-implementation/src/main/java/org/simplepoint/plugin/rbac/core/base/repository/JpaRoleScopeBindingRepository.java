package org.simplepoint.plugin.rbac.core.base.repository;

import org.simplepoint.plugin.rbac.core.api.repository.RoleScopeBindingRepository;
import org.simplepoint.security.entity.RoleScopeBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaRoleScopeBindingRepository extends JpaRepository<RoleScopeBinding, String>, RoleScopeBindingRepository {
}

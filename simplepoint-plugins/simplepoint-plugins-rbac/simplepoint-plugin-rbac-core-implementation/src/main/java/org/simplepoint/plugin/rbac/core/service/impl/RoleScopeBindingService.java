package org.simplepoint.plugin.rbac.core.service.impl;

import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleScopeAssignmentVo;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.core.api.repository.RoleScopeBindingRepository;
import org.simplepoint.security.entity.RoleResourceGrant;
import org.simplepoint.security.entity.RoleScopeBinding;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lazy compatibility reads preserve legacy policy combinations until explicitly replaced. */
@Service
public class RoleScopeBindingService {
  private final RoleScopeBindingRepository bindings;
  private final RoleResourceGrantRepository grants;
  private final ScopePolicyValidator validator;

  public RoleScopeBindingService(RoleScopeBindingRepository bindings, RoleResourceGrantRepository grants,
                                ScopePolicyValidator validator) {
    this.bindings = bindings;
    this.grants = grants;
    this.validator = validator;
  }

  public RoleScopeAssignmentVo read(String tenantId, String roleId) {
    RoleScopeAssignmentVo result = new RoleScopeAssignmentVo();
    result.setRoleId(roleId);
    var binding = bindings.findByTenantIdAndRoleId(tenantId, roleId);
    if (binding.isPresent()) {
      result.setDataScopeId(binding.get().getDataScopeId());
      result.setFieldScopeId(binding.get().getFieldScopeId());
      result.setRevision(binding.get().getRevision());
      return result;
    }
    List<RoleResourceGrant> legacy = grants.findByRoleIdIn(List.of(roleId)).stream()
        .filter(grant -> tenantId.equals(grant.getTenantId()) && grant.getDeletedAt() == null).toList();
    List<String> dataIds = legacy.stream().map(RoleResourceGrant::getDataScopeId).filter(Objects::nonNull).distinct().sorted().toList();
    List<String> fieldIds = legacy.stream().map(RoleResourceGrant::getFieldScopeId).filter(Objects::nonNull).distinct().sorted().toList();
    result.setDataScopeId(dataIds.size() == 1 ? dataIds.getFirst() : null);
    result.setFieldScopeId(fieldIds.size() == 1 ? fieldIds.getFirst() : null);
    result.setLegacyConflict(dataIds.size() > 1 || fieldIds.size() > 1);
    result.setLegacyDataScopeIds(dataIds);
    result.setLegacyFieldScopeIds(fieldIds);
    return result;
  }

  @Transactional(rollbackFor = Exception.class)
  public void save(String tenantId, RoleScopeAssignmentVo assignment) {
    if (assignment == null || assignment.getRoleId() == null || assignment.getRoleId().isBlank()) {
      throw new IllegalArgumentException("roleId is required");
    }
    validator.validate(tenantId, assignment.getDataScopeId(), assignment.getFieldScopeId());
    RoleScopeBinding binding = bindings.findByTenantIdAndRoleId(tenantId, assignment.getRoleId())
        .orElseGet(RoleScopeBinding::new);
    if (!Objects.equals(assignment.getRevision(), binding.getRevision())) {
      throw new OptimisticLockingFailureException("范围配置已更新，请重新加载后保存");
    }
    if (binding.getId() == null && read(tenantId, assignment.getRoleId()).isLegacyConflict()
        && !assignment.isConfirmLegacyReplacement()) {
      throw new IllegalArgumentException("旧授权包含多个范围，请确认替换后保存");
    }
    binding.setTenantId(tenantId);
    binding.setRoleId(assignment.getRoleId());
    binding.setDataScopeId(assignment.getDataScopeId());
    binding.setFieldScopeId(assignment.getFieldScopeId());
    bindings.save(binding);
  }

  /** Legacy grant requests must not silently change an independently saved role policy. */
  public void validateLegacyAssignment(String tenantId, String roleId, String dataScopeId, String fieldScopeId) {
    if (dataScopeId == null && fieldScopeId == null) return;
    bindings.findByTenantIdAndRoleId(tenantId, roleId).ifPresent(binding -> {
      if ((dataScopeId != null && !Objects.equals(dataScopeId, binding.getDataScopeId()))
          || (fieldScopeId != null && !Objects.equals(fieldScopeId, binding.getFieldScopeId()))) {
        throw new IllegalArgumentException("角色范围已独立配置，请通过 scope-assignment 接口修改");
      }
    });
  }
}

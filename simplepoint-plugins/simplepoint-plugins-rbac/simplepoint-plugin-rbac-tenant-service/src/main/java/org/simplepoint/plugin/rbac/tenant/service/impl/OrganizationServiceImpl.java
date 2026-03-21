package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.OrganizationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped organization management service.
 */
@Service
public class OrganizationServiceImpl extends BaseServiceImpl<OrganizationRepository, Organization, String>
    implements OrganizationService {

  private final OrganizationRepository organizationRepository;

  public OrganizationServiceImpl(
      OrganizationRepository repository,
      DetailsProviderService detailsProviderService
  ) {
    super(repository, detailsProviderService);
    this.organizationRepository = repository;
  }

  @Override
  public Map<String, Object> schema() {
    Map<String, Object> schema = super.schema();
    String tenantId = currentTenantId(false);
    Map<String, Object> jsonSchema = asMap(schema.get("schema"));
    if (jsonSchema == null) {
      return schema;
    }
    Map<String, Object> properties = asMap(jsonSchema.get("properties"));
    if (properties == null) {
      return schema;
    }
    Map<String, Object> parentId = asMap(properties.get("parentId"));
    if (parentId != null) {
      parentId.put("oneOf", buildParentOptions(tenantId, null));
    }
    return schema;
  }

  @Override
  public <S extends Organization> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    if (currentTenantId(false) == null) {
      return Page.empty(pageable);
    }
    return super.limit(attributes, pageable);
  }

  @Override
  public <S extends Organization> S create(S entity) {
    String tenantId = currentTenantId(true);
    normalizeEntity(entity);
    validateUniqueCode(tenantId, entity.getCode(), null);
    validateParent(tenantId, entity.getParentId(), null);
    entity.setTenantId(tenantId);
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    return super.create(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Organization> Organization modifyById(S entity) {
    String tenantId = currentTenantId(true);
    Organization current = organizationRepository.findByIdAndTenantId(requireId(entity.getId()), tenantId)
        .orElseThrow(() -> new IllegalArgumentException("组织机构不存在"));
    normalizeEntity(entity);
    validateUniqueCode(tenantId, entity.getCode(), current.getId());
    validateParent(tenantId, entity.getParentId(), current.getId());
    entity.setTenantId(current.getTenantId());
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled() == null ? true : current.getEnabled());
    }
    return (Organization) super.modifyById(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    Set<String> normalizedIds = normalizeIds(ids);
    if (normalizedIds.isEmpty()) {
      return;
    }
    String tenantId = currentTenantId(true);
    Collection<Organization> organizations = organizationRepository.findAllByIdsAndTenantId(normalizedIds, tenantId);
    if (organizations.size() != normalizedIds.size()) {
      throw new IllegalArgumentException("存在无权操作的组织机构或组织机构不存在");
    }
    Set<String> childIds = new LinkedHashSet<>(organizationRepository.findIdsByParentIds(normalizedIds, tenantId));
    childIds.removeAll(normalizedIds);
    if (!childIds.isEmpty()) {
      throw new IllegalArgumentException("请先删除子组织机构后再删除当前组织");
    }
    super.removeByIds(normalizedIds);
  }

  private List<Map<String, Object>> buildParentOptions(String tenantId, String excludeId) {
    if (tenantId == null || tenantId.isBlank()) {
      return List.of();
    }
    return organizationRepository.findAllByTenantId(tenantId)
        .stream()
        .filter(organization -> !Objects.equals(organization.getId(), excludeId))
        .map(organization -> Map.<String, Object>of(
            "const", organization.getId(),
            "title", buildOrganizationLabel(organization)
        ))
        .toList();
  }

  private static String buildOrganizationLabel(Organization organization) {
    if (organization.getCode() == null || organization.getCode().isBlank()) {
      return organization.getName();
    }
    return organization.getName() + " (" + organization.getCode() + ")";
  }

  private void normalizeEntity(Organization entity) {
    entity.setName(requireText(entity.getName(), "组织名称不能为空"));
    entity.setCode(requireText(entity.getCode(), "组织编码不能为空"));
    entity.setParentId(trimToNull(entity.getParentId()));
    entity.setDescription(trimToNull(entity.getDescription()));
  }

  private void validateUniqueCode(String tenantId, String code, String currentId) {
    boolean exists = currentId == null
        ? organizationRepository.existsByTenantIdAndCode(tenantId, code)
        : organizationRepository.existsByTenantIdAndCodeAndIdNot(tenantId, code, currentId);
    if (exists) {
      throw new IllegalArgumentException("当前租户下组织编码已存在");
    }
  }

  private void validateParent(String tenantId, String parentId, String currentId) {
    String normalizedParentId = trimToNull(parentId);
    if (normalizedParentId == null) {
      return;
    }
    if (Objects.equals(currentId, normalizedParentId)) {
      throw new IllegalArgumentException("上级组织不能选择自己");
    }
    Organization parent = organizationRepository.findByIdAndTenantId(normalizedParentId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("上级组织不存在"));
    String cursor = trimToNull(parent.getParentId());
    Set<String> visited = new LinkedHashSet<>();
    visited.add(parent.getId());
    while (cursor != null) {
      if (Objects.equals(currentId, cursor)) {
        throw new IllegalArgumentException("不能将组织移动到自己的下级组织下");
      }
      if (!visited.add(cursor)) {
        throw new IllegalArgumentException("组织层级数据异常，请检查上级组织配置");
      }
      Organization current = organizationRepository.findByIdAndTenantId(cursor, tenantId)
          .orElseThrow(() -> new IllegalArgumentException("组织层级数据异常，请检查上级组织配置"));
      cursor = trimToNull(current.getParentId());
    }
  }

  private String currentTenantId(boolean required) {
    String tenantId = getAuthorizationContext() == null ? null : trimToNull(getAuthorizationContext().getAttribute("X-Tenant-Id"));
    if (required && tenantId == null) {
      throw new IllegalArgumentException("请先选择租户");
    }
    return tenantId;
  }

  private static Set<String> normalizeIds(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return Set.of();
    }
    return ids.stream()
        .map(OrganizationServiceImpl::trimToNull)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static String requireId(String id) {
    String normalizedId = trimToNull(id);
    if (normalizedId == null) {
      throw new IllegalArgumentException("组织机构标识不能为空");
    }
    return normalizedId;
  }

  private static String requireText(String value, String message) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return null;
  }
}

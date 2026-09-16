package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.constants.TenantDictionaryCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryItemRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.OrganizationService;
import org.simplepoint.plugin.rbac.tenant.api.vo.DictionaryOptionVo;
import org.simplepoint.plugin.rbac.tenant.api.vo.OrganizationOptionPage;
import org.simplepoint.plugin.rbac.tenant.api.vo.OrganizationOptionVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped organization management service.
 */
@Service
public class OrganizationServiceImpl extends BaseServiceImpl<OrganizationRepository, Organization, String>
    implements OrganizationService {

  private final OrganizationRepository organizationRepository;
  private final DictionaryItemRepository dictionaryItemRepository;
  private final org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService authorizationVersions;

  /**
   * Creates the organization service.
   *
   * @param repository               the organization repository
   * @param detailsProviderService   the schema/details provider
   * @param dictionaryItemRepository the dictionary item repository
   */
  public OrganizationServiceImpl(
      OrganizationRepository repository,
      DetailsProviderService detailsProviderService,
      DictionaryItemRepository dictionaryItemRepository,
      org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService authorizationVersions
  ) {
    super(repository, detailsProviderService);
    this.organizationRepository = repository;
    this.dictionaryItemRepository = dictionaryItemRepository;
    this.authorizationVersions = authorizationVersions;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  public <S extends Organization> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    if (currentTenantId(false) == null) {
      return Page.empty(pageable);
    }
    return super.limit(attributes, pageable);
  }

  @Override
  @Transactional(readOnly = true)
  public OrganizationOptionPage options(
      String keyword,
      String parentId,
      String ids,
      String excludeId,
      boolean flat,
      Pageable pageable
  ) {
    String tenantId = currentTenantId(false);
    int pageNumber = pageable != null && pageable.isPaged() ? Math.max(0, pageable.getPageNumber()) : 0;
    int pageSize = pageable != null && pageable.isPaged() ? Math.min(100, Math.max(1, pageable.getPageSize())) : 30;
    if (tenantId == null) {
      return new OrganizationOptionPage(List.of(), pageNumber, pageSize, false);
    }

    String normalizedExcludeId = trimToNull(excludeId);
    Set<String> selectedIds = parseIds(ids);
    if (!selectedIds.isEmpty()) {
      List<Organization> selected = organizationRepository.findAllByIdsAndTenantId(selectedIds, tenantId)
          .stream()
          .filter(organization -> !Objects.equals(organization.getId(), normalizedExcludeId))
          .sorted(organizationComparator())
          .toList();
      return optionPage(selected, tenantId, pageNumber, pageSize, false);
    }

    Pageable optionPageable = PageRequest.of(pageNumber, pageSize);
    String normalizedKeyword = trimToNull(keyword);
    Slice<Organization> organizations;
    if (normalizedKeyword != null) {
      String foldedKeyword = normalizedKeyword.substring(0, Math.min(normalizedKeyword.length(), 128))
          .toLowerCase(Locale.ROOT);
      organizations = organizationRepository.searchOptions(
          tenantId,
          foldedKeyword,
          "%" + foldedKeyword + "%",
          normalizedExcludeId,
          optionPageable
      );
    } else if (flat) {
      organizations = organizationRepository.findFlatOptions(tenantId, normalizedExcludeId, optionPageable);
    } else {
      String normalizedParentId = trimToNull(parentId);
      organizations = normalizedParentId == null
          ? organizationRepository.findRootOptions(tenantId, normalizedExcludeId, optionPageable)
          : organizationRepository.findChildOptions(
              tenantId,
              normalizedParentId,
              normalizedExcludeId,
              optionPageable
          );
    }
    return optionPage(
        organizations.getContent(),
        tenantId,
        organizations.getNumber(),
        organizations.getSize(),
        organizations.hasNext()
    );
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Organization> S create(S entity) {
    String tenantId = currentTenantId(true);
    normalizeEntity(entity);
    validateUniqueCode(tenantId, entity.getCode(), null);
    validateParent(tenantId, entity.getParentId(), null);
    entity.setTenantId(tenantId);
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    S result = super.create(entity);
    authorizationVersions.refreshTenant(tenantId);
    return result;
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
    Organization result = super.modifyById(entity);
    authorizationVersions.refreshTenant(tenantId);
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<Organization> create(Collection<Organization> entities) {
    return entities.stream().map(this::create).toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeById(String id) {
    removeByIds(List.of(id));
  }

  @Override
  public void removeAll() {
    throw new org.springframework.security.access.AccessDeniedException("请按组织标识删除，批量清空组织不受支持");
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
    authorizationVersions.refreshTenant(tenantId);
  }

  private OrganizationOptionPage optionPage(
      Collection<Organization> organizations,
      String tenantId,
      int pageNumber,
      int pageSize,
      boolean hasNext
  ) {
    Set<String> organizationIds = organizations.stream()
        .map(Organization::getId)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> parentIds = organizationIds.isEmpty()
        ? Set.of()
        : new HashSet<>(organizationRepository.findParentIdsWithChildren(organizationIds, tenantId));
    List<OrganizationOptionVo> content = organizations.stream()
        .map(organization -> new OrganizationOptionVo(
            organization.getId(),
            organization.getName(),
            organization.getCode(),
            organization.getParentId(),
            organization.getType(),
            organization.getDescription(),
            organization.getSort(),
            !Boolean.FALSE.equals(organization.getEnabled()),
            parentIds.contains(organization.getId())
        ))
        .toList();
    return new OrganizationOptionPage(content, pageNumber, pageSize, hasNext);
  }

  private static Comparator<Organization> organizationComparator() {
    return Comparator
        .comparing(Organization::getSort, Comparator.nullsLast(Integer::compareTo))
        .thenComparing(Organization::getName, Comparator.nullsLast(String::compareTo))
        .thenComparing(Organization::getCode, Comparator.nullsLast(String::compareTo))
        .thenComparing(Organization::getId, Comparator.nullsLast(String::compareTo));
  }

  private static Set<String> parseIds(String ids) {
    String normalized = trimToNull(ids);
    if (normalized == null) {
      return Set.of();
    }
    return normalizeIds(List.of(normalized.split(","))).stream()
        .limit(100)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private void normalizeEntity(Organization entity) {
    entity.setName(requireText(entity.getName(), "组织名称不能为空"));
    entity.setCode(requireText(entity.getCode(), "组织编码不能为空"));
    entity.setType(requireOrganizationType(entity.getType()));
    entity.setParentId(trimToNull(entity.getParentId()));
    entity.setDescription(trimToNull(entity.getDescription()));
  }

  private String requireOrganizationType(String type) {
    String normalized = requireText(type, "组织类型不能为空");
    boolean supported = dictionaryItemRepository.options(TenantDictionaryCodes.ORGANIZATION_TYPE)
        .stream()
        .map(DictionaryOptionVo::value)
        .anyMatch(normalized::equals);
    if (!supported) {
      throw new IllegalArgumentException("组织类型无效");
    }
    return normalized;
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

}

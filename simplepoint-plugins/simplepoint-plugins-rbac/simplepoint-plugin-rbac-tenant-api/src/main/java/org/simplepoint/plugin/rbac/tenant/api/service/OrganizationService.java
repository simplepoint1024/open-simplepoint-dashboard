package org.simplepoint.plugin.rbac.tenant.api.service;

import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.vo.OrganizationOptionPage;
import org.springframework.data.domain.Pageable;

/**
 * Service for tenant-scoped organization management.
 */
public interface OrganizationService extends BaseService<Organization, String> {

  /**
   * Loads one lightweight page for a lazy organization selector.
   *
   * @param keyword   optional name/code keyword; when present the hierarchy filter is ignored
   * @param parentId  parent whose direct children should be returned; {@code null} loads roots
   * @param ids       optional comma-separated ids used to resolve selected values
   * @param excludeId optional organization id to hide, normally the record being edited
   * @param flat      whether to page all organizations instead of loading hierarchy roots
   * @param pageable  requested page
   * @return count-free organization option page
   */
  OrganizationOptionPage options(
      String keyword,
      String parentId,
      String ids,
      String excludeId,
      boolean flat,
      Pageable pageable
  );
}

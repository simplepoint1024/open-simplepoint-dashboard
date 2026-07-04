package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;

/**
 * Repository for application resource relations.
 */
public interface ApplicationResourceRelevanceRepository {

  <S extends ApplicationResourceRelevance> List<S> saveAll(Iterable<S> entities);

  void unauthorized(String applicationCode, Set<String> resourceCodes);

  Collection<String> authorized(String applicationCode);

  void deleteAllByApplicationCodes(Collection<String> applicationCodes);

  void deleteAllByResourceCodes(Collection<String> resourceCodes);

  void updateApplicationCode(String oldCode, String newCode);

  void updateResourceCode(String oldCode, String newCode);
}

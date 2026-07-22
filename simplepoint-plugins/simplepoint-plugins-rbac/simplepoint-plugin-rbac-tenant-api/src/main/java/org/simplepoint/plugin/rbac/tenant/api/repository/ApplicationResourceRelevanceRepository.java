package org.simplepoint.plugin.rbac.tenant.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.rbac.tenant.api.entity.ApplicationResourceRelevance;

/**
 * Repository for application resource relations.
 */
public interface ApplicationResourceRelevanceRepository {

  /** Persists all supplied application resource relations. */
  <S extends ApplicationResourceRelevance> List<S> saveAll(Iterable<S> entities);

  /** Removes the specified resource assignments from an application. */
  void unauthorized(String applicationCode, Set<String> resourceCodes);

  /** Returns the resource codes assigned to an application. */
  Collection<String> authorized(String applicationCode);

  /** Deletes relations owned by any of the supplied applications. */
  void deleteAllByApplicationCodes(Collection<String> applicationCodes);

  /** Deletes relations referring to any of the supplied resources. */
  void deleteAllByResourceCodes(Collection<String> resourceCodes);

  /** Replaces an existing application code in all relations. */
  void updateApplicationCode(String oldCode, String newCode);

  /** Replaces an existing resource code in all relations. */
  void updateResourceCode(String oldCode, String newCode);
}

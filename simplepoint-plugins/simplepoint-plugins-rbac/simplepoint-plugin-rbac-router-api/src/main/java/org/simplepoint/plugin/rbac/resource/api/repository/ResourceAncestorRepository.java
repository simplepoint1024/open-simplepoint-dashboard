package org.simplepoint.plugin.rbac.resource.api.repository;

import java.util.Collection;
import java.util.List;
import org.simplepoint.security.entity.ResourceAncestor;

/**
 * Repository for resource ancestor relations.
 */
public interface ResourceAncestorRepository {

  <S extends ResourceAncestor> S save(S entity);

  <S extends ResourceAncestor> List<S> saveAll(Iterable<S> entities);

  void deleteChild(Collection<String> childIds);

  void deleteAncestor(String ancestorId);

  Collection<String> findAncestorIdsByChildIdIn(Collection<String> ids);

  Collection<String> findChildIdsByAncestorIds(Collection<String> ids);
}

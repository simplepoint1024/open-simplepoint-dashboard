package org.simplepoint.data.jpa.base;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.simplepoint.api.base.QueryMembership;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * A base repository interface for generic CRUD operations on entities.
 * This interface extends the core base repository and Spring's Repository,
 * providing a unified approach to persistence functionality.
 *
 * @param <T> the type of the entity, extending BaseEntity
 * @param <I> the type of the entity's identifier, which must be serializable
 */
@NoRepositoryBean
public interface BaseRepository<T extends BaseEntityImpl<I>, I extends Serializable>
    extends org.simplepoint.api.base.BaseRepository<T, I>, Repository<T, I> {

  // Spring Data invokes interface default methods before the repository target.
  // Redeclare supported capabilities so JPA proxies reach BaseRepositoryImpl;
  // the API defaults continue to reject unsupported non-JPA implementations.
  @Override
  <S extends T> Page<S> limitScoped(
      Map<String, String> attributes, Map<String, String> required, Pageable pageable);

  @Override
  <S extends T> Page<S> limitInScope(
      Map<String, String> attributes, String field, Collection<String> allowedValues, Pageable pageable);

  @Override
  <S extends T> Page<S> limitMemberships(
      Map<String, String> attributes, List<QueryMembership> memberships, Pageable pageable);
}

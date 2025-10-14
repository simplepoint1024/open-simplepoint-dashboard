package org.simplepoint.api.base.audit;

import java.io.Serializable;
import java.util.Collection;
import org.simplepoint.api.base.BaseDetailsService;
import org.simplepoint.api.base.BaseEntity;

/**
 * Service for auditing modifications to data.
 */
public interface ModifyDataAuditingService extends BaseDetailsService {

  /**
   * Compare the original and modified entities and log the differences.
   *
   * @param originalEntity the original entity before modification
   * @param modifiedEntity the modified entity after changes
   * @param entityClass    the class of the entity
   * @param <I>            the type of the primary key
   * @param <T>            the type of the base entity
   * @param <S>            the type of the modified entity
   */
  <I extends Serializable, T extends BaseEntity<I>, S extends T> void modify(T originalEntity, S modifiedEntity, Class<T> entityClass);

  /**
   * Log the creation of a new entity.
   *
   * @param entity      the newly created entity
   * @param entityClass the class of the entity
   * @param <I>         the type of the primary key
   * @param <T>         the type of the base entity
   * @param <S>         the type of the created entity
   */
  <I extends Serializable, T extends BaseEntity<I>, S extends T> void save(Collection<S> entity, Class<T> entityClass);

  /**
   * Log the deletion of an entity.
   *
   * @param entity      the entity that was deleted
   * @param entityClass the class of the entity
   * @param <I>         the type of the primary key
   * @param <T>         the type of the base entity
   */
  <I extends Serializable, T extends BaseEntity<I>> void delete(Collection<T> entity, Class<T> entityClass);

}

package org.simplepoint.data.jpa.base;

import java.io.Serializable;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
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
}

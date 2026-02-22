package org.simplepoint.security.cache;

/**
 * AuthorityRecord is a simple record class that represents an authority record in the cache.
 * It contains two fields: id and authority, which represent the unique identifier of the record and the authority string, respectively.
 *
 * <p>This class is used to store and manage authority information in the cache, allowing for efficient retrieval and management of user roles and permissions.
 */
public record AuthorityRecord(
    String id,
    String authority
) {
}

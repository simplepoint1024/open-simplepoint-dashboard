package org.simplepoint.cache;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;

/**
 * CacheService is an interface that defines the contract for a caching service in the SimplePoint application.
 * It can be implemented to provide caching functionality using various caching technologies (e.g., Redis, Ehcache).
 * The specific methods for caching operations (e.g., get, put, evict) would be defined in the implementation classes.
 */
public interface CacheService {

  /**
   * Puts a value into the cache with a specified key and time-to-live (TTL).
   *
   * @param key        the key under which the value will be cached
   * @param value      the value to be cached, which must be serializable
   * @param ttlSeconds the time-to-live for the cached value in seconds
   * @param <T>        the type of the value being cached, which must implement Serializable
   */
  <T extends Serializable> void put(String key, T value, long ttlSeconds);

  /**
   * Puts a value into the cache with a specified key without setting a time-to-live (TTL).
   *
   * @param key   the key under which the value will be cached
   * @param value the value to be cached, which must be serializable
   * @param <T>   the type of the value being cached, which must implement Serializable
   */
  <T extends Serializable> void put(String key, T value);

  /**
   * Retrieves a value from the cache by its key and casts it to the specified type.
   *
   * @param key  the key of the cached value to retrieve
   * @param type the Class object representing the type to which the cached value should be cast
   * @param <T>  the type of the value being retrieved, which must implement Serializable
   * @return the cached value cast to the specified type, or null if not found or if the type does not match
   */
  <T extends Serializable> T get(String key, Class<T> type);

  /**
   * Retrieves a value from the cache by its key and casts it to the specified type, with a specified time-to-live (TTL).
   *
   * @param key        the key of the cached value to retrieve
   * @param type       the Class object representing the type to which the cached value should be cast
   * @param ttlSeconds the time-to-live for the cached value in seconds
   * @param <T>        the type of the value being retrieved, which must implement Serializable
   * @return the cached value cast to the specified type, or null if not found or if the type does not match
   */
  <T extends Serializable> T get(String key, Class<T> type, long ttlSeconds);

  /**
   * Retrieves a collection of values from the cache by their keys and casts them to the specified type.
   *
   * @param keys the collection of keys for the cached values to retrieve
   * @param type the Class object representing the type to which the cached values should be cast
   * @param <T>  the type of the values being retrieved, which must implement Serializable
   * @return a collection of cached values cast to the specified type, or an empty collection if not found or if the type does not match
   */
  <T extends Serializable> Collection<T> multipleGet(Collection<String> keys, Class<T> type);

  /**
   * Retrieves a map of values from the cache by their keys and casts them to the specified type.
   *
   * @param keys the collection of keys for the cached values to retrieve
   * @param type the Class object representing the type to which the cached values should be cast
   * @param <T>  the type of the values being retrieved, which must implement Serializable
   * @return a map of cached values cast to the specified type, where the keys are the original keys and the values are the cached values, or an empty map if not found or if the type does not match
   */
  <T extends Serializable> Map<String, T> multipleGetAsMap(Collection<String> keys, Class<T> type);

  /**
   * Deletes a value from the cache by its key.
   *
   * @param key the key of the cached value to delete
   */
  void delete(String key);

  /**
   * Retrieves a collection of values from the cache by their key and casts them to the specified type.
   *
   * @param key  the key of the cached values to retrieve
   * @param type the Class object representing the type to which the cached values should be cast
   * @param <T>  the type of the values being retrieved, which must implement Serializable
   * @return a collection of cached values cast to the specified type, or an empty collection if not found or if the type does not match
   */
  <T extends Serializable> Collection<T> getAll(String key, Class<T> type);
}

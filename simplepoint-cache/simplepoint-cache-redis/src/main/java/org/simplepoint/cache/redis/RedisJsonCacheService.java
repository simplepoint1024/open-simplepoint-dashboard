package org.simplepoint.cache.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.simplepoint.cache.CacheService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * RedisJsonCacheService is an implementation of the CacheService interface that uses Redis as the underlying caching mechanism.
 * This class provides methods for putting, getting, and evicting cache entries in Redis, with support for JSON serialization and deserialization.
 * The actual implementation of the caching logic (e.g., interacting with Redis) is not provided in this code snippet and should be implemented as needed.
 */
public class RedisJsonCacheService implements CacheService {

  private final ObjectMapper objectMapper;

  private final ValueOperations<String, String> operations;

  /**
   * Constructs a RedisJsonCacheService with the specified ObjectMapper for JSON serialization and deserialization.
   *
   * @param objectMapper the ObjectMapper to use for converting objects to and from JSON when storing and retrieving cache entries
   */
  public RedisJsonCacheService(
      ObjectMapper objectMapper,
      RedisTemplate<String, String> redisTemplate
  ) {
    this.objectMapper = objectMapper;
    operations = redisTemplate.opsForValue();
  }

  @Override
  public <T extends Serializable> void put(String key, T value, long ttlSeconds) {
    try {
      this.operations.set(key, objectMapper.writeValueAsString(value), ttlSeconds, TimeUnit.SECONDS);
    } catch (JsonProcessingException e) {
      throw new SerializationException("Failed to serialize value for key: " + key, e);
    }
  }

  @Override
  public <T extends Serializable> void put(String key, T value) {
    try {
      this.operations.set(key, objectMapper.writeValueAsString(value));
    } catch (JsonProcessingException e) {
      throw new SerializationException("Failed to serialize value for key: " + key, e);
    }
  }

  @Override
  public <T extends Serializable> T get(String key, Class<T> type) {
    String json = this.operations.get(key);
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new SerializationException("Failed to deserialize value for key: " + key, e);
    }
  }

  @Override
  public <T extends Serializable> T get(String key, Class<T> type, long ttlSeconds) {
    String json = this.operations.getAndExpire(key, ttlSeconds, TimeUnit.SECONDS);
    if (json == null) {
      return null;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new SerializationException("Failed to deserialize value for key: " + key, e);
    }
  }

  @Override
  public <T extends Serializable> Collection<T> multipleGet(Collection<String> keys, Class<T> type) {
    List<String> jsons = this.operations.multiGet(keys);
    if (jsons == null) {
      return List.of();
    }
    jsons.removeIf(Objects::isNull);
    Collection<T> results = new ArrayList<>(jsons.size());
    for (String json : jsons) {
      try {
        results.add(objectMapper.readValue(json, type));
      } catch (JsonProcessingException e) {
        throw new SerializationException("Failed to deserialize value for keys: " + keys, e);
      }
    }
    return results;
  }

  @Override
  public <T extends Serializable> Map<String, T> multipleGetAsMap(Collection<String> keys, Class<T> type) {
    List<String> jsons = this.operations.multiGet(keys);
    if (jsons == null) {
      return Map.of();
    }
    jsons.removeIf(Objects::isNull);
    Map<String, T> results = new HashMap<>(jsons.size());
    int index = 0;
    for (String json : jsons) {
      try {
        results.put(((List<String>) keys).get(index), objectMapper.readValue(json, type));
      } catch (JsonProcessingException e) {
        throw new SerializationException("Failed to deserialize value for keys: " + keys, e);
      }
      index++;
    }
    return results;
  }

  @Override
  public void delete(String key) {
    this.operations.getOperations().delete(key);
  }

  @Override
  public <T extends Serializable> Collection<T> getAll(String key, Class<T> type) {
    String json = this.operations.get(key);
    if (json == null) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, type));
    } catch (JsonProcessingException e) {
      throw new SerializationException("Failed to deserialize value for key: " + key, e);
    }
  }
}

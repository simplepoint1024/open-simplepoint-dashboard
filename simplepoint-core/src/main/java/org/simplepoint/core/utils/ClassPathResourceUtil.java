package org.simplepoint.core.utils;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Utility class for reading JSON resources from the classpath.
 */
public class ClassPathResourceUtil {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  static {
    objectMapper.configure(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION.mappedFeature(), true);
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  /**
   * Reads all JSON files from the specified directory in the classpath
   * and deserializes them into objects of the specified class.
   *
   * @param dir   the directory in the classpath to scan for JSON files
   * @param clazz the class to deserialize the JSON files into
   * @param <T>   the type of the deserialized objects
   * @return a map where the keys are filenames (without .json extension)
   *         and the values are the deserialized objects
   * @throws IOException if an I/O error occurs during reading
   */
  public static <T> Map<String, T> readJson(String dir, Class<T> clazz) throws IOException {
    Map<String, T> result = new HashMap<>();
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    // 使用 classpath*: 兼容 JAR 内扫描
    Resource[] resources = resolver.getResources("classpath*:" + dir + "/*.json");

    for (Resource resource : resources) {
      String filename = resource.getFilename();
      if (filename == null || !filename.toLowerCase().endsWith(".json")) {
        continue;
      }

      T obj = objectMapper.readValue(resource.getInputStream(), clazz);
      String key = filename.substring(0, filename.length() - 5); // 去掉 .json
      result.put(key, obj);
    }

    return result;
  }


  /**
   * Reads all JSON files from the specified directory in the classpath,
   * organized by language subdirectories, and deserializes them into maps.
   *
   * @param dir the directory in the classpath to scan for JSON files
   * @return a nested map where the first key is the language,
   *         the second key is the filename (without .json extension),
   *         and the value is the deserialized map from the JSON file
   * @throws IOException if an I/O error occurs during reading
   */
  public static Map<String, Map<String, Map<String, String>>> readJsonPathMap(String dir) throws IOException {
    Map<String, Map<String, Map<String, String>>> result = new HashMap<>();
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    // classpath*: 兼容 JAR 内扫描
    Resource[] resources = resolver.getResources("classpath*:" + dir + "/**/*.json");

    for (Resource resource : resources) {
      String filename = resource.getFilename();
      if (filename == null || !filename.toLowerCase().endsWith(".json")) {
        continue;
      }

      // 使用 URI + Paths 解析目录名（跨平台 + JAR 兼容）
      URI uri = resource.getURI();
      String path = uri.toString(); // 统一格式，不受 OS 影响

      // 解析语言目录名：倒数第二层目录
      String[] parts = path.split("/");
      if (parts.length < 2) {
        continue;
      }
      String lang = parts[parts.length - 2];

      // 反序列化 JSON
      Map<String, String> obj = objectMapper.readValue(
          resource.getInputStream(),
          new TypeReference<>() {
          }
      );

      // 去掉 .json
      String key = filename.substring(0, filename.length() - 5);

      result.computeIfAbsent(lang, k -> new HashMap<>())
          .put(key, obj);
    }

    return result;
  }
}

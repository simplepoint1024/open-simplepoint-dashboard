package org.simplepoint.core.utils;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Utility class to load resources from the classpath.
 */
public class ClassPathResourceUtil {
  private static final ObjectMapper objectMapper = new ObjectMapper();

  static {
    objectMapper.configure(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION.mappedFeature(), true);
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  /**
   * Loads properties from JSON files located in the specified directory within the classpath.
   *
   * @param dir   The directory path within the classpath.
   * @param clazz The class type to which the JSON files will be mapped.
   * @param <T>   The type of the properties to be loaded.
   * @return A map of file names (without .json extension) to their corresponding properties.
   * @throws IOException If an I/O error occurs while reading the files.
   */
  public static <T> Map<String, T> readJson(String dir, Class<T> clazz) throws IOException {
    Map<String, T> properties = new HashMap<>();
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    // 扫描 classpath 下的目录
    Resource[] resources = resolver.getResources("classpath:" + dir + "/*.json");

    for (Resource resource : resources) {
      String fileName = resource.getFilename();
      if (fileName != null && fileName.endsWith(".json")) {
        T prop = objectMapper.readValue(resource.getInputStream(), clazz);
        properties.put(fileName.replace(".json", ""), prop);
      }
    }
    return properties;
  }

  /**
   * 读取多语言目录下的 JSON 文件，返回嵌套 Map 结构.
   *
   * @param dir 目录路径
   * @return 嵌套 Map 结构，第一层键为语言目录名，第二层键为文件名（不含 .json），第三层为文件内容的键值对
   * @throws IOException 如果读取文件时发生 I/O 错误
   */
  public static Map<String, Map<String, Map<String, String>>> readJsonPathMap(String dir) throws IOException {
    Map<String, Map<String, Map<String, String>>> result = new HashMap<>();
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    // 扫描所有语言目录下的 json 文件
    Resource[] resources = resolver.getResources("classpath:" + dir + "/**/*.json");

    for (Resource resource : resources) {
      String filename = resource.getFilename();
      if (filename == null || !filename.endsWith(".json")) {
        continue;
      }

      // 解析语言目录名，例如 zh-CN
      String desc = resource.getDescription(); // 更稳妥，避免 JAR 内路径问题
      String[] parts = desc.split("/");
      String lang = parts[parts.length - 2]; // 倒数第二层目录作为语言

      // 反序列化 JSON 为 Map<String,String>
      Map<String, String> obj = objectMapper.readValue(resource.getInputStream(),
          new TypeReference<>() {
          });

      // 放入结果 Map
      result.computeIfAbsent(lang, k -> new HashMap<>())
          .put(filename.replace(".json", ""), obj);
    }

    return result;
  }

}

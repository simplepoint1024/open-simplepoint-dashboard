package org.simplepoint.plugin.oidc.service;

import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import tools.jackson.databind.json.JsonMapper;

/**
 * Utility class for parsing and serializing JSON data
 * related to OAuth2 authorization using Jackson.
 *
 * <p>This class provides methods to parse JSON strings into Map objects
 * and to serialize Map objects into JSON strings. It leverages
 * Spring Security's SecurityJacksonModules to handle OAuth2-specific
 * serialization and deserialization.</p>
 */
public class SecurityJacksonParse {
  private static final JsonMapper jsonMapper = JsonMapper.builder()
      .addModules(
          SecurityJacksonModules.getModules(OAuth2Authorization.class.getClassLoader())
      )
      .build();

  /**
   * Parses a JSON string into a Map representation.
   * This method is used for converting stored JSON data
   * (e.g., OAuth2 client settings or token configurations)
   * back into a Map format for application use.
   *
   * @param data the JSON string to parse
   * @return a Map representation of the parsed data
   * @throws IllegalArgumentException if parsing fails
   */
  public static Map<String, Object> parseMap(String data) {
    final ParameterizedTypeReference<Map<String, Object>> typeReference = new ParameterizedTypeReference<>() {
    };
    var javaType = SecurityJacksonParse.jsonMapper.getTypeFactory()
        .constructType(typeReference.getType());
    return SecurityJacksonParse.jsonMapper.readValue(data, javaType);
  }

  /**
   * Serializes a Map into a JSON string representation.
   * This method is used for converting OAuth2 client settings or token configurations
   * into a JSON format that can be stored in a database.
   *
   * @param data the map containing the data to serialize
   * @return a JSON string representation of the map
   * @throws IllegalArgumentException if serialization fails
   */
  public static String writeMap(Map<String, Object> data) {
    try {
      String s = SecurityJacksonParse.jsonMapper.writeValueAsString(data);
      System.out.println(s);
      return s;
    } catch (Exception ex) {
      throw new IllegalArgumentException(ex.getMessage(), ex);
    }
  }
}

package org.simplepoint.events.composition;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** External credentials and optional explicit storage bindings; manifests contain neither. */
@Getter
@Setter
@ConfigurationProperties("simplepoint.events")
public class ModuleEventProperties {
  private Map<String, Binding> modules = new LinkedHashMap<>();

  /** A module uses the unique/default business storage unless explicit bean names are supplied. */
  @Getter
  @Setter
  public static class Binding {
    private String password;
    private String dataSource;
    private String transactionManager;
  }
}

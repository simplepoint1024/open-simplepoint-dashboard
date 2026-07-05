package org.simplepoint.plugin.rbac.resource.service.support;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

/**
 * Indexes entity button declarations so action resources can inherit their UI metadata.
 */
@Slf4j
@Service
public class ButtonResourceMetadataRegistry {

  private static final String BASE_PACKAGE = "org.simplepoint";

  private final Map<String, ButtonResourceMetadata> metadataByAuthority = new ConcurrentHashMap<>();

  /**
   * Scans the application classpath for {@link ButtonDeclarations}.
   */
  @PostConstruct
  public void initialize() {
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(ButtonDeclarations.class));
    ClassLoader classLoader = ClassUtils.getDefaultClassLoader();

    for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
      String className = definition.getBeanClassName();
      if (className == null || className.isBlank()) {
        continue;
      }
      try {
        Class<?> type = ClassUtils.forName(className, classLoader);
        ButtonDeclarations declarations = AnnotationUtils.findAnnotation(type, ButtonDeclarations.class);
        if (declarations == null || declarations.value().length == 0) {
          continue;
        }
        for (ButtonDeclaration declaration : declarations.value()) {
          register(declaration);
        }
      } catch (LinkageError | ClassNotFoundException ex) {
        log.debug("Skipping button declaration scan for {}", className, ex);
      }
    }

    log.info("Indexed button declaration metadata: {}", metadataByAuthority.size());
  }

  /**
   * Returns button metadata by authority.
   */
  public ButtonResourceMetadata find(String authority) {
    if (authority == null || authority.isBlank()) {
      return null;
    }
    return metadataByAuthority.get(authority.trim());
  }

  /**
   * Exposes an immutable snapshot for diagnostics and tests.
   */
  public Map<String, ButtonResourceMetadata> snapshot() {
    return Collections.unmodifiableMap(metadataByAuthority);
  }

  private void register(ButtonDeclaration declaration) {
    String authority = trimToNull(declaration.authority());
    if (authority == null) {
      return;
    }
    ButtonResourceMetadata metadata = new ButtonResourceMetadata(
        authority,
        trimToNull(declaration.title()),
        trimToNull(declaration.icon()),
        declaration.danger(),
        declaration.sort()
    );
    metadataByAuthority.putIfAbsent(authority, metadata);
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  /**
   * UI metadata declared by a button.
   */
  public record ButtonResourceMetadata(
      String authority,
      String title,
      String icon,
      Boolean danger,
      Integer sort
  ) {
  }
}

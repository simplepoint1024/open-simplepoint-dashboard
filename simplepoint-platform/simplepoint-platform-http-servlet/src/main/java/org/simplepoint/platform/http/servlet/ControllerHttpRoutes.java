package org.simplepoint.platform.http.servlet;

import java.io.IOException;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleDescriptor;
import org.simplepoint.platform.composition.ModuleHttpRoutes;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/** Reads selected controllers without evaluating conditions or constructing application beans. */
public final class ControllerHttpRoutes implements ModuleHttpRoutes {
  /** Public metadata-only adapter used by the pure composition planner. */
  public ControllerHttpRoutes() {}

  @Override
  public Set<String> roots(ModuleDescriptor module) {
    ClassLoader loader = module.configuration().getClassLoader();
    var resolver = new PathMatchingResourcePatternResolver(loader);
    var metadata = new CachingMetadataReaderFactory(resolver);
    var filter = new AnnotationTypeFilter(Controller.class);
    var sources = new TreeMap<String, String>();
    var roots = new TreeSet<String>();
    int inspected = 0;
    try {
      for (String packageName : module.packages()) {
        var resources =
            resolver.getResources("classpath*:" + packageName.replace('.', '/') + "/**/*.class");
        for (var resource : resources) {
          if (++inspected > 10_000) {
            throw new ModuleCompositionException("MODULE_HTTP_SCAN_LIMIT", module.id());
          }
          var reader = metadata.getMetadataReader(resource);
          var type = reader.getClassMetadata();
          if (!type.isIndependent()
              || type.isInterface()
              || type.isAbstract()
              || !filter.match(reader, metadata)) {
            continue;
          }
          String source = resource.getURL().toExternalForm();
          String previous = sources.putIfAbsent(type.getClassName(), source);
          if (previous != null && !previous.equals(source)) {
            throw new ModuleCompositionException("DUPLICATE_HTTP_CONTROLLER", type.getClassName());
          }
        }
      }
      for (String name : sources.keySet()) {
        Class<?> type = Class.forName(name, false, loader);
        if (type.getClassLoader() != loader) {
          throw new ModuleCompositionException("MODULE_HTTP_CONTROLLER_LOADER_MISMATCH", name);
        }
        var mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        if (mapping == null || mapping.path().length == 0) {
          // Protocol metadata controllers may declare complete paths only on their methods.
          // Read the actual inherited/composed mappings; never reserve the whole host.
          boolean found = false;
          for (var method : org.springframework.util.ReflectionUtils.getAllDeclaredMethods(type)) {
            var operation =
                AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (operation == null) {
              continue;
            }
            if (operation.path().length == 0) {
              throw new ModuleCompositionException("MISSING_CONTROLLER_HTTP_ROOT", name);
            }
            for (String path : operation.path()) {
              addRoot(roots, path, name);
              found = true;
            }
          }
          if (!found) {
            throw new ModuleCompositionException("MISSING_CONTROLLER_HTTP_ROOT", name);
          }
        } else {
          for (String path : mapping.path()) {
            addRoot(roots, path, name);
          }
        }
      }
      return Set.copyOf(roots);
    } catch (IOException | ClassNotFoundException | LinkageError failure) {
      throw new ModuleCompositionException("MODULE_HTTP_ROUTES_FAILED", module.id());
    }
  }

  private static void addRoot(Set<String> roots, String path, String controller) {
    String root = HttpRoutePaths.literalRoot(path);
    if (root.equals("/")) {
      throw new ModuleCompositionException("MISSING_CONTROLLER_HTTP_ROOT", controller);
    }
    roots.add(root);
  }
}

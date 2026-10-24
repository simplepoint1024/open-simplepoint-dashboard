package org.simplepoint.mcp.gateway.module;

import java.util.Set;
import java.util.TreeSet;
import org.simplepoint.mcp.gateway.publication.McpPublicationDispatcherServlet;
import org.simplepoint.platform.composition.ModuleDescriptor;
import org.simplepoint.platform.composition.ModuleHttpRoutes;
import org.simplepoint.platform.http.servlet.ControllerHttpRoutes;

/** Metadata from the actual MVC declarations and the native servlet's single path constant. */
public final class McpGatewayHttpRoutes implements ModuleHttpRoutes {
  /** Creates the metadata reader without starting gateway components. */
  public McpGatewayHttpRoutes() {}

  @Override
  public Set<String> roots(ModuleDescriptor module) {
    var roots = new TreeSet<>(new ControllerHttpRoutes().roots(module));
    roots.add(McpPublicationDispatcherServlet.PATH);
    return Set.copyOf(roots);
  }
}

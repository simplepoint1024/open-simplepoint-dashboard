package org.simplepoint.data.migration;

import java.io.Reader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;

/** Exact in-memory resources prevent a migration scan from crossing into another module. */
final class OwnedMigrationResources implements ResourceProvider {
  private final List<LoadableResource> resources;

  OwnedMigrationResources(String module, List<ModuleMigrationPlan.Script> scripts) {
    resources =
        scripts.stream().map(script -> (LoadableResource) new SqlResource(module, script)).toList();
  }

  @Override
  public LoadableResource getResource(String name) {
    return resources.stream()
        .filter(
            resource ->
                resource.getRelativePath().equals(name) || resource.getFilename().equals(name))
        .findFirst()
        .orElse(null);
  }

  @Override
  public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
    return resources.stream()
        .filter(
            resource ->
                resource.getFilename().startsWith(prefix)
                    && Arrays.stream(suffixes)
                        .anyMatch(suffix -> resource.getFilename().endsWith(suffix)))
        .toList();
  }

  private static final class SqlResource extends LoadableResource {
    private final String module;
    private final ModuleMigrationPlan.Script script;

    private SqlResource(String module, ModuleMigrationPlan.Script script) {
      this.module = module;
      this.script = script;
    }

    @Override
    public Reader read() {
      return new StringReader(script.sql());
    }

    @Override
    public String getAbsolutePath() {
      return "module:" + module + "/" + script.name();
    }

    @Override
    public String getAbsolutePathOnDisk() {
      return null;
    }

    @Override
    public String getFilename() {
      return script.name();
    }

    @Override
    public String getRelativePath() {
      return script.name();
    }
  }
}

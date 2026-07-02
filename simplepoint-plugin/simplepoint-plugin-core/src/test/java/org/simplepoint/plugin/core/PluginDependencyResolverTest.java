package org.simplepoint.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.PluginArtifact;
import org.simplepoint.plugin.api.manifest.PluginManifest;

class PluginDependencyResolverTest {

  private final PluginDependencyResolver resolver = new PluginDependencyResolver();

  @Test
  void sort_ordersRequiredDependenciesBeforeDependents() {
    PluginDescriptor child = descriptor("child", dependency("parent", false));
    PluginDescriptor parent = descriptor("parent");

    List<PluginDescriptor> sorted = resolver.sort(List.of(child, parent), List.of());

    assertThat(sorted).extracting(PluginDescriptor::id).containsExactly("parent", "child");
  }

  @Test
  void sort_allowsDependencyThatIsAlreadyInstalled() {
    PluginDescriptor child = descriptor("child", dependency("parent", false));

    List<PluginDescriptor> sorted = resolver.sort(List.of(child), List.of("parent"));

    assertThat(sorted).extracting(PluginDescriptor::id).containsExactly("child");
  }

  @Test
  void sort_rejectsCandidateThatIsAlreadyInstalled() {
    PluginDescriptor plugin = descriptor("plugin");

    assertThatThrownBy(() -> resolver.sort(List.of(plugin), List.of("plugin")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Plugin plugin is already installed");
  }

  @Test
  void sort_rejectsMissingRequiredDependency() {
    PluginDescriptor child = descriptor("child", dependency("missing", false));

    assertThatThrownBy(() -> resolver.sort(List.of(child), List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires missing dependency missing");
  }

  @Test
  void sort_rejectsCircularDependency() {
    PluginDescriptor left = descriptor("left", dependency("right", false));
    PluginDescriptor right = descriptor("right", dependency("left", false));

    assertThatThrownBy(() -> resolver.sort(List.of(left, right), List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Circular plugin dependency detected");
  }

  @Test
  void sort_ordersOptionalDependencyWhenCandidateExists() {
    PluginDescriptor child = descriptor("child", dependency("parent", true));
    PluginDescriptor parent = descriptor("parent");

    List<PluginDescriptor> sorted = resolver.sort(List.of(child, parent), List.of());

    assertThat(sorted).extracting(PluginDescriptor::id).containsExactly("parent", "child");
  }

  @Test
  void sort_ignoresMissingOptionalDependency() {
    PluginDescriptor child = descriptor("child", dependency("missing", true));

    List<PluginDescriptor> sorted = resolver.sort(List.of(child), List.of());

    assertThat(sorted).extracting(PluginDescriptor::id).containsExactly("child");
  }

  @Test
  void sort_allowsCompatibleCandidateDependencyVersion() {
    PluginDescriptor child = descriptor("child", dependency("parent", ">=1.0.0 <2.0.0", false));
    PluginDescriptor parent = descriptor("parent", "1.5.0");

    List<PluginDescriptor> sorted = resolver.sort(List.of(child, parent), List.of());

    assertThat(sorted).extracting(PluginDescriptor::id).containsExactly("parent", "child");
  }

  @Test
  void sort_rejectsCandidateDependencyVersionMismatch() {
    PluginDescriptor child = descriptor("child", dependency("parent", ">=2.0.0", false));
    PluginDescriptor parent = descriptor("parent", "1.5.0");

    assertThatThrownBy(() -> resolver.sort(List.of(child, parent), List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Plugin child requires dependency parent version >=2.0.0, but resolved version is 1.5.0");
  }

  @Test
  void sort_allowsCompatibleInstalledDependencyVersion() {
    PluginDescriptor child = descriptor("child", dependency("parent", "^1.2.0", false));

    List<PluginDescriptor> sorted = resolver.sort(List.of(child), Map.of("parent", "1.8.0"));

    assertThat(sorted).extracting(PluginDescriptor::id).containsExactly("child");
  }

  @Test
  void sort_rejectsInstalledDependencyVersionMismatch() {
    PluginDescriptor child = descriptor("child", dependency("parent", "~1.2.0", false));

    assertThatThrownBy(() -> resolver.sort(List.of(child), Map.of("parent", "1.3.0")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Plugin child requires dependency parent version ~1.2.0, but resolved version is 1.3.0");
  }

  @Test
  void sort_ignoresMissingOptionalDependencyVersionRequirement() {
    PluginDescriptor child = descriptor("child", dependency("optional-parent", ">=2.0.0", true));

    List<PluginDescriptor> sorted = resolver.sort(List.of(child), Map.of());

    assertThat(sorted).extracting(PluginDescriptor::id).containsExactly("child");
  }

  @Test
  void sort_rejectsPresentOptionalDependencyVersionMismatch() {
    PluginDescriptor child = descriptor("child", dependency("optional-parent", ">=2.0.0", true));
    PluginDescriptor optionalParent = descriptor("optional-parent", "1.9.0");

    assertThatThrownBy(() -> resolver.sort(List.of(child, optionalParent), List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Plugin child requires dependency optional-parent version >=2.0.0, but resolved version is 1.9.0");
  }

  private static PluginDescriptor descriptor(String id, PluginManifest.PluginDependency... dependencies) {
    return descriptor(id, "1.0.0", dependencies);
  }

  private static PluginDescriptor descriptor(
      String id,
      String version,
      PluginManifest.PluginDependency... dependencies
  ) {
    PluginManifest manifest = new PluginManifest();
    manifest.setId(id);
    manifest.setName(id);
    manifest.setVersion(version);
    manifest.setDependencies(List.of(dependencies));
    URI uri = URI.create("file:/plugins/" + id + ".jar");
    return PluginDescriptor.from(PluginArtifact.unknown(uri), manifest);
  }

  private static PluginManifest.PluginDependency dependency(String id, boolean optional) {
    return dependency(id, null, optional);
  }

  private static PluginManifest.PluginDependency dependency(String id, String version, boolean optional) {
    PluginManifest.PluginDependency dependency = new PluginManifest.PluginDependency();
    dependency.setId(id);
    dependency.setVersion(version);
    dependency.setOptional(optional);
    return dependency;
  }
}

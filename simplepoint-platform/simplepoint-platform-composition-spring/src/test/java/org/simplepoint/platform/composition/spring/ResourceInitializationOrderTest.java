package org.simplepoint.platform.composition.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleInitializationStage;

class ResourceInitializationOrderTest {
  @Test
  void reverseDiscoveryStillRegistersSharedParentsFirstAfterBootstrap() {
    var bootstrap =
        new ModuleInitializationRunner.PreparedTask(
            "base", false, ModuleInitializationStage.BOOTSTRAP, 0, () -> {});
    var child = task("child");
    var unrelated = task("unrelated");
    var parent = task("parent");
    var ordered =
        ModuleInitializationRunner.orderResources(
            List.of(bootstrap, child, unrelated, parent),
            Map.of("directory", "parent"),
            Map.of("child", Map.of("directory", "directory-owner")),
            Map.of("parent", "directory-owner", "child", "child-owner"));
    assertThat(ordered).containsExactly(bootstrap, unrelated, parent, child);
  }

  @Test
  void wrongOwnerAndCyclesFailBeforeAnyActionRuns() {
    var tasks = List.of(task("a"), task("b"));
    var codes = Map.of("a.directory", "a", "b.directory", "b");
    var owners = Map.of("a", "owner-a", "b", "owner-b");
    assertThatThrownBy(
            () ->
                ModuleInitializationRunner.orderResources(
                    tasks, codes, Map.of("a", Map.of("b.directory", "wrong")), owners))
        .isInstanceOfSatisfying(
            ModuleCompositionException.class,
            error -> assertThat(error.code()).isEqualTo("RESOURCE_PARENT_OWNER_CONFLICT"));
    assertThatThrownBy(
            () ->
                ModuleInitializationRunner.orderResources(
                    tasks,
                    codes,
                    Map.of(
                        "a",
                        Map.of("b.directory", "owner-b"),
                        "b",
                        Map.of("a.directory", "owner-a")),
                    owners))
        .isInstanceOfSatisfying(
            ModuleCompositionException.class,
            error -> assertThat(error.code()).isEqualTo("CYCLIC_RESOURCE_PARENTS"));
  }

  @Test
  void missingDirectoryOfSelectedOwnerFailsBeforeRemoteEffects() {
    assertThatThrownBy(
            () ->
                ModuleInitializationRunner.orderResources(
                    List.of(task("child"), task("parent")),
                    Map.of(),
                    Map.of("child", Map.of("missing.directory", "directory-owner")),
                    Map.of("parent", "directory-owner", "child", "child-owner")))
        .isInstanceOfSatisfying(
            ModuleCompositionException.class,
            error -> assertThat(error.code()).isEqualTo("RESOURCE_PARENT_MISSING"));
  }

  @Test
  void remoteParentsRemainForAuthoritativeServerValidation() {
    var child = task("child");
    assertThat(
            ModuleInitializationRunner.orderResources(
                List.of(child),
                Map.of(),
                Map.of("child", Map.of("remote.directory", "remote-owner")),
                Map.of("child", "child-owner")))
        .containsExactly(child);
  }

  private static ModuleInitializationRunner.PreparedTask task(String module) {
    return new ModuleInitializationRunner.PreparedTask(
        module,
        true,
        ModuleInitializationStage.REGISTRATION,
        0,
        () -> {
          throw new AssertionError("Planning must not execute startup effects");
        });
  }
}

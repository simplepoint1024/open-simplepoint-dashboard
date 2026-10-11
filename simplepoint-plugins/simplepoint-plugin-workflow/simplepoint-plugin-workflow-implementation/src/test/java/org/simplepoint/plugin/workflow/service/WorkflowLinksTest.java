package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkflowLinksTest {
  @Test
  void linksOpenPersonalEntriesWithOpaqueIdentifiersAndCopiedTab() {
    assertThat(WorkflowLinks.task("process/1", "task&view=other"))
        .isEqualTo("/my/todos?task=task%26view%3Dother&instance=process%2F1");
    assertThat(WorkflowLinks.matter("process/1")).isEqualTo("/my/items?instance=process%2F1");
    assertThat(WorkflowLinks.copied("process/1"))
        .isEqualTo("/my/items?instance=process%2F1&view=copied");
  }
}

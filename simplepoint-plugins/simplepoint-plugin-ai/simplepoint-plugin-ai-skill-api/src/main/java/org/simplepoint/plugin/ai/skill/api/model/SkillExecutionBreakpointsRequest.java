package org.simplepoint.plugin.ai.skill.api.model;

import java.util.List;

/** Safe pre-step breakpoints for one Draft debug execution. */
public record SkillExecutionBreakpointsRequest(List<String> stepIds) {

  /** Defensively owns the requested breakpoint list. */
  public SkillExecutionBreakpointsRequest {
    stepIds = stepIds == null ? List.of() : List.copyOf(stepIds);
  }
}

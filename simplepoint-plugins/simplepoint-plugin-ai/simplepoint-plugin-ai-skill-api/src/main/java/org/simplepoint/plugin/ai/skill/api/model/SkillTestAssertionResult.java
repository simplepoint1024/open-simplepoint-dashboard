package org.simplepoint.plugin.ai.skill.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Durable result of one assertion from a pinned Skill Designer test case.
 *
 * @param id assertion identifier from the immutable Draft Revision
 * @param sourceNodeId source workflow node or {@code __output}
 * @param fieldPath dot-separated path evaluated against the source value
 * @param operator configured assertion operator
 * @param passed whether the assertion matched
 * @param expected configured expected value
 * @param actual resolved actual value, or null when the path does not exist
 * @param message stable human-readable outcome
 */
@Schema(title = "Skill Test Assertion Result")
public record SkillTestAssertionResult(
    String id,
    String sourceNodeId,
    String fieldPath,
    String operator,
    boolean passed,
    Object expected,
    Object actual,
    String message
) {
}

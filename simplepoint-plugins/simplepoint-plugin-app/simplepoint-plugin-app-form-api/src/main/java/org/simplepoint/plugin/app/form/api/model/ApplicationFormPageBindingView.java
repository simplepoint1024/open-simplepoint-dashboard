package org.simplepoint.plugin.app.form.api.model;

import java.time.Instant;

/** Published or recoverable page binding without infrastructure credentials. */
public record ApplicationFormPageBindingView(
    String id,
    String applicationId,
    String pageId,
    String formId,
    int formVersion,
    String sceneId,
    ApplicationFormPageBindingStatus status,
    String resourceId,
    String resourceCode,
    String runtimePath,
    String errorCode,
    int attempt,
    Instant activatedAt,
    Instant createdAt,
    Instant updatedAt
) {
}

package org.simplepoint.plugin.app.form.api.model;

/** One main, dialog or drawer rendering scene in a form version. */
public record FormSceneDefinition(
    String sceneId,
    String code,
    String name,
    FormSceneType type,
    Integer width,
    FormComponentDefinition root
) {
}

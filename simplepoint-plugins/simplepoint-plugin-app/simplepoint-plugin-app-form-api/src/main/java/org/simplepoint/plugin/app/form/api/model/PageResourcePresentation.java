package org.simplepoint.plugin.app.form.api.model;

import org.simplepoint.api.locale.LocalizedText;

/** Captured publication metadata; a stale navigation revision must fail at the resource owner. */
public record PageResourcePresentation(
    LocalizedText name,
    LocalizedText groupName,
    String navigationRootKey,
    Long navigationRevision,
    String parentManagedKey) {}

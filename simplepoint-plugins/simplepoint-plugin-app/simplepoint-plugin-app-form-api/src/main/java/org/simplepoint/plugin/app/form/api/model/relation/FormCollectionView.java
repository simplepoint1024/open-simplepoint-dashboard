package org.simplepoint.plugin.app.form.api.model.relation;

import org.simplepoint.api.locale.LocalizedText;

/** An authorized section under a saved business record. */
public record FormCollectionView(
    String id,
    boolean inverse,
    String label,
    LocalizedText labelLocalization,
    boolean canLink,
    boolean canUnlink) {}

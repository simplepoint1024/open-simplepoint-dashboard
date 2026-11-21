package org.simplepoint.plugin.app.form.api.model.relation;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.simplepoint.api.locale.LocalizedText;

/** Authorized inverse navigation label; source model and field metadata are not exposed. */
public record FormInverseViewSummary(
    String viewId,
    String label,
    @JsonInclude(JsonInclude.Include.NON_NULL) LocalizedText labelLocalization) {}

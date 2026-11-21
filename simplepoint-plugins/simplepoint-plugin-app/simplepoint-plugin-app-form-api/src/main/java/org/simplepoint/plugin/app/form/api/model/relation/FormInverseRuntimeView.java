package org.simplepoint.plugin.app.form.api.model.relation;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeView;

/** Source form descriptor for the shared editor, narrowed by the inverse view's action ceiling. */
public record FormInverseRuntimeView(
    ApplicationFormRuntimeView source,
    String fieldId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String detailId,
    boolean canUnlink) {}

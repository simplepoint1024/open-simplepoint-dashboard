package org.simplepoint.plugin.app.form.api.model.relation;

import org.simplepoint.api.locale.LocalizedText;

/** Published, authorized business entry. Names are presentation, never relationship identities. */
public record FormRelationTargetOption(
    FormRelationTarget target,
    String pageId,
    String applicationName,
    String pageName,
    String formName,
    LocalizedText applicationNameLocalization,
    LocalizedText pageNameLocalization) {}

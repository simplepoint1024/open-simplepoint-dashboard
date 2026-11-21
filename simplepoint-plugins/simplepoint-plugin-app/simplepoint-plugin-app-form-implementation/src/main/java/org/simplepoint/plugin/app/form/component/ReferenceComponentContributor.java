package org.simplepoint.plugin.app.form.component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.model.FormComponentDescriptor;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.service.FormComponentContributor;

/** Presentation of a field-owned business relationship; no target identity belongs in component properties. */
public final class ReferenceComponentContributor implements FormComponentContributor {
  public static final String TYPE = "field.reference";

  @Override
  public Collection<FormComponentDescriptor> descriptors() {
    return List.of(new FormComponentDescriptor(TYPE, "1", false, true,
        Set.of(FormFieldType.UUID), Map.of(), Set.of(), Set.of()));
  }
}

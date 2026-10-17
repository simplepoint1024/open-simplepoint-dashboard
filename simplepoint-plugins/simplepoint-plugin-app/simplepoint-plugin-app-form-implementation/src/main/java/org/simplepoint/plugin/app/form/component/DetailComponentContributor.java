package org.simplepoint.plugin.app.form.component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.model.FormComponentDescriptor;
import org.simplepoint.plugin.app.form.api.model.FormComponentPropertyDescriptor;
import org.simplepoint.plugin.app.form.api.model.FormValueKind;
import org.simplepoint.plugin.app.form.api.service.FormComponentContributor;

/** Presentation binding for an independently defined owned data collection. */
public final class DetailComponentContributor implements FormComponentContributor {
  @Override
  public Collection<FormComponentDescriptor> descriptors() {
    return List.of(new FormComponentDescriptor("data.detail", "1", false, false, Set.of(),
        Map.of("detailId", new FormComponentPropertyDescriptor("detailId", Set.of(FormValueKind.STRING), true, null)),
        Set.of(), Set.of()));
  }
}

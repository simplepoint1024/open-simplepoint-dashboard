package org.simplepoint.plugin.app.form.api.model;

import java.util.List;

/** Stable field identities defining business list defaults, independent from scene layout. */
public record FormPresentation(List<String> listFieldOrder, Integer labelWidth) {
  public FormPresentation {
    listFieldOrder = listFieldOrder == null ? List.of() : List.copyOf(listFieldOrder);
  }
}

package org.simplepoint.plugin.app.form.service;

import java.util.HashSet;
import java.util.Set;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;

/** Shared publication/runtime memory budget for distinct active immutable definitions. */
final class FormActiveDefinitionBudget {
  private static final int MAX_CHARACTERS = 4 * 1024 * 1024;
  private final Set<Integer> versions = new HashSet<>();
  private int characters;

  void include(final int version, final String document) {
    if (versions.add(version)) {
      if (document == null || document.length() > MAX_CHARACTERS - characters) {
        throw new ApplicationFormException(ApplicationFormErrorCode.DOCUMENT_INVALID,
            "Active form definitions exceed the shared publication and runtime budget");
      }
      characters += document.length();
    }
  }
}

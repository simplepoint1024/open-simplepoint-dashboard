package org.simplepoint.plugin.app.form.api.model.relation;

import java.util.regex.Pattern;

/** Shared validation for portable stable identities; display labels and storage names are not IDs. */
final class FormRelationIdentity {
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

  private FormRelationIdentity() {
  }

  static String require(String value) {
    if (value == null || !ID.matcher(value).matches()) {
      throw new IllegalArgumentException("Relation identity is invalid");
    }
    return value;
  }
}

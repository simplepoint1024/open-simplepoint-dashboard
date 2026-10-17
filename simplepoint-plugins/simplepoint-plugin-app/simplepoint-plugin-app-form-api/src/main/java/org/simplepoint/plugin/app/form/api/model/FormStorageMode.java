package org.simplepoint.plugin.app.form.api.model;

/** How a logical form field participates in persistence. */
public enum FormStorageMode {
  STORED,
  COMPUTED,
  REFERENCE,
  ATTACHMENT,
  TRANSIENT
}

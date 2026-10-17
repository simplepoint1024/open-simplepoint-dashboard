package org.simplepoint.plugin.app.form.api.model;

/** Business write boundary evaluated against the persisted record state, independent of UI and permissions. */
public enum FormFieldWritePolicy {
  EDITABLE,
  CREATE_ONLY,
  UNTIL_SUBMITTED;

  /** A missing current state denotes creation; the requested target state never grants write access. */
  public boolean allows(final FormRecordState currentState) {
    return switch (this) {
      case EDITABLE -> true;
      case CREATE_ONLY -> currentState == null;
      case UNTIL_SUBMITTED -> currentState != FormRecordState.SUBMITTED;
    };
  }
}

package org.simplepoint.plugin.app.form.api.model;

/** Form operation policy. This intersects authorization and lifecycle restrictions. */
public record FormFieldAccess(boolean readOnly, CreateMode create, EditMode edit) {
  /** Whether the field participates in a new record's editable values. */
  public enum CreateMode {
    WRITE,
    HIDDEN
  }

  /** Visibility and writability while editing an existing record. */
  public enum EditMode {
    WRITE,
    READ,
    HIDDEN
  }

  /** Missing operation policies retain the legacy editable behavior. */
  public FormFieldAccess {
    create = create == null ? CreateMode.WRITE : create;
    edit = edit == null ? EditMode.WRITE : edit;
  }

  /** Intersects the form-level read-only flag with the current operation. */
  public boolean allows(FormRecordState state) {
    return !readOnly && (state == null ? create == CreateMode.WRITE : edit == EditMode.WRITE);
  }

  /** Keeps the stricter visibility and write policy across immutable page versions. */
  public static FormFieldAccess intersect(FormFieldAccess left, FormFieldAccess right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return new FormFieldAccess(
        left.readOnly || right.readOnly,
        left.create == CreateMode.HIDDEN || right.create == CreateMode.HIDDEN
            ? CreateMode.HIDDEN
            : CreateMode.WRITE,
        left.edit == EditMode.HIDDEN || right.edit == EditMode.HIDDEN
            ? EditMode.HIDDEN
            : left.edit == EditMode.READ || right.edit == EditMode.READ
                ? EditMode.READ
                : EditMode.WRITE);
  }
}

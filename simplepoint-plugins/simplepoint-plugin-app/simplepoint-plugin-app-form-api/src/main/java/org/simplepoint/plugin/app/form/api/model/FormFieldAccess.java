package org.simplepoint.plugin.app.form.api.model;

/** Form operation policy. This intersects authorization and lifecycle restrictions. */
public record FormFieldAccess(boolean readOnly, CreateMode create, EditMode edit) {
  public enum CreateMode { WRITE, HIDDEN }
  public enum EditMode { WRITE, READ, HIDDEN }

  public FormFieldAccess {
    create = create == null ? CreateMode.WRITE : create;
    edit = edit == null ? EditMode.WRITE : edit;
  }

  public boolean allows(FormRecordState state) {
    return !readOnly && (state == null ? create == CreateMode.WRITE : edit == EditMode.WRITE);
  }

  public static FormFieldAccess intersect(FormFieldAccess left, FormFieldAccess right) {
    if (left == null) return right;
    if (right == null) return left;
    return new FormFieldAccess(left.readOnly || right.readOnly,
        left.create == CreateMode.HIDDEN || right.create == CreateMode.HIDDEN ? CreateMode.HIDDEN : CreateMode.WRITE,
        left.edit == EditMode.HIDDEN || right.edit == EditMode.HIDDEN ? EditMode.HIDDEN
            : left.edit == EditMode.READ || right.edit == EditMode.READ ? EditMode.READ : EditMode.WRITE);
  }
}

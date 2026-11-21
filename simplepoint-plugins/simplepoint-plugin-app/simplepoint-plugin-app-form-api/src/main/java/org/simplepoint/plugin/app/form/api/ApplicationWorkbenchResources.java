package org.simplepoint.plugin.app.form.api;

/** Stable resource codes shared by workbench capabilities and the HTTP authorization boundary. */
public final class ApplicationWorkbenchResources {
  public static final String VIEW = "app.workbench.view";
  public static final String CREATE_APPLICATION = "app.workbench.apps.create";
  public static final String CREATE_PAGE = "app.workbench.pages.create";
  public static final String CREATE_FORM = "app.workbench.forms.create";
  public static final String EDIT_FORM = "app.workbench.forms.edit";
  public static final String PUBLISH_FORM = "app.workbench.forms.publish";
  public static final String DELETE_APPLICATION = "app.workbench.apps.delete";
  public static final String DELETE_PAGE = "app.workbench.pages.delete";
  public static final String DELETE_FORM = "app.workbench.forms.delete";

  public static final String CAN_VIEW = "hasAuthority('" + VIEW + "')";
  public static final String CAN_CREATE_APPLICATION = "hasAuthority('" + CREATE_APPLICATION + "')";
  public static final String CAN_CREATE_PAGE = "hasAuthority('" + CREATE_PAGE + "')";
  public static final String CAN_CREATE_FORM = "hasAuthority('" + CREATE_FORM + "')";
  public static final String CAN_EDIT_FORM = "hasAuthority('" + EDIT_FORM + "')";
  public static final String CAN_PUBLISH_FORM = "hasAuthority('" + PUBLISH_FORM + "')";
  public static final String CAN_DELETE_APPLICATION =
      CAN_VIEW + " and hasAuthority('" + DELETE_APPLICATION + "')";
  public static final String CAN_DELETE_PAGE =
      CAN_VIEW + " and hasAuthority('" + DELETE_PAGE + "')";
  public static final String CAN_DELETE_FORM =
      CAN_VIEW + " and hasAuthority('" + DELETE_FORM + "')";

  private ApplicationWorkbenchResources() {}
}

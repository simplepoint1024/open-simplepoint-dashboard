package org.simplepoint.plugin.personal.api;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.springframework.core.annotation.Order;

/** Platform UI projections, deliberately excluding ownership, delivery keys and version fields. */
public final class PersonalTableModels {
  private PersonalTableModels() {}

  /** Editable checklist fields; stable IDs are assigned/preserved by the submit adapter. */
  public record Checklist(
      @Schema(
              title = "i18n:personal.subtaskTitle",
              minLength = 1,
              maxLength = 200,
              requiredMode = Schema.RequiredMode.REQUIRED)
          String title,
      @Schema(title = "i18n:personal.completeSubtask") boolean done) {}

  /** Item table metadata and platform action declarations. */
  @ButtonDeclarations({
    @ButtonDeclaration(
        key = "add",
        title = "i18n:personal.createItem",
        icon = "PlusCircleOutlined",
        color = "blue",
        sort = 0,
        argumentMinSize = 0,
        argumentMaxSize = 0,
        danger = false,
        authority = "personal.items.add"),
    @ButtonDeclaration(
        key = "edit",
        title = "i18n:personal.editItem",
        icon = "EditOutlined",
        color = "orange",
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.items.edit"),
    @ButtonDeclaration(
        key = "view",
        title = "i18n:personal.itemDetails",
        icon = "EyeOutlined",
        color = "blue",
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.items.detail"),
    @ButtonDeclaration(
        key = "delete",
        title = "i18n:personal.delete",
        icon = "MinusCircleOutlined",
        color = "danger",
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = true,
        authority = "personal.items.delete"),
    @ButtonDeclaration(
        key = "START",
        title = "i18n:personal.start",
        icon = "PlayCircleOutlined",
        color = "blue",
        sort = 4,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.items.START"),
    @ButtonDeclaration(
        key = "COMPLETE",
        title = "i18n:personal.complete",
        icon = "CheckCircleOutlined",
        color = "green",
        sort = 5,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.items.COMPLETE"),
    @ButtonDeclaration(
        key = "CANCEL",
        title = "i18n:personal.cancelItem",
        icon = "StopOutlined",
        color = "orange",
        sort = 6,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.items.CANCEL"),
    @ButtonDeclaration(
        key = "REOPEN",
        title = "i18n:personal.reopen",
        icon = "RedoOutlined",
        color = "blue",
        sort = 7,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.items.REOPEN"),
    @ButtonDeclaration(
        key = "ARCHIVE",
        title = "i18n:personal.archive",
        icon = "InboxOutlined",
        color = "blue",
        sort = 8,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.items.ARCHIVE"),
    @ButtonDeclaration(
        key = "UNARCHIVE",
        title = "i18n:personal.unarchive",
        icon = "ExportOutlined",
        color = "blue",
        sort = 9,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.items.UNARCHIVE"),
    @ButtonDeclaration(
        key = "RESTORE",
        title = "i18n:personal.restore",
        icon = "UndoOutlined",
        color = "green",
        sort = 10,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.items.RESTORE")
  })
  @org.simplepoint.core.query.TableQuery(
      model = org.simplepoint.plugin.personal.api.entity.PersonalItem.class)
  public record ItemTable(
      @Order(0)
          @Schema(
              title = "i18n:personal.title",
              minLength = 1,
              maxLength = 200,
              requiredMode = Schema.RequiredMode.REQUIRED,
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          String title,
      @Order(1)
          @Schema(
              title = "i18n:personal.description",
              maxLength = 8000,
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {
                        @ExtensionProperty(name = "x-list-visible", value = "false"),
                        @ExtensionProperty(name = "widget", value = "textarea")
                      }))
          String description,
      @Order(2)
          @Schema(
              title = "i18n:personal.category",
              maxLength = 60,
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          String category,
      @Order(3)
          @Schema(
              title = "i18n:personal.priority",
              minimum = "0",
              maximum = "3",
              defaultValue = "1",
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          int priority,
      @Order(4)
          @Schema(
              title = "i18n:personal.status",
              accessMode = Schema.AccessMode.READ_ONLY,
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          String status,
      @Order(5)
          @Schema(
              title = "i18n:personal.startAt",
              type = "string",
              format = "date-time",
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "false")}))
          Instant startAt,
      @Order(6)
          @Schema(
              title = "i18n:personal.dueAt",
              type = "string",
              format = "date-time",
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          Instant dueAt,
      @Order(7)
          @Schema(
              title = "i18n:personal.pinned",
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "false")}))
          boolean pinned,
      @Order(8)
          @Schema(
              title = "i18n:personal.checklist",
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          List<Checklist> checklist) {}

  /** Todo table metadata and platform action declarations. */
  @ButtonDeclarations({
    @ButtonDeclaration(
        key = "add",
        title = "i18n:personal.createTodo",
        icon = "PlusCircleOutlined",
        color = "blue",
        sort = 0,
        argumentMinSize = 0,
        argumentMaxSize = 0,
        danger = false,
        authority = "personal.todos.add"),
    @ButtonDeclaration(
        key = "view",
        title = "i18n:personal.todoDetails",
        icon = "EyeOutlined",
        color = "blue",
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.todos.detail"),
    @ButtonDeclaration(
        key = "COMPLETE",
        title = "i18n:personal.complete",
        icon = "CheckCircleOutlined",
        color = "green",
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.todos.COMPLETE"),
    @ButtonDeclaration(
        key = "CANCEL",
        title = "i18n:personal.cancelItem",
        icon = "StopOutlined",
        color = "orange",
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.todos.CANCEL"),
    @ButtonDeclaration(
        key = "REOPEN",
        title = "i18n:personal.reopen",
        icon = "RedoOutlined",
        color = "blue",
        sort = 4,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.todos.REOPEN")
  })
  @org.simplepoint.core.query.TableQuery(
      model = org.simplepoint.plugin.personal.api.entity.PersonalTodo.class)
  public record TodoTable(
      @Order(0)
          @Schema(
              title = "i18n:personal.title",
              minLength = 1,
              maxLength = 200,
              requiredMode = Schema.RequiredMode.REQUIRED,
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          String title,
      @Order(1)
          @Schema(
              title = "i18n:personal.description",
              maxLength = 8000,
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {
                        @ExtensionProperty(name = "x-list-visible", value = "false"),
                        @ExtensionProperty(name = "widget", value = "textarea")
                      }))
          String content,
      @Order(2)
          @Schema(
              title = "i18n:personal.priority",
              minimum = "0",
              maximum = "3",
              defaultValue = "1",
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          int priority,
      @Order(3)
          @Schema(
              title = "i18n:personal.source",
              accessMode = Schema.AccessMode.READ_ONLY,
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          String source,
      @Order(4)
          @Schema(
              title = "i18n:personal.dueAt",
              type = "string",
              format = "date-time",
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          Instant dueAt) {}

  /** Message table metadata and platform action declarations. */
  @ButtonDeclarations({
    @ButtonDeclaration(
        key = "view",
        title = "i18n:personal.messageDetails",
        icon = "EyeOutlined",
        color = "blue",
        sort = 0,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.messages.detail"),
    @ButtonDeclaration(
        key = "READ",
        title = "i18n:personal.read",
        icon = "MailOutlined",
        color = "blue",
        sort = 1,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.messages.READ"),
    @ButtonDeclaration(
        key = "UNREAD",
        title = "i18n:personal.unread",
        icon = "MailOutlined",
        color = "orange",
        sort = 2,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.messages.UNREAD"),
    @ButtonDeclaration(
        key = "ARCHIVE",
        title = "i18n:personal.archive",
        icon = "InboxOutlined",
        color = "blue",
        sort = 3,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.messages.ARCHIVE"),
    @ButtonDeclaration(
        key = "UNARCHIVE",
        title = "i18n:personal.unarchive",
        icon = "ExportOutlined",
        color = "blue",
        sort = 4,
        argumentMinSize = 1,
        argumentMaxSize = 1,
        danger = false,
        authority = "personal.messages.UNARCHIVE"),
    @ButtonDeclaration(
        key = "readAll",
        title = "i18n:personal.readAll",
        icon = "CheckCircleOutlined",
        color = "green",
        sort = 5,
        argumentMinSize = 0,
        argumentMaxSize = 0,
        danger = false,
        authority = "personal.messages.readAll")
  })
  @org.simplepoint.core.query.TableQuery(
      model = org.simplepoint.plugin.personal.api.entity.PersonalMessage.class)
  public record MessageTable(
      @Order(0)
          @Schema(
              title = "i18n:personal.title",
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          String title,
      @Order(1)
          @Schema(
              title = "i18n:personal.priority",
              minimum = "0",
              maximum = "3",
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          int priority,
      @Order(2)
          @Schema(
              title = "i18n:personal.source",
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          String source,
      @Order(3)
          @Schema(
              title = "i18n:personal.createdAt",
              type = "string",
              format = "date-time",
              extensions =
                  @Extension(
                      name = "x-ui",
                      properties = {@ExtensionProperty(name = "x-list-visible", value = "true")}))
          Instant createdAt) {}
}

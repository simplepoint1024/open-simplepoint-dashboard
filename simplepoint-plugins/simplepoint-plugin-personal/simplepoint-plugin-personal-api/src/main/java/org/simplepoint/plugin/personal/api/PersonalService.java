package org.simplepoint.plugin.personal.api;

import java.util.Map;
import org.simplepoint.plugin.personal.api.PersonalModels.ActionInput;
import org.simplepoint.plugin.personal.api.PersonalModels.EventView;
import org.simplepoint.plugin.personal.api.PersonalModels.ItemInput;
import org.simplepoint.plugin.personal.api.PersonalModels.ItemView;
import org.simplepoint.plugin.personal.api.PersonalModels.MessageView;
import org.simplepoint.plugin.personal.api.PersonalModels.Overview;
import org.simplepoint.plugin.personal.api.PersonalModels.TodoInput;
import org.simplepoint.plugin.personal.api.PersonalModels.TodoView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Authenticated user's personal workspace. Every method derives ownership from server context. */
public interface PersonalService {
  /** Returns a bounded, filtered page in the active, archived, overdue or trash view. */
  default Page<ItemView> items(
      String keyword,
      String status,
      String category,
      Integer priority,
      String view,
      Pageable page) {
    return items(keyword, status, category, priority, view, page, Map.of());
  }

  /** Applies platform header filters and pageable sorting within the current personal view. */
  Page<ItemView> items(
      String keyword,
      String status,
      String category,
      Integer priority,
      String view,
      Pageable page,
      Map<String, String> filters);

  /** Loads an owned non-deleted item or reports a non-disclosing not-found error. */
  ItemView item(String id);

  /** Creates an item and its first immutable activity entry atomically. */
  ItemView createItem(ItemInput input);

  /** Updates editable fields only when the supplied version is current. */
  ItemView updateItem(String id, ItemInput input);

  /** Applies a validated state transition or appends an immutable note. */
  ItemView itemAction(String id, ActionInput action);

  /** Lists activity for an owned item, including items in the trash. */
  Page<EventView> history(String id, Pageable page);

  /** Lists personal todos with optional status and title filtering. */
  default Page<TodoView> todos(String keyword, String status, Pageable page) {
    return todos(keyword, status, page, Map.of());
  }

  /** Applies platform header filters and sorting to personal todos. */
  Page<TodoView> todos(String keyword, String status, Pageable page, Map<String, String> filters);

  /** Loads one owned todo without exposing its integration delivery key. */
  TodoView todo(String id);

  /** Creates a manual todo in the current personal workspace. */
  TodoView createTodo(TodoInput input);

  /** Changes a manual todo; business-source outcomes cannot be changed here. */
  TodoView todoAction(String id, ActionInput action);

  /** Lists owned inbox or archived messages with an optional unread filter. */
  default Page<MessageView> messages(
      String keyword, boolean unreadOnly, boolean archived, Pageable page) {
    return messages(keyword, unreadOnly, archived, page, Map.of());
  }

  /** Applies platform header filters and sorting to personal messages. */
  Page<MessageView> messages(
      String keyword,
      boolean unreadOnly,
      boolean archived,
      Pageable page,
      Map<String, String> filters);

  /** Loads one owned message without marking it as read. */
  MessageView message(String id);

  /** Changes the read or archive state using optimistic version checks. */
  MessageView messageAction(String id, ActionInput action);

  /** Marks only the current workspace inbox read in one bulk update. */
  void readAllMessages();

  /** Returns bounded homepage summaries and counts without loading complete lists. */
  Overview overview();
}

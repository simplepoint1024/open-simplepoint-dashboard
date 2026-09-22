package org.simplepoint.plugin.personal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.plugin.personal.api.PersonalInboxGateway;
import org.simplepoint.plugin.personal.api.PersonalModels.ActionInput;
import org.simplepoint.plugin.personal.api.PersonalModels.ChecklistEntry;
import org.simplepoint.plugin.personal.api.PersonalModels.Delivery;
import org.simplepoint.plugin.personal.api.PersonalModels.DeliveryTarget;
import org.simplepoint.plugin.personal.api.PersonalModels.EventView;
import org.simplepoint.plugin.personal.api.PersonalModels.ItemInput;
import org.simplepoint.plugin.personal.api.PersonalModels.ItemView;
import org.simplepoint.plugin.personal.api.PersonalModels.MessageView;
import org.simplepoint.plugin.personal.api.PersonalModels.Overview;
import org.simplepoint.plugin.personal.api.PersonalModels.TodoInput;
import org.simplepoint.plugin.personal.api.PersonalModels.TodoView;
import org.simplepoint.plugin.personal.api.PersonalService;
import org.simplepoint.plugin.personal.api.entity.OwnedRecord;
import org.simplepoint.plugin.personal.api.entity.PersonalItem;
import org.simplepoint.plugin.personal.api.entity.PersonalItemEvent;
import org.simplepoint.plugin.personal.api.entity.PersonalMessage;
import org.simplepoint.plugin.personal.api.entity.PersonalTodo;
import org.simplepoint.plugin.personal.repository.PersonalRepository;
import org.simplepoint.plugin.personal.repository.PersonalRepository.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner-scoped personal work management; no administrator bypass for private content. */
@Service
@Transactional(readOnly = true)
public class PersonalServiceImpl implements PersonalService, PersonalInboxGateway {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> ITEM_STATUSES =
      Set.of("TODO", "IN_PROGRESS", "DONE", "CANCELLED");
  private static final Set<String> TODO_STATUSES = Set.of("PENDING", "DONE", "CANCELLED");
  private final PersonalRepository repository;
  private final Clock clock;

  /** Creates the personal work service with a UTC system clock. */
  @Autowired
  public PersonalServiceImpl(PersonalRepository repository) {
    this(repository, Clock.systemUTC());
  }

  PersonalServiceImpl(PersonalRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  private DeliveryTarget current() {
    var context = AuthorizationContextHolder.getContext();
    if (context == null || context.getUserId() == null || context.getScopeType() == null) {
      throw new AccessDeniedException("缺少当前用户或工作空间上下文");
    }
    String tenant =
        context.getScopeType() == AuthorizationScopeType.PLATFORM
            ? "__platform__"
            : required(context.getAttribute("X-Tenant-Id"), 100, "工作空间");
    return new DeliveryTarget(context.getUserId(), context.getScopeType().name() + ":" + tenant);
  }

  private Filter filter(DeliveryTarget target, Map<String, Object> equal) {
    return new Filter(
        target.userId(), target.workspace(), equal, null, false, false, clock.instant());
  }

  private <T extends OwnedRecord> T owned(Class<T> type, String id, boolean trash) {
    var target = current();
    return repository
        .find(
            type,
            new Filter(
                target.userId(),
                target.workspace(),
                Map.of("id", required(id, 64, "ID")),
                null,
                trash,
                false,
                clock.instant()))
        .orElseThrow(() -> new java.util.NoSuchElementException("记录不存在或无权访问"));
  }

  private static Pageable bounded(Pageable page) {
    int number = page == null || page.isUnpaged() ? 0 : page.getPageNumber();
    int size =
        page == null || page.isUnpaged() ? 20 : Math.min(100, Math.max(1, page.getPageSize()));
    if (number < 0 || (long) number * size > 1_000_000) {
      throw new IllegalArgumentException("分页范围过大");
    }
    return PageRequest.of(
        number,
        size,
        page == null ? org.springframework.data.domain.Sort.unsorted() : page.getSort());
  }

  @Override
  public Page<ItemView> items(
      String keyword,
      String status,
      String category,
      Integer priority,
      String view,
      Pageable page,
      Map<String, String> filters) {
    if (view == null || !Set.of("ACTIVE", "ARCHIVED", "TRASH", "OVERDUE").contains(view)) {
      throw new IllegalArgumentException("无效的事项视图");
    }
    final var target = current();
    Map<String, Object> equal = new LinkedHashMap<>();
    if (!"TRASH".equals(view)) {
      equal.put("archived", "ARCHIVED".equals(view));
    }
    if (status != null && !status.isBlank()) {
      if (!ITEM_STATUSES.contains(status)) {
        throw new IllegalArgumentException("无效的事项状态");
      }
      equal.put("status", status);
    }
    if ("OVERDUE".equals(view)) {
      equal.put("status", List.of("TODO", "IN_PROGRESS"));
    }
    if (category != null && !category.isBlank()) {
      equal.put("category", text(category, 60, "分类"));
    }
    if (priority != null) {
      equal.put("priority", priority(priority));
    }
    var filter =
        new Filter(
            target.userId(),
            target.workspace(),
            equal,
            text(keyword, 100, "搜索词"),
            "TRASH".equals(view),
            "OVERDUE".equals(view),
            clock.instant(),
            Map.copyOf(filters));
    return repository.page(PersonalItem.class, filter, bounded(page), "items").map(this::itemView);
  }

  @Override
  public ItemView item(String id) {
    return itemView(owned(PersonalItem.class, id, false));
  }

  @Override
  @Transactional
  public ItemView createItem(ItemInput input) {
    var item = new PersonalItem();
    initialize(item, current());
    apply(item, input);
    repository.insert(item);
    event(item, "CREATED", null);
    repository.flush();
    return itemView(item);
  }

  @Override
  @Transactional
  public ItemView updateItem(String id, ItemInput input) {
    require(input != null, "缺少事项内容");
    var item = owned(PersonalItem.class, id, false);
    revision(item, input.version());
    if (item.isArchived() || !Set.of("TODO", "IN_PROGRESS").contains(item.getStatus())) {
      throw new IllegalArgumentException("请先恢复归档或重新打开事项再编辑");
    }
    apply(item, input);
    event(item, "UPDATED", null);
    repository.flush();
    return itemView(item);
  }

  private void apply(PersonalItem item, ItemInput input) {
    if (input == null) {
      throw new IllegalArgumentException("缺少事项内容");
    }
    if (input.startAt() != null
        && input.dueAt() != null
        && input.dueAt().isBefore(input.startAt())) {
      throw new IllegalArgumentException("截止时间不能早于开始时间");
    }
    var checklist = input.checklist() == null ? List.<ChecklistEntry>of() : input.checklist();
    if (checklist.size() > 50) {
      throw new IllegalArgumentException("子清单最多 50 项");
    }
    Set<String> ids = new HashSet<>();
    var normalized =
        checklist.stream()
            .map(
                entry -> {
                  if (entry == null) {
                    throw new IllegalArgumentException("无效的子清单");
                  }
                  String id =
                      entry.id() == null || entry.id().isBlank()
                          ? UUID.randomUUID().toString()
                          : required(entry.id(), 64, "子项 ID");
                  if (!ids.add(id)) {
                    throw new IllegalArgumentException("子清单 ID 重复");
                  }
                  return new ChecklistEntry(id, required(entry.title(), 200, "子项标题"), entry.done());
                })
            .toList();
    item.setTitle(required(input.title(), 200, "标题"));
    item.setDescription(text(input.description(), 8000, "说明"));
    item.setCategory(text(input.category(), 60, "分类"));
    item.setPriority(priority(input.priority()));
    item.setStartAt(input.startAt());
    item.setDueAt(input.dueAt());
    item.setPinned(input.pinned());
    try {
      item.setChecklistJson(JSON.writeValueAsString(normalized));
    } catch (Exception error) {
      throw new IllegalArgumentException("子清单格式不正确", error);
    }
    item.setUpdatedAt(clock.instant());
  }

  @Override
  @Transactional
  public ItemView itemAction(String id, ActionInput input) {
    require(input != null, "缺少操作");
    String action = required(input.action(), 32, "操作");
    var item = owned(PersonalItem.class, id, "RESTORE".equals(action));
    revision(item, input.version());
    String status = item.getStatus();
    boolean active = !item.isArchived() && Set.of("TODO", "IN_PROGRESS").contains(status);
    switch (action) {
      case "START" -> {
        require(!item.isArchived() && "TODO".equals(status), "只有未开始事项可以开始");
        item.setStatus("IN_PROGRESS");
      }
      case "COMPLETE" -> {
        require(active, "只有进行中的事项可以完成");
        require(checklist(item).stream().allMatch(ChecklistEntry::done), "请先完成所有子清单");
        item.setStatus("DONE");
        item.setCompletedAt(clock.instant());
        deliverMessage(
            new DeliveryTarget(item.getOwnerId(), item.getWorkspace()),
            new Delivery(
                "PERSONAL_ITEMS",
                item.getId() + ":done:" + item.getVersion(),
                "事项已完成：" + item.getTitle().substring(0, Math.min(180, item.getTitle().length())),
                item.getTitle(),
                item.getPriority(),
                null,
                "/my/items?item=" + item.getId()));
      }
      case "CANCEL" -> {
        require(active, "只有未完成事项可以取消");
        item.setStatus("CANCELLED");
      }
      case "REOPEN" -> {
        require(!item.isArchived() && !active, "只有已完成或已取消事项可以重新打开");
        item.setStatus("TODO");
        item.setCompletedAt(null);
      }
      case "ARCHIVE" -> {
        require(!active && !item.isArchived(), "请先完成或取消事项再归档");
        item.setArchived(true);
      }
      case "UNARCHIVE" -> {
        require(item.isArchived(), "事项未归档");
        item.setArchived(false);
      }
      case "DELETE" -> item.setDeletedAt(clock.instant());
      case "RESTORE" -> item.setDeletedAt(null);
      case "COMMENT" -> required(input.comment(), 2000, "备注");
      default -> throw new IllegalArgumentException("不支持的事项操作");
    }
    item.setUpdatedAt(clock.instant());
    event(item, action, "COMMENT".equals(action) ? input.comment().trim() : null);
    repository.flush();
    return itemView(item);
  }

  @Override
  public Page<EventView> history(String id, Pageable page) {
    // Deleted records remain private but their history can be reviewed in the trash.
    PersonalItem item;
    try {
      item = owned(PersonalItem.class, id, false);
    } catch (java.util.NoSuchElementException missing) {
      item = owned(PersonalItem.class, id, true);
    }
    return repository
        .page(
            PersonalItemEvent.class,
            filter(current(), Map.of("itemId", item.getId())),
            bounded(page),
            "createdAt")
        .map(
            entry ->
                new EventView(
                    entry.getId(), entry.getAction(), entry.getDetail(), entry.getCreatedAt()));
  }

  private void event(PersonalItem item, String action, String detail) {
    var event = new PersonalItemEvent();
    initialize(event, new DeliveryTarget(item.getOwnerId(), item.getWorkspace()));
    event.setItemId(item.getId());
    event.setAction(action);
    event.setDetail(detail);
    repository.insert(event);
  }

  @Override
  public Page<TodoView> todos(
      String keyword, String status, Pageable page, Map<String, String> filters) {
    Map<String, Object> equal = new LinkedHashMap<>();
    if (status != null && !status.isBlank()) {
      if (!TODO_STATUSES.contains(status)) {
        throw new IllegalArgumentException("无效的待办状态");
      }
      equal.put("status", status);
    }
    var target = current();
    return repository
        .page(
            PersonalTodo.class,
            new Filter(
                target.userId(),
                target.workspace(),
                equal,
                text(keyword, 100, "搜索词"),
                false,
                false,
                clock.instant(),
                Map.copyOf(filters)),
            bounded(page),
            "dueAt")
        .map(this::todoView);
  }

  @Override
  public TodoView todo(String id) {
    return todoView(owned(PersonalTodo.class, id, false));
  }

  @Override
  @Transactional
  public TodoView createTodo(TodoInput input) {
    require(input != null, "缺少待办内容");
    return deliverTodo(
        current(),
        new Delivery(
            "MANUAL",
            UUID.randomUUID().toString(),
            input.title(),
            input.content(),
            input.priority(),
            input.dueAt(),
            null));
  }

  @Override
  @Transactional
  public TodoView todoAction(String id, ActionInput input) {
    require(input != null, "缺少操作");
    String action = required(input.action(), 32, "操作");
    var todo = owned(PersonalTodo.class, id, false);
    revision(todo, input.version());
    require("MANUAL".equals(todo.getSource()), "业务待办请进入来源系统处理，不能在此直接修改结果");
    switch (action) {
      case "COMPLETE" -> {
        require("PENDING".equals(todo.getStatus()), "待办已处理");
        todo.setStatus("DONE");
        todo.setCompletedAt(clock.instant());
      }
      case "CANCEL" -> {
        require("PENDING".equals(todo.getStatus()), "待办已处理");
        todo.setStatus("CANCELLED");
        todo.setCompletedAt(clock.instant());
      }
      case "REOPEN" -> {
        require(!"PENDING".equals(todo.getStatus()), "待办已处于待处理状态");
        todo.setStatus("PENDING");
        todo.setCompletedAt(null);
      }
      default -> throw new IllegalArgumentException("不支持的待办操作");
    }
    todo.setUpdatedAt(clock.instant());
    repository.flush();
    return todoView(todo);
  }

  @Override
  public Page<MessageView> messages(
      String keyword,
      boolean unreadOnly,
      boolean archived,
      Pageable page,
      Map<String, String> filters) {
    Map<String, Object> equal = new LinkedHashMap<>();
    equal.put("archived", archived);
    if (unreadOnly) {
      equal.put("readAt", null);
    }
    var target = current();
    return repository
        .page(
            PersonalMessage.class,
            new Filter(
                target.userId(),
                target.workspace(),
                equal,
                text(keyword, 100, "搜索词"),
                false,
                false,
                clock.instant(),
                Map.copyOf(filters)),
            bounded(page),
            "createdAt")
        .map(this::messageView);
  }

  @Override
  public MessageView message(String id) {
    return messageView(owned(PersonalMessage.class, id, false));
  }

  @Override
  @Transactional
  public MessageView messageAction(String id, ActionInput input) {
    require(input != null, "缺少操作");
    String action = required(input.action(), 32, "操作");
    var message = owned(PersonalMessage.class, id, false);
    revision(message, input.version());
    switch (action) {
      case "READ" -> {
        if (message.getReadAt() == null) {
          message.setReadAt(clock.instant());
        }
      }
      case "UNREAD" -> message.setReadAt(null);
      case "ARCHIVE" -> {
        message.setArchived(true);
        if (message.getReadAt() == null) {
          message.setReadAt(clock.instant());
        }
      }
      case "UNARCHIVE" -> message.setArchived(false);
      default -> throw new IllegalArgumentException("不支持的消息操作");
    }
    message.setUpdatedAt(clock.instant());
    repository.flush();
    return messageView(message);
  }

  @Override
  @Transactional
  public void readAllMessages() {
    var target = current();
    repository.readAllMessages(target.userId(), target.workspace(), clock.instant());
  }

  @Override
  public Overview overview() {
    var target = current();
    var active =
        filter(target, Map.of("archived", false, "status", List.of("TODO", "IN_PROGRESS")));
    var overdue =
        new Filter(
            active.owner(), active.workspace(), active.equal(), null, false, true, clock.instant());
    var pending = filter(target, Map.of("status", "PENDING"));
    Map<String, Object> unread = new LinkedHashMap<>();
    unread.put("archived", false);
    unread.put("readAt", null);
    var messages = filter(target, unread);
    var itemPage = repository.page(PersonalItem.class, active, PageRequest.of(0, 5), "dueAt");
    var todoPage = repository.page(PersonalTodo.class, pending, PageRequest.of(0, 5), "dueAt");
    var messagePage =
        repository.page(PersonalMessage.class, messages, PageRequest.of(0, 5), "createdAt");
    return new Overview(
        itemPage.getTotalElements(),
        repository.count(PersonalItem.class, overdue),
        todoPage.getTotalElements(),
        messagePage.getTotalElements(),
        itemPage.map(this::itemView).getContent(),
        todoPage.map(this::todoView).getContent(),
        messagePage.map(this::messageView).getContent());
  }

  @Override
  @Transactional
  public TodoView deliverTodo(DeliveryTarget target, Delivery input) {
    validateDelivery(target, input);
    var existing =
        repository.find(
            PersonalTodo.class,
            filter(target, Map.of("source", input.source(), "sourceKey", input.sourceKey())));
    if (existing.isPresent()) {
      return todoView(existing.get());
    }
    var todo = new PersonalTodo();
    initialize(todo, target);
    todo.setTitle(input.title().trim());
    todo.setContent(text(input.content(), 8000, "内容"));
    todo.setPriority(input.priority());
    todo.setSource(input.source());
    todo.setSourceKey(input.sourceKey());
    todo.setLinkPath(input.linkPath());
    todo.setDueAt(input.dueAt());
    repository.insert(todo);
    repository.flush();
    return todoView(todo);
  }

  @Override
  @Transactional
  public MessageView deliverMessage(DeliveryTarget target, Delivery input) {
    validateDelivery(target, input);
    var existing =
        repository.find(
            PersonalMessage.class,
            filter(target, Map.of("source", input.source(), "sourceKey", input.sourceKey())));
    if (existing.isPresent()) {
      return messageView(existing.get());
    }
    var message = new PersonalMessage();
    initialize(message, target);
    message.setTitle(input.title().trim());
    message.setContent(text(input.content(), 8000, "内容"));
    message.setPriority(input.priority());
    message.setSource(input.source());
    message.setSourceKey(input.sourceKey());
    message.setLinkPath(input.linkPath());
    repository.insert(message);
    repository.flush();
    return messageView(message);
  }

  @Override
  @Transactional
  public TodoView resolveTodo(
      DeliveryTarget target, String source, String sourceKey, String outcome) {
    require(outcome != null && Set.of("DONE", "CANCELLED").contains(outcome), "无效的待办处理结果");
    var todo =
        repository
            .find(
                PersonalTodo.class,
                filter(target, Map.of("source", source, "sourceKey", sourceKey)))
            .orElseThrow(() -> new java.util.NoSuchElementException("待办不存在"));
    if (!"PENDING".equals(todo.getStatus())) {
      require(outcome.equals(todo.getStatus()), "待办已存在不同的处理结果");
      return todoView(todo);
    }
    todo.setStatus(outcome);
    todo.setCompletedAt(clock.instant());
    todo.setUpdatedAt(clock.instant());
    repository.flush();
    return todoView(todo);
  }

  private void initialize(OwnedRecord record, DeliveryTarget target) {
    record.setOwnerId(target.userId());
    record.setWorkspace(target.workspace());
    record.setCreatedBy(target.userId());
    record.setUpdatedBy(target.userId());
    record.setCreatedAt(clock.instant());
    record.setUpdatedAt(clock.instant());
  }

  private static void validateDelivery(DeliveryTarget target, Delivery delivery) {
    require(target != null && delivery != null, "缺少投递内容或收件人");
    required(target.userId(), 64, "收件人");
    required(target.workspace(), 160, "工作空间");
    require(target.workspace().matches("(PLATFORM|TENANT|PERSONAL):[^\\s]+"), "工作空间格式错误");
    require(required(delivery.source(), 64, "来源").equals(delivery.source()), "来源不能包含首尾空格");
    require(
        required(delivery.sourceKey(), 160, "来源业务标识").equals(delivery.sourceKey()),
        "来源业务标识不能包含首尾空格");
    required(delivery.title(), 200, "标题");
    text(delivery.content(), 8000, "内容");
    priority(delivery.priority());
    String path = delivery.linkPath();
    require(
        path == null
            || (path.length() <= 512
                && path.startsWith("/")
                && !path.startsWith("//")
                && !path.contains("\\")
                && path.chars().noneMatch(Character::isISOControl)),
        "业务链接必须是站内路径");
  }

  private static void revision(OwnedRecord record, Long version) {
    if (version == null || !Objects.equals(record.getVersion(), version)) {
      throw new org.springframework.dao.OptimisticLockingFailureException("记录已变更，请刷新后重试；当前编辑内容未保存");
    }
  }

  private static int priority(int value) {
    require(value >= 0 && value <= 3, "无效的优先级");
    return value;
  }

  private static void require(boolean valid, String message) {
    if (!valid) {
      throw new IllegalArgumentException(message);
    }
  }

  private static String required(String value, int max, String label) {
    String result = text(value, max, label);
    require(!result.isBlank(), label + "不能为空");
    return result;
  }

  private static String text(String value, int max, String label) {
    String result = value == null ? "" : value.trim();
    require(result.length() <= max, label + "长度超过限制");
    return result;
  }

  private List<ChecklistEntry> checklist(PersonalItem item) {
    try {
      return JSON.readValue(item.getChecklistJson(), new TypeReference<>() {});
    } catch (Exception error) {
      throw new IllegalStateException("已保存的子清单格式异常", error);
    }
  }

  private ItemView itemView(PersonalItem item) {
    return new ItemView(
        item.getId(),
        item.getVersion(),
        item.getTitle(),
        item.getDescription(),
        item.getCategory(),
        item.getPriority(),
        item.getStatus(),
        item.getStartAt(),
        item.getDueAt(),
        item.getCompletedAt(),
        item.isPinned(),
        item.isArchived(),
        item.getDeletedAt(),
        checklist(item),
        item.getCreatedAt(),
        item.getUpdatedAt());
  }

  private TodoView todoView(PersonalTodo todo) {
    return new TodoView(
        todo.getId(),
        todo.getVersion(),
        todo.getTitle(),
        todo.getContent(),
        todo.getPriority(),
        todo.getStatus(),
        todo.getDueAt(),
        todo.getCompletedAt(),
        todo.getSource(),
        todo.getLinkPath(),
        todo.getCreatedAt());
  }

  private MessageView messageView(PersonalMessage message) {
    return new MessageView(
        message.getId(),
        message.getVersion(),
        message.getTitle(),
        message.getContent(),
        message.getPriority(),
        message.getSource(),
        message.getLinkPath(),
        message.getReadAt(),
        message.isArchived(),
        message.getCreatedAt());
  }
}

package org.simplepoint.core.query;

import java.util.List;
import java.util.function.Predicate;
import org.springframework.data.domain.Sort;

/** Platform default: explicit ordinal first; otherwise creation time descending. Never overrides a requested sort. */
public final class DefaultTableOrder {
  private DefaultTableOrder() {}

  public static Sort forModel(Class<?> type, Predicate<String> available) {
    for (String name : List.of("sort", "sortOrder", "sequence", "ordinal", "position")) {
      if (available.test(name) && numeric(type, name)) return Sort.by(name);
    }
    return available.test("createdAt") ? Sort.by(Sort.Direction.DESC, "createdAt") : Sort.unsorted();
  }

  public static boolean exists(Class<?> type, String name) {
    try {QueryCapabilities.field(type, name); return true;} catch (InvalidQueryException ignored) {return false;}
  }

  private static boolean numeric(Class<?> type, String name) {
    if (!exists(type, name)) return false;
    Class<?> field = QueryCapabilities.field(type, name).getType();
    return Number.class.isAssignableFrom(field) || field == int.class || field == long.class || field == short.class;
  }
}

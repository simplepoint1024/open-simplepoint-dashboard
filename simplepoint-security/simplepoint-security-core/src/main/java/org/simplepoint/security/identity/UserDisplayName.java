package org.simplepoint.security.identity;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.simplepoint.security.entity.User;

/**
 * Shared identity label: explicit full name, legacy structured name, then email; never nickname/ID.
 */
public final class UserDisplayName {
  /** Constant HQL for the fixed account alias u, including annotation-based legacy queries. */
  public static final String HQL =
      "coalesce(nullif(trim(u.name), ''), "
          + "nullif(trim(concat(trim(concat(coalesce(trim(u.familyName), ''), ' ', "
          + "coalesce(trim(u.middleName), ''))), ' ', coalesce(trim(u.givenName), ''))), ''), "
          + "nullif(trim(u.email), ''))";

  /** Equivalent native SQL for the pinned workflow read model, using the fixed alias u. */
  public static final String SQL =
      "coalesce(nullif(trim(u.name), ''), "
          + "nullif(trim(concat(trim(concat(coalesce(trim(u.family_name), ''), ' ', "
          + "coalesce(trim(u.middle_name), ''))), ' ', coalesce(trim(u.given_name), ''))), ''), "
          + "nullif(trim(u.email), ''))";

  private UserDisplayName() {}

  /**
   * Uses the existing family/middle/given order without guessing or persisting a user's full name.
   */
  public static String fullName(User user) {
    if (user == null) {
      return null;
    }
    String explicit = text(user.getName());
    return explicit != null
        ? explicit
        : text(
            Stream.of(user.getFamilyName(), user.getMiddleName(), user.getGivenName())
                .map(UserDisplayName::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" ")));
  }

  /** Returns null when neither a name nor an email is available. */
  public static String display(User user) {
    String name = fullName(user);
    return name != null ? name : user == null ? null : text(user.getEmail());
  }

  /** SQL-compatible blank normalization; stored fields remain untouched. */
  public static String text(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  /** Reuses the identity label for projection, predicates, sorting and data-scope evaluation. */
  public static Expression<String> expression(CriteriaBuilder cb, Path<User> user) {
    var familyMiddle =
        cb.trim(
            cb.concat(
                cb.concat(cb.coalesce(cb.trim(user.get("familyName")), ""), " "),
                cb.coalesce(cb.trim(user.get("middleName")), "")));
    var structured =
        cb.nullif(
            cb.trim(
                cb.concat(
                    cb.concat(familyMiddle, " "), cb.coalesce(cb.trim(user.get("givenName")), ""))),
            "");
    return cb.<String>coalesce()
        .value(cb.nullif(cb.trim(user.get("name")), ""))
        .value(structured)
        .value(cb.nullif(cb.trim(user.get("email")), ""))
        .as(String.class);
  }

  /**
   * Builds only readable scalar fields; protected values never influence a picker label or order.
   */
  public static String hql(Predicate<String> readable) {
    String name = scalar("name", readable);
    String family = scalar("familyName", readable);
    String middle = scalar("middleName", readable);
    String given = scalar("givenName", readable);
    String structured =
        "nullif(trim(concat(trim(concat(coalesce("
            + family
            + ", ''), ' ', coalesce("
            + middle
            + ", ''))), ' ', coalesce("
            + given
            + ", ''))), '')";
    return "coalesce(" + name + ", " + structured + ", " + scalar("email", readable) + ")";
  }

  private static String scalar(String field, Predicate<String> readable) {
    return readable.test(field) ? "nullif(trim(u." + field + "), '')" : "cast(null as string)";
  }
}

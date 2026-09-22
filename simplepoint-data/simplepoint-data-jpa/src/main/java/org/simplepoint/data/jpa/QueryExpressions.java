package org.simplepoint.data.jpa;

import jakarta.persistence.Lob;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.metamodel.Attribute;
import java.lang.reflect.AnnotatedElement;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.query.criteria.JpaExpression;
import org.hibernate.query.sqm.NodeBuilder;

/** SQL scalar expressions for queryable values whose storage is not a plain scalar column. */
public final class QueryExpressions {
  private QueryExpressions() {}

  /** Hibernate can expose a generic field's erased bound even on a concrete entity path. */
  public static Class<?> javaType(Expression<?> value) {
    if (value instanceof Path<?> path
        && path.getParentPath() != null
        && path.getModel() instanceof Attribute<?, ?> attribute
        && attribute.getJavaMember() instanceof java.lang.reflect.Field field) {
      return org.springframework.core.ResolvableType.forField(
              field, path.getParentPath().getJavaType())
          .resolve(value.getJavaType());
    }
    return value.getJavaType();
  }

  /**
   * Reads text LOB content for comparisons and ordering, never its PostgreSQL object identifier.
   * Non-LOB paths and already-computed projection expressions retain their original SQL type.
   */
  public static Expression<?> scalar(CriteriaBuilder cb, Expression<?> value) {
    if (javaType(value) != String.class
        || !(value instanceof Path<?> path)
        || !(path.getModel() instanceof Attribute<?, ?> attribute)
        || !(attribute.getJavaMember() instanceof AnnotatedElement member)
        || !member.isAnnotationPresent(Lob.class)
        || !(cb instanceof NodeBuilder builder)) {
      return value;
    }
    if (builder.getQueryEngine().getDialect() instanceof PostgreSQLDialect) {
      // Hibernate maps @Lob String to an OID, including older schemas with text-typed OID
      // columns.
      // The JDBC driver encodes CLOB bytes with the connection encoding.
      return cb.function(
          "convert_from",
          String.class,
          cb.function("lo_get", byte[].class, builder.sql("cast(? as oid)", Long.class, value)),
          cb.function("current_setting", String.class, cb.literal("client_encoding")));
    }
    // Hibernate's lower() type validator rejects CLOB even on engines supporting text casts.
    return builder.cast((JpaExpression<?>) value, String.class);
  }
}

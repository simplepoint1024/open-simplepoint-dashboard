package org.simplepoint.core.annotation;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import org.hibernate.annotations.IdGeneratorType;
import org.simplepoint.core.base.generator.UuidStringIdentifierGenerator;

/**
 * Annotation to specify that a field should use UUID string generation for its identifier.
 *
 * <p>This annotation is a shorthand for applying the {@link UuidStringIdentifierGenerator}
 * to an entity field.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Retention(RUNTIME)
@IdGeneratorType(UuidStringIdentifierGenerator.class)
public @interface UuidStringGenerator {
}

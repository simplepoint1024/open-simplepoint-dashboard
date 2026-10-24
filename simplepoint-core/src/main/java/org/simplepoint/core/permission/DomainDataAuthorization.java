package org.simplepoint.core.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that domain services enforce tenant, record and field visibility for this transport.
 *
 * <p>Functional authorities and workspace scope checks still apply. No standard page policy is
 * inferred from the functional authorities. Use only for domain projections with their own
 * authorization model, such as workflow participants; never for a standard CRUD controller or a
 * controller declaring {@link PermissionPage}. Every read and write must enforce the domain
 * boundary.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface DomainDataAuthorization {}

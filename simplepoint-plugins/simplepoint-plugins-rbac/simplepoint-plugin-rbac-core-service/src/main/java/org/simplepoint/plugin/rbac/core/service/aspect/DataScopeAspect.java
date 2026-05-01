/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.rbac.core.service.aspect;

import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.core.datascopeannotation.DataScopeContext;
import org.simplepoint.core.datascopeannotation.DataScopeFilter;
import org.springframework.stereotype.Component;

/**
 * AOP aspect that resolves the current user's data scope and populates {@link DataScopeContext}
 * before the annotated service method executes.
 *
 * <p>Intercepts methods annotated with {@link DataScopeFilter}, reads the effective
 * {@code dataScopeType} and {@code deptIds} from the current {@link AuthorizationContext},
 * builds an immutable {@link DataScopeCondition}, and stores it in the thread-local
 * {@link DataScopeContext}. The repository layer can then read from {@link DataScopeContext}
 * to add the appropriate row-level predicate (e.g. a JPA {@code Specification}).</p>
 *
 * <p>The context is always cleared in the {@code finally} block to prevent leakage.</p>
 */
@Aspect
@Component
public class DataScopeAspect {

  @Around("@annotation(dataScope)")
  public Object applyDataScope(ProceedingJoinPoint pjp, DataScopeFilter dataScope) throws Throwable {
    AuthorizationContext ctx = AuthorizationContextHolder.getContext();
    if (ctx == null) {
      return pjp.proceed();
    }

    String scopeType = ctx.getDataScopeType();
    String userId = ctx.getUserId();
    Set<String> deptIds = ctx.getDeptIds();

    DataScopeCondition condition = new DataScopeCondition(
        scopeType,
        dataScope.deptField(),
        dataScope.ownerField(),
        userId,
        deptIds
    );
    DataScopeContext.set(condition);
    try {
      return pjp.proceed();
    } finally {
      DataScopeContext.clear();
    }
  }
}

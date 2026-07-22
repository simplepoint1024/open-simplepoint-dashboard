/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package org.simplepoint.plugin.rbac.core.service.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.core.datascopeannotation.DataScopeContext;
import org.simplepoint.core.datascopeannotation.DataScopeFilter;

class DataScopeAspectTest {

  private final DataScopeAspect aspect = new DataScopeAspect();

  @AfterEach
  void tearDown() {
    DataScopeContext.clear();
  }

  @Test
  void applyDataScope_treatsAdministratorWithoutConfiguredScopeAsAll() throws Throwable {
    AuthorizationContext context = new AuthorizationContext();
    context.setIsAdministrator(true);
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    when(joinPoint.proceed()).thenAnswer(invocation -> {
      assertThat(DataScopeContext.get()).isNotNull();
      assertThat(DataScopeContext.get().isAllData()).isTrue();
      return "ok";
    });

    Object result;
    try (MockedStatic<AuthorizationContextHolder> holder = mockStatic(AuthorizationContextHolder.class)) {
      holder.when(AuthorizationContextHolder::getContext).thenReturn(context);
      result = aspect.applyDataScope(joinPoint, annotation());
    }

    assertThat(result).isEqualTo("ok");
    assertThat(DataScopeContext.get()).isNull();
  }

  @Test
  void applyDataScope_restoresOuterConditionAfterNestedCall() throws Throwable {
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("user-1");
    context.setDataScopeType("SELF");
    DataScopeCondition outer = new DataScopeCondition(
        "CUSTOM", "createOrgDeptId", "createdBy", "outer-user", Set.of("dept-1")
    );
    DataScopeContext.set(outer);
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    when(joinPoint.proceed()).thenReturn("ok");

    try (MockedStatic<AuthorizationContextHolder> holder = mockStatic(AuthorizationContextHolder.class)) {
      holder.when(AuthorizationContextHolder::getContext).thenReturn(context);
      aspect.applyDataScope(joinPoint, annotation());
    }

    assertThat(DataScopeContext.get()).isSameAs(outer);
  }

  private DataScopeFilter annotation() throws NoSuchMethodException {
    Method method = AnnotatedOperation.class.getDeclaredMethod("invoke");
    return method.getAnnotation(DataScopeFilter.class);
  }

  private static class AnnotatedOperation {

    @DataScopeFilter(deptField = "createOrgDeptId", ownerField = "createdBy")
    void invoke() {
      // Annotation fixture only.
    }
  }
}

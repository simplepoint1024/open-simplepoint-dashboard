/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.monitor.support;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.lang.reflect.Method;
import java.time.Instant;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.ErrorLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.monitor.properties.ErrorLogMonitorProperties;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Converts Logback events into audit record commands.
 */
public class ErrorLogCommandFactory {

  private static final int DEFAULT_MESSAGE_MAX_LENGTH = 4000;
  private static final int DEFAULT_EXCEPTION_MESSAGE_MAX_LENGTH = 4000;
  private static final int DEFAULT_STACK_TRACE_MAX_LENGTH = 16000;
  private static final String SPRING_APPLICATION_NAME = "spring.application.name";
  private static final String AUTHORIZATION_CONTEXT_HOLDER = "org.simplepoint.core.AuthorizationContextHolder";

  private final ErrorLogMonitorProperties properties;
  private final String applicationName;

  /**
   * Creates a factory with the monitor properties and runtime environment.
   *
   * @param properties  monitor properties
   * @param environment spring environment used to resolve the application name
   */
  public ErrorLogCommandFactory(final ErrorLogMonitorProperties properties, final Environment environment) {
    this.properties = properties;
    this.applicationName = normalize(environment == null ? null : environment.getProperty(SPRING_APPLICATION_NAME));
  }

  /**
   * Converts a logging event into an AMQP command for the auditing service.
   *
   * @param event the logging event
   * @return the resulting error log command
   */
  public ErrorLogRecordCommand create(final ILoggingEvent event) {
    ErrorLogRecordCommand command = new ErrorLogRecordCommand();
    command.setOccurredAt(Instant.ofEpochMilli(event.getTimeStamp()));
    command.setLevel(event.getLevel() == null ? null : event.getLevel().toString());
    command.setSourceService(truncate(firstNonBlank(properties.getSourceService(), applicationName), 64));
    command.setLoggerName(truncate(normalize(event.getLoggerName()), 256));
    command.setThreadName(truncate(normalize(event.getThreadName()), 128));
    command.setMessage(truncate(normalize(event.getFormattedMessage()), positive(properties.getMessageMaxLength(),
        DEFAULT_MESSAGE_MAX_LENGTH)));
    command.setExceptionType(truncate(resolveExceptionType(event.getThrowableProxy()), 256));
    command.setExceptionMessage(truncate(resolveExceptionMessage(event.getThrowableProxy()),
        positive(properties.getExceptionMessageMaxLength(), DEFAULT_EXCEPTION_MESSAGE_MAX_LENGTH)));
    command.setStackTrace(truncate(resolveStackTrace(event.getThrowableProxy()),
        positive(properties.getStackTraceMaxLength(), DEFAULT_STACK_TRACE_MAX_LENGTH)));
    command.setTenantId(truncate(resolveAuthorizationContextValue("getAttribute", "X-Tenant-Id"), 64));
    command.setContextId(truncate(resolveAuthorizationContextValue("getContextId"), 64));
    command.setUserId(truncate(resolveUserId(), 128));
    command.setRequestUri(truncate(resolveRequestValue("getRequestURI"), 512));
    command.setClientIp(truncate(resolveClientIp(), 64));
    return command;
  }

  private String resolveUserId() {
    String userId = resolveAuthorizationContextValue("getUserId");
    if (userId != null) {
      return userId;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return null;
    }
    String name = normalize(authentication.getName());
    if ("anonymousUser".equalsIgnoreCase(name)) {
      return null;
    }
    return name;
  }

  private String resolveExceptionType(final IThrowableProxy throwableProxy) {
    return throwableProxy == null ? null : normalize(throwableProxy.getClassName());
  }

  private String resolveExceptionMessage(final IThrowableProxy throwableProxy) {
    return throwableProxy == null ? null : normalize(throwableProxy.getMessage());
  }

  private String resolveStackTrace(final IThrowableProxy throwableProxy) {
    if (throwableProxy == null) {
      return null;
    }
    return normalize(ThrowableProxyUtil.asString(throwableProxy));
  }

  private String resolveClientIp() {
    String forwardedFor = resolveRequestValue("getHeader", "X-Forwarded-For");
    if (forwardedFor != null) {
      String[] candidates = forwardedFor.split(",");
      if (candidates.length > 0) {
        String firstCandidate = normalize(candidates[0]);
        if (firstCandidate != null) {
          return firstCandidate;
        }
      }
    }
    return firstNonBlank(resolveRequestValue("getRemoteAddr"), resolveRequestValue("getRemoteHost"));
  }

  private String resolveAuthorizationContextValue(final String methodName, final Object... arguments) {
    Object authorizationContext = resolveAuthorizationContext();
    return invokeStringMethod(authorizationContext, methodName, arguments);
  }

  private Object resolveAuthorizationContext() {
    try {
      Class<?> holderClass = Class.forName(AUTHORIZATION_CONTEXT_HOLDER);
      Method getContext = holderClass.getMethod("getContext");
      return getContext.invoke(null);
    } catch (ClassNotFoundException ex) {
      return null;
    } catch (ReflectiveOperationException ex) {
      return null;
    }
  }

  private String resolveRequestValue(final String methodName, final Object... arguments) {
    Object request = resolveCurrentRequest();
    return invokeStringMethod(request, methodName, arguments);
  }

  private Object resolveCurrentRequest() {
    RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
    if (requestAttributes == null) {
      return null;
    }
    try {
      Method getRequest = requestAttributes.getClass().getMethod("getRequest");
      return getRequest.invoke(requestAttributes);
    } catch (NoSuchMethodException ex) {
      return null;
    } catch (ReflectiveOperationException ex) {
      return null;
    }
  }

  private String invokeStringMethod(final Object target, final String methodName, final Object... arguments) {
    if (target == null) {
      return null;
    }
    try {
      Class<?>[] parameterTypes = new Class<?>[arguments.length];
      for (int i = 0; i < arguments.length; i++) {
        parameterTypes[i] = arguments[i].getClass();
      }
      Method method = target.getClass().getMethod(methodName, parameterTypes);
      Object result = method.invoke(target, arguments);
      if (!(result instanceof String value)) {
        return null;
      }
      return normalize(value);
    } catch (ReflectiveOperationException ex) {
      return null;
    }
  }

  private int positive(final Integer value, final int defaultValue) {
    if (value == null || value <= 0) {
      return defaultValue;
    }
    return value;
  }

  private String truncate(final String value, final int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private String firstNonBlank(final String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      String normalized = normalize(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private String normalize(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}

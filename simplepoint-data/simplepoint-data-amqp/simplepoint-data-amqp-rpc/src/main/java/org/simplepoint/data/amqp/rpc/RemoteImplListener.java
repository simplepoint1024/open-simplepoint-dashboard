/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.rpc;

import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.data.amqp.rpc.properties.ArpcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * A listener component for processing incoming AMQP
 * (Advanced Message Queuing Protocol) messages.
 * Integrates with Spring's application context to dynamically
 * invoke methods on beans annotated with
 * {@link AmqpRemoteService}. Handles message correlation,
 * reply-to functionality, and message priorities.
 */
@Component
class RemoteImplListener implements ApplicationContextAware {

  /**
   * Logger instance for logging message processing and errors.
   */
  private final Logger log = LoggerFactory.getLogger(RemoteImplListener.class);

  /**
   * The Spring application context used for retrieving beans dynamically.
   */
  private ApplicationContext applicationContext;

  /**
   * RabbitTemplate instance for sending messages and handling replies.
   */
  private final RabbitTemplate rabbitTemplate;


  private final ArpcProperties properties;

  private final MeterRegistry meterRegistry;

  private volatile DispatchRegistry dispatchRegistry;

  RemoteImplListener(
      final RabbitTemplate rabbitTemplate,
      final ArpcProperties properties,
      @Nullable final MeterRegistry meterRegistry
  ) {
    this.rabbitTemplate = rabbitTemplate;
    this.properties = properties;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Processes incoming messages from the specified queue.
   * Dynamically invokes methods on beans based on the message type and payload.
   *
   * @param message the incoming AMQP {@link Message} to process
   */
  @RabbitListener(
      queues = ArpcProperties.REQUEST_QUEUE_NAME_KEY,
      containerFactory = "arpcListenerContainerFactory"
  )
  void process(Message message) {
    MessageProperties requestMessageProps = message.getMessageProperties();
    String correlationId = requestMessageProps.getCorrelationId();
    String replyTo = requestMessageProps.getReplyTo();
    RemoteProtocol.RemoteMethodDescriptor descriptor = RemoteProtocol.resolveDescriptor(requestMessageProps);
    Object[] arguments = new Object[0];
    long startedAt = System.nanoTime();
    String outcome = "success";
    try {
      RemoteProtocol.assertSupportedProtocolVersion(requestMessageProps);
      Object decoded = RemoteProxyFactory.toObjectArray(message.getBody());
      if (decoded instanceof Object[] payload) {
        arguments = payload;
      } else if (decoded != null) {
        arguments = new Object[] {decoded};
      }
      String signature = RemoteProtocol.signature(
          descriptor.interfaceName(),
          descriptor.methodName(),
          descriptor.parameterTypes()
      );
      log.debug("Received RPC request from {} correlationId:{} type:{}",
          requestMessageProps.getAppId(), correlationId, signature);

      RemoteServiceMethod serviceMethod = resolveServiceMethod(descriptor, arguments);
      if (serviceMethod == null) {
        throw new IllegalStateException("No remote service method registered for " + signature);
      }

      Object invoke = serviceMethod.invoke(arguments);
      if (replyTo == null || replyTo.isBlank()) {
        log.debug("Processed one-way RPC call {}", signature);
        return;
      }
      publishReply(replyTo, buildReply(correlationId, signature, invoke), signature);
    } catch (AmqpRejectAndDontRequeueException deadLetterException) {
      outcome = "error";
      throw deadLetterException;
    } catch (Exception e) {
      outcome = "error";
      String signature = RemoteProtocol.signature(
          descriptor.interfaceName(),
          descriptor.methodName(),
          descriptor.parameterTypes()
      );
      handleFailure(replyTo, correlationId, signature, e);
    } finally {
      RemoteMetrics.recordServer(meterRegistry, descriptor, outcome, System.nanoTime() - startedAt);
    }
  }

  /**
   * Sets the Spring application context.
   *
   * @param applicationContext the {@link ApplicationContext} instance
   * @throws BeansException if application context initialization fails
   */
  @Override
  public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    this.applicationContext = applicationContext;
  }

  private Message buildReply(final String correlationId, final String signature, final Object value) {
    return RemoteProtocol.withProtocolVersion(MessageBuilder
        .withBody(RemoteProxyFactory.toByteArray(value))
        .setCorrelationId(correlationId)
        .setPriority(properties.getPriority())
        .setType(signature)
        .setAppId(properties.prefix(properties.getAppId())))
        .build();
  }

  private Message buildErrorReply(final String correlationId, final String signature, final Exception exception) {
    Throwable root = rootCause(exception);
    RemoteInvocationError error = new RemoteInvocationError(root.getClass().getName(), root.getMessage());
    return RemoteProtocol.withProtocolVersion(MessageBuilder
        .withBody(RemoteProxyFactory.toByteArray(error))
        .setCorrelationId(correlationId)
        .setPriority(properties.getPriority())
        .setType(signature)
        .setHeader(RemoteProtocol.HEADER_REMOTE_ERROR, true)
        .setAppId(properties.prefix(properties.getAppId())))
        .build();
  }

  private void publishReply(final String replyTo, final Message reply, final String signature) {
    try {
      rabbitTemplate.send(replyTo, reply);
    } catch (Exception exception) {
      throw deadLetter(signature, "Failed to publish RPC reply for " + signature, exception);
    }
  }

  private void handleFailure(final String replyTo, final String correlationId, final String signature,
                             final Exception exception) {
    log.error("Failed to process RPC request {}", signature, exception);
    if (replyTo == null || replyTo.isBlank()) {
      throw deadLetter(signature, "Failed RPC request with no replyTo for " + signature, exception);
    }
    try {
      rabbitTemplate.send(replyTo, buildErrorReply(correlationId, signature, exception));
    } catch (Exception replyError) {
      log.error("Failed to publish RPC error reply for {}", signature, replyError);
      throw deadLetter(signature, "Failed to publish RPC error reply for " + signature, replyError);
    }
  }

  private AmqpRejectAndDontRequeueException deadLetter(final String signature, final String message,
                                                       final Exception exception) {
    log.error("{}; routing original message to DLQ", message, exception);
    return new AmqpRejectAndDontRequeueException(message + " [signature=" + signature + "]", exception);
  }

  private Throwable rootCause(final Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private RemoteServiceMethod resolveServiceMethod(final RemoteProtocol.RemoteMethodDescriptor descriptor,
                                                  final Object[] arguments) {
    if (descriptor.interfaceName() == null || descriptor.methodName() == null) {
      return null;
    }
    DispatchRegistry registry = dispatchRegistry();
    RemoteMethodKey exactKey = new RemoteMethodKey(
        descriptor.interfaceName(),
        descriptor.methodName(),
        descriptor.parameterTypes()
    );
    RemoteServiceMethod exact = registry.bySignature().get(exactKey);
    if (exact != null) {
      return exact;
    }
    List<RemoteServiceMethod> candidates = registry.byMethod().get(new RemoteMethodSelector(
        descriptor.interfaceName(),
        descriptor.methodName()
    ));
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    List<RemoteServiceMethod> matches = candidates.stream()
        .filter(candidate -> candidate.matches(arguments))
        .toList();
    if (matches.isEmpty()) {
      return null;
    }
    if (matches.size() > 1) {
      throw new IllegalStateException("Ambiguous remote service method for "
          + RemoteProtocol.signature(descriptor.interfaceName(), descriptor.methodName(), descriptor.parameterTypes()));
    }
    return matches.get(0);
  }

  private DispatchRegistry dispatchRegistry() {
    DispatchRegistry current = dispatchRegistry;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (dispatchRegistry == null) {
        dispatchRegistry = buildDispatchRegistry();
      }
      return dispatchRegistry;
    }
  }

  private DispatchRegistry buildDispatchRegistry() {
    Map<RemoteMethodKey, RemoteServiceMethod> exact = new LinkedHashMap<>();
    Map<RemoteMethodSelector, List<RemoteServiceMethod>> grouped = new LinkedHashMap<>();
    Map<String, Object> beans = applicationContext.getBeansWithAnnotation(AmqpRemoteService.class);
    for (Map.Entry<String, Object> entry : beans.entrySet()) {
      Object bean = entry.getValue();
      Class<?> beanType = applicationContext.getType(entry.getKey());
      if (beanType == null) {
        beanType = bean.getClass();
      }
      for (Class<?> remoteInterface : collectRemoteInterfaces(beanType)) {
        for (Method method : remoteInterface.getMethods()) {
          RemoteServiceMethod serviceMethod = new RemoteServiceMethod(bean, method);
          RemoteMethodKey key = new RemoteMethodKey(
              remoteInterface.getName(),
              method.getName(),
              RemoteProtocol.parameterTypeNames(method)
          );
          exact.putIfAbsent(key, serviceMethod);
          grouped.computeIfAbsent(new RemoteMethodSelector(remoteInterface.getName(), method.getName()),
              ignored -> new ArrayList<>()).add(serviceMethod);
        }
      }
    }
    return new DispatchRegistry(Map.copyOf(exact), copyGrouped(grouped));
  }

  private Map<RemoteMethodSelector, List<RemoteServiceMethod>> copyGrouped(
      final Map<RemoteMethodSelector, List<RemoteServiceMethod>> grouped) {
    Map<RemoteMethodSelector, List<RemoteServiceMethod>> copied = new LinkedHashMap<>();
    grouped.forEach((key, value) -> copied.put(key, List.copyOf(value)));
    return Map.copyOf(copied);
  }

  private Collection<Class<?>> collectRemoteInterfaces(final Class<?> beanType) {
    Map<String, Class<?>> interfaces = new LinkedHashMap<>();
    ArrayDeque<Class<?>> queue = new ArrayDeque<>();
    queue.add(beanType);
    while (!queue.isEmpty()) {
      Class<?> current = queue.poll();
      if (current == null || Objects.equals(current, Object.class)) {
        continue;
      }
      for (Class<?> currentInterface : current.getInterfaces()) {
        if (currentInterface.isAnnotationPresent(AmqpRemoteClient.class)) {
          interfaces.putIfAbsent(currentInterface.getName(), currentInterface);
        }
        queue.offer(currentInterface);
      }
      if (current.getSuperclass() != null) {
        queue.offer(current.getSuperclass());
      }
    }
    return interfaces.values();
  }

  private record DispatchRegistry(Map<RemoteMethodKey, RemoteServiceMethod> bySignature,
                                  Map<RemoteMethodSelector, List<RemoteServiceMethod>> byMethod) {
  }

  private record RemoteMethodSelector(String interfaceName, String methodName) {
  }

  private record RemoteMethodKey(String interfaceName, String methodName, String[] parameterTypes) {

    @Override
    public boolean equals(final Object object) {
      if (this == object) {
        return true;
      }
      if (!(object instanceof RemoteMethodKey that)) {
        return false;
      }
      return Objects.equals(interfaceName, that.interfaceName)
          && Objects.equals(methodName, that.methodName)
          && java.util.Arrays.equals(parameterTypes, that.parameterTypes);
    }

    @Override
    public int hashCode() {
      return Objects.hash(interfaceName, methodName, java.util.Arrays.hashCode(parameterTypes));
    }
  }

  private record RemoteServiceMethod(Object bean, Method method) {

    private Object invoke(final Object[] arguments) throws Exception {
      return method.invoke(bean, arguments);
    }

    private boolean matches(final Object[] arguments) {
      Class<?>[] parameterTypes = method.getParameterTypes();
      if (parameterTypes.length != arguments.length) {
        return false;
      }
      for (int i = 0; i < parameterTypes.length; i++) {
        Object argument = arguments[i];
        Class<?> parameterType = parameterTypes[i];
        if (argument == null) {
          if (parameterType.isPrimitive()) {
            return false;
          }
          continue;
        }
        if (!RemoteProtocol.wrapPrimitive(parameterType).isAssignableFrom(argument.getClass())) {
          return false;
        }
      }
      return true;
    }
  }
}

/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.rpc;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.simplepoint.data.amqp.rpc.properties.ArpcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpMessageReturnedException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Configuration;

/**
 * A factory class for creating proxy beans and handling RPC-style remote method invocations
 * via AMQP (Advanced Message Queuing Protocol). This includes serializing arguments,
 * sending messages, and deserializing responses.
 */
@Slf4j
class RemoteProxyFactory {

  private static final Logger AUDIT_WARNING_LOG =
      LoggerFactory.getLogger("org.simplepoint.plugin.auditing.logging.monitor.transport");

  private static final ThreadLocal<Kryo> kryoThreadLocal = ThreadLocal.withInitial(() -> {
    Kryo kryo = new Kryo();
    kryo.setReferences(false); // Disable references to avoid circular references
    kryo.setRegistrationRequired(false); // Allow dynamic class registration
    return kryo;
  });

  /**
   * Creates a dynamic proxy bean definition for the specified interface and attributes.
   *
   * @param classLoader the class loader to define the proxy class
   * @param clazz       the class (interface) to proxy
   * @param attributes  additional attributes for the proxy
   * @return a Spring {@link BeanDefinition} for the proxy
   */
  static BeanDefinition proxy(final ClassLoader classLoader, final Class<?> clazz,
                              Map<String, Object> attributes) {
    Map<String, Object> proxyAttributes = new LinkedHashMap<>(attributes);
    proxyAttributes.putIfAbsent(RemoteInvocationHandler.REMOTE_INTERFACE_ATTRIBUTE, clazz.getName());
    InvocationHandler invocationHandler = new RemoteInvocationHandler(Map.copyOf(proxyAttributes));
    Object instance = Proxy.newProxyInstance(classLoader, new Class[] {clazz}, invocationHandler);
    BeanDefinitionBuilder beanDefinitionBuilder =
        BeanDefinitionBuilder.genericBeanDefinition(instance.getClass());
    beanDefinitionBuilder.addConstructorArgValue(invocationHandler);
    beanDefinitionBuilder.setLazyInit(true); // Lazily initialize the bean
    beanDefinitionBuilder.setPrimary(false); // Do not make it the primary bean
    beanDefinitionBuilder.setSynthetic(true); // Mark it as synthetic
    BeanDefinition beanDefinition = beanDefinitionBuilder.getBeanDefinition();
    beanDefinition.setAttribute(RemoteInvocationHandler.REMOTE_INTERFACE_ATTRIBUTE, clazz.getName());
    return beanDefinition;
  }

  /**
   * Serializes an object to a byte array.
   *
   * @param object the object to serialize
   * @return the serialized byte array
   */
  static byte[] toByteArray(Object object) {
    Kryo kryo = kryoThreadLocal.get();
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
         Output output = new Output(bos)) {
      kryo.writeClassAndObject(output, object);
      output.flush();
      return bos.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("Kryo serialize error", e);
    }
  }

  /**
   * Deserializes a byte array back into an object.
   *
   * @param byteArray the byte array to deserialize
   * @return the deserialized object
   */
  static Object toObjectArray(byte[] byteArray) {
    Kryo kryo = kryoThreadLocal.get();
    try (Input input = new Input(new ByteArrayInputStream(byteArray))) {
      return kryo.readClassAndObject(input);
    } catch (Exception e) {
      throw new RuntimeException("Kryo deserialize error", e);
    }
  }

  /**
   * An invocation handler for handling method calls on the proxy instance.
   */
  static class RemoteInvocationHandler implements InvocationHandler {
    private static final String AUDIT_TARGET_PREFIX = "auditing.";
    static final String REMOTE_INTERFACE_ATTRIBUTE = "remoteInterface";
    private final Map<String, Object> attributes;

    private static RabbitTemplate rabbitTemplate;

    private static RabbitTemplate auditRabbitTemplate;

    private static ArpcProperties properties;

    private static MeterRegistry meterRegistry;

    /**
     * Constructs the invocation handler with the provided attributes.
     *
     * @param attributes the attributes associated with the proxy
     */
    public RemoteInvocationHandler(Map<String, Object> attributes) {
      this.attributes = attributes;
    }

    /**
     * Handles method invocations on the proxy instance.
     *
     * @param proxy  the proxy instance
     * @param method the method being called
     * @param args   the arguments passed to the method
     * @return the result of the method call, or null if the method has no return value
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      long startedAt = System.nanoTime();
      String outcome = "success";
      String methodName = method.getName();
      if (methodName.equals("hashCode")) {
        return System.identityHashCode(proxy);
      } else if (methodName.equals("equals") && method.getParameterCount() == 1) {
        return proxy == args[0];
      } else if (methodName.equals("toString")) {
        return proxy.getClass().getName() + "@"
            + Integer.toHexString(System.identityHashCode(proxy)) + ", with InvocationHandler "
            + this;
      }

      String logicalTarget = resolveLogicalTarget();
      String to = resolveTarget(logicalTarget);
      String interfaceName = resolveInterfaceName(proxy, method);
      String[] parameterTypes = RemoteProtocol.parameterTypeNames(method);
      String typeName = RemoteProtocol.signature(interfaceName, method.getName(), parameterTypes);
      boolean auditFireAndForget = isAuditFireAndForget(method, logicalTarget);
      RabbitTemplate template = selectRabbitTemplate(auditFireAndForget);
      try {
        String correlationId = template.getUUID();
        Object[] arguments = RemoteProtocol.normalizeArguments(args);
        CorrelationData correlationData = new CorrelationData(correlationId);
        String exchange = properties.exchange();

        MessageBuilderSupport<Message> messageBuilder = MessageBuilder
            .withBody(toByteArray(arguments))
            .setAppId(properties.getAppId())
            .setPriority(properties.getPriority())
            .setCorrelationId(correlationId)
            .setType(typeName)
            .setHeader(RemoteProtocol.HEADER_INTERFACE_NAME, interfaceName)
            .setHeader(RemoteProtocol.HEADER_METHOD_NAME, method.getName())
            .setHeader(RemoteProtocol.HEADER_PARAMETER_TYPES, RemoteProtocol.encodeParameterTypes(parameterTypes));
        RemoteProtocol.withProtocolVersion(messageBuilder);

        if (method.getReturnType() == void.class) {
          template.send(exchange, to, messageBuilder.build(), correlationData);
          awaitPublisherConfirmForOneWay(template, to, typeName, correlationData);
          return null;
        }

        Message build = messageBuilder.build();
        log.debug("Sending RPC to: {}, correlationId: {}, type: {}", to, correlationId, typeName);
        Message messageResult =
            template.sendAndReceive(exchange, to, build, correlationData);

        if (messageResult == null) {
          RemoteInvocationException confirmFailure = publisherConfirmFailureIfReady(to, typeName, correlationData);
          if (confirmFailure != null) {
            outcome = "error";
            throw confirmFailure;
          }
          outcome = "timeout";
          throw RemoteInvocationException.timeout(to, typeName);
        }
        String protocolVersion = RemoteProtocol.resolveProtocolVersion(messageResult.getMessageProperties());
        if (!RemoteProtocol.isSupportedProtocolVersion(protocolVersion)) {
          throw RemoteInvocationException.protocol(to, typeName, protocolVersion);
        }
        if (RemoteProtocol.isRemoteError(messageResult.getMessageProperties())) {
          outcome = "remote_error";
          Object errorPayload = toObjectArray(messageResult.getBody());
          RemoteInvocationError error = errorPayload instanceof RemoteInvocationError remoteError
              ? remoteError
              : new RemoteInvocationError(errorPayload == null ? RuntimeException.class.getName()
              : errorPayload.getClass().getName(), Objects.toString(errorPayload, "Remote invocation failed"));
          throw RemoteInvocationException.remote(to, typeName, error);
        }
        return toObjectArray(messageResult.getBody());
      } catch (AmqpMessageReturnedException returnedException) {
        outcome = "error";
        if (auditFireAndForget) {
          warnAuditReturned(to, typeName, returnedException);
          return null;
        }
        throw RemoteInvocationException.unroutable(to, typeName, returnedException);
      } catch (AmqpException amqpException) {
        outcome = "error";
        if (auditFireAndForget) {
          warnAuditFailure(to, typeName, amqpException);
          return null;
        }
        throw RemoteInvocationException.transport(to, typeName, amqpException);
      } catch (Exception e) {
        if (e instanceof RemoteInvocationException remoteInvocationException) {
          if ("success".equals(outcome)) {
            outcome = "error";
          }
          if (auditFireAndForget) {
            warnAuditFailure(to, typeName, remoteInvocationException);
            return null;
          }
          throw remoteInvocationException;
        }
        outcome = "error";
        if (auditFireAndForget) {
          warnAuditFailure(to, typeName, e);
          return null;
        }
        throw new RuntimeException(e);
      } finally {
        RemoteMetrics.recordClient(
            meterRegistry,
            to,
            interfaceName,
            method.getName(),
            outcome,
            System.nanoTime() - startedAt
        );
      }
    }

    private String resolveLogicalTarget() {
      return attributes.get("to").toString();
    }

    private String resolveTarget(final String target) {
      Map<String, String> providers = properties.getProviders();
      boolean exist = providers.containsKey(target);
      return properties.prefix(exist ? providers.get(target) : target);
    }

    private String resolveInterfaceName(final Object proxy, final Method method) {
      Object configured = attributes.get(REMOTE_INTERFACE_ATTRIBUTE);
      if (configured instanceof String configuredName && configuredName != null && !configuredName.isBlank()) {
        return configuredName;
      }
      Class<?>[] interfaces = proxy == null ? new Class<?>[0] : proxy.getClass().getInterfaces();
      if (interfaces.length > 0 && interfaces[0] != null) {
        return interfaces[0].getName();
      }
      return method.getDeclaringClass().getName();
    }

    private RabbitTemplate selectRabbitTemplate(final boolean auditFireAndForget) {
      if (auditFireAndForget && auditRabbitTemplate != null) {
        return auditRabbitTemplate;
      }
      return rabbitTemplate;
    }

    private boolean isAuditFireAndForget(final Method method, final String logicalTarget) {
      return method.getReturnType() == void.class
          && logicalTarget != null
          && logicalTarget.startsWith(AUDIT_TARGET_PREFIX);
    }

    private void warnAuditReturned(final String target, final String signature,
                                   final AmqpMessageReturnedException returnedException) {
      if (returnedException == null) {
        AUDIT_WARNING_LOG.warn("Audit AMQP publish to [{}] for {} was returned by broker", target, signature);
        return;
      }
      AUDIT_WARNING_LOG.warn(
          "Audit AMQP publish to [{}] for {} was returned by broker: replyCode={}, replyText={}, exchange={}, routingKey={}",
          target,
          signature,
          returnedException.getReplyCode(),
          returnedException.getReplyText(),
          returnedException.getExchange(),
          returnedException.getRoutingKey()
      );
    }

    private void warnAuditFailure(final String target, final String signature, final Throwable throwable) {
      String message = throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()
          ? throwable == null ? "unknown failure" : throwable.getClass().getSimpleName()
          : throwable.getMessage();
      AUDIT_WARNING_LOG.warn("Audit AMQP publish to [{}] for {} failed: {}", target, signature, message);
    }

    private void awaitPublisherConfirmForOneWay(final RabbitTemplate template, final String target, final String signature,
                                                final CorrelationData correlationData) {
      if (!supportsPublisherConfirms(template)) {
        return;
      }
      long timeout = properties.getPublisher().getConfirmTimeout();
      try {
        CorrelationData.Confirm confirm = correlationData.getFuture().get(timeout, TimeUnit.MILLISECONDS);
        if (confirm == null) {
          log.warn("AMQP RPC publish to [{}] for {} completed without broker confirm result within {} ms",
              target, signature, timeout);
          return;
        }
        if (!confirm.ack()) {
          throw RemoteInvocationException.publisherConfirmNack(target, signature, confirm.reason());
        }
      } catch (TimeoutException exception) {
        log.warn("AMQP RPC publish to [{}] for {} did not receive a broker confirm within {} ms",
            target, signature, timeout);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw RemoteInvocationException.publisherConfirmFailure(target, signature, exception);
      } catch (ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof AmqpException amqpException) {
          throw RemoteInvocationException.transport(target, signature, amqpException);
        }
        throw RemoteInvocationException.publisherConfirmFailure(target, signature,
            cause == null ? exception : cause);
      }
    }

    private RemoteInvocationException publisherConfirmFailureIfReady(final String target, final String signature,
                                                                     final CorrelationData correlationData) {
      if (!supportsPublisherConfirms(rabbitTemplate) || !correlationData.getFuture().isDone()) {
        return null;
      }
      try {
        CorrelationData.Confirm confirm = correlationData.getFuture().getNow(null);
        if (confirm != null && !confirm.ack()) {
          return RemoteInvocationException.publisherConfirmNack(target, signature, confirm.reason());
        }
        return null;
      } catch (Exception exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof AmqpException amqpException) {
          return RemoteInvocationException.transport(target, signature, amqpException);
        }
        return RemoteInvocationException.publisherConfirmFailure(target, signature,
            cause == null ? exception : cause);
      }
    }

    private boolean supportsPublisherConfirms(final RabbitTemplate template) {
      if (template == null) {
        return false;
      }
      ConnectionFactory connectionFactory = template.getConnectionFactory();
      return connectionFactory != null && connectionFactory.isPublisherConfirms();
    }

    private static RabbitTemplate createAuditRabbitTemplate(final RabbitTemplate sourceTemplate) {
      if (sourceTemplate == null) {
        return null;
      }
      ConnectionFactory connectionFactory = sourceTemplate.getConnectionFactory();
      if (connectionFactory == null) {
        return sourceTemplate;
      }
      RabbitTemplate template = new RabbitTemplate(connectionFactory);
      template.setMandatory(true);
      template.setReturnsCallback(returned ->
          AUDIT_WARNING_LOG.warn(
              "Audit AMQP publish was returned by broker: replyCode={}, replyText={}, exchange={}, routingKey={}",
              returned.getReplyCode(),
              returned.getReplyText(),
              returned.getExchange(),
              returned.getRoutingKey()
          ));
      return template;
    }

    /**
     * A nested configuration class to load application context values.
     */
    @Configuration
    static class ApplicationContextLoader implements ApplicationContextAware {

      @SuppressWarnings("all")
      public ApplicationContextLoader(
          final ArpcProperties properties
      ) {
        RemoteInvocationHandler.properties = properties;
      }

      /**
       * Sets the application context to retrieve the RabbitTemplate bean.
       *
       * @param applicationContext the Spring {@link ApplicationContext}
       * @throws BeansException if a bean cannot be retrieved
       */
      @Override
      public void setApplicationContext(@NotNull ApplicationContext applicationContext)
          throws BeansException {
        RemoteInvocationHandler.rabbitTemplate = applicationContext.getBean(RabbitTemplate.class);
        RemoteInvocationHandler.auditRabbitTemplate =
            RemoteInvocationHandler.createAuditRabbitTemplate(RemoteInvocationHandler.rabbitTemplate);
        RemoteInvocationHandler.meterRegistry =
            applicationContext.getBeanProvider(MeterRegistry.class).getIfAvailable();
      }
    }
  }
}

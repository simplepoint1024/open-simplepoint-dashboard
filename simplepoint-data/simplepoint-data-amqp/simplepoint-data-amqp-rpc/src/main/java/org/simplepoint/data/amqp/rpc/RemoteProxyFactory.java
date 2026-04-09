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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.simplepoint.data.amqp.rpc.properties.ArpcProperties;
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
    InvocationHandler invocationHandler = new RemoteInvocationHandler(attributes);
    Object instance = Proxy.newProxyInstance(classLoader, new Class[] {clazz}, invocationHandler);
    BeanDefinitionBuilder beanDefinitionBuilder =
        BeanDefinitionBuilder.genericBeanDefinition(instance.getClass());
    beanDefinitionBuilder.addConstructorArgValue(invocationHandler);
    beanDefinitionBuilder.setLazyInit(true); // Lazily initialize the bean
    beanDefinitionBuilder.setPrimary(false); // Do not make it the primary bean
    beanDefinitionBuilder.setSynthetic(true); // Mark it as synthetic
    return beanDefinitionBuilder.getBeanDefinition();
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
    private final Map<String, Object> attributes;

    private static RabbitTemplate rabbitTemplate;

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

      String to = resolveTarget(method);
      String typeName = RemoteProtocol.signature(method);
      try {
        String correlationId = rabbitTemplate.getUUID();
        Object[] arguments = RemoteProtocol.normalizeArguments(args);
        String[] parameterTypes = RemoteProtocol.parameterTypeNames(method);
        CorrelationData correlationData = new CorrelationData(correlationId);
        String exchange = properties.exchange();

        MessageBuilderSupport<Message> messageBuilder = MessageBuilder
            .withBody(toByteArray(arguments))
            .setAppId(properties.getAppId())
            .setPriority(properties.getPriority())
            .setCorrelationId(correlationId)
            .setType(typeName)
            .setHeader(RemoteProtocol.HEADER_INTERFACE_NAME, method.getDeclaringClass().getName())
            .setHeader(RemoteProtocol.HEADER_METHOD_NAME, method.getName())
            .setHeader(RemoteProtocol.HEADER_PARAMETER_TYPES, RemoteProtocol.encodeParameterTypes(parameterTypes));
        RemoteProtocol.withProtocolVersion(messageBuilder);

        if (method.getReturnType() == void.class) {
          rabbitTemplate.send(exchange, to, messageBuilder.build(), correlationData);
          awaitPublisherConfirmForOneWay(to, typeName, correlationData);
          return null;
        }

        Message build = messageBuilder.build();
        log.debug("Sending RPC to: {}, correlationId: {}, type: {}", to, correlationId, typeName);
        Message messageResult =
            rabbitTemplate.sendAndReceive(exchange, to, build, correlationData);

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
        throw RemoteInvocationException.unroutable(to, typeName, returnedException);
      } catch (AmqpException amqpException) {
        outcome = "error";
        throw RemoteInvocationException.transport(to, typeName, amqpException);
      } catch (Exception e) {
        if (e instanceof RemoteInvocationException remoteInvocationException) {
          if ("success".equals(outcome)) {
            outcome = "error";
          }
          throw remoteInvocationException;
        }
        outcome = "error";
        throw new RuntimeException(e);
      } finally {
        RemoteMetrics.recordClient(meterRegistry, to, method, outcome, System.nanoTime() - startedAt);
      }
    }

    private String resolveTarget(final Method method) {
      String target = attributes.get("to").toString();
      Map<String, String> providers = properties.getProviders();
      boolean exist = providers.containsKey(target);
      return properties.prefix(exist ? providers.get(target) : target);
    }

    private void awaitPublisherConfirmForOneWay(final String target, final String signature,
                                                final CorrelationData correlationData) {
      if (!supportsPublisherConfirms()) {
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
      if (!supportsPublisherConfirms() || !correlationData.getFuture().isDone()) {
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

    private boolean supportsPublisherConfirms() {
      ConnectionFactory connectionFactory = rabbitTemplate.getConnectionFactory();
      return connectionFactory != null && connectionFactory.isPublisherConfirms();
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
        RemoteInvocationHandler.meterRegistry =
            applicationContext.getBeanProvider(MeterRegistry.class).getIfAvailable();
      }
    }
  }
}

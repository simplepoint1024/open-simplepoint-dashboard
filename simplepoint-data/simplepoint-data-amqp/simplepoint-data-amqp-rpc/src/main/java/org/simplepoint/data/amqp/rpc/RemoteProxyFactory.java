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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.simplepoint.data.amqp.rpc.properties.ArpcProperties;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
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
   * @throws Exception if an I/O error occurs
   */
  static byte[] toByteArray(Object object) throws Exception {
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
   * @throws Exception if an I/O error occurs
   */
  static Object toObjectArray(byte[] byteArray) throws Exception {
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
      try {
        String methodName = method.getName();
        if (methodName.equals("hashCode")) {
          return System.identityHashCode(proxy);
        } else if (methodName.equals("toString")) {
          return proxy.getClass().getName() + "@"
              + Integer.toHexString(System.identityHashCode(proxy)) + ", with InvocationHandler "
              + this;
        }

        String target = attributes.get("to").toString();
        boolean exist = properties.getServices().containsKey(target);
        String to = properties.prefix(exist
            ? properties.getServices().get(target) :
            target);
        String correlationId = rabbitTemplate.getUUID();
        String typeName = getTypeName(method);

        MessageBuilderSupport<Message> messageBuilder = MessageBuilder
            .withBody(toByteArray(args))
            .setAppId(properties.getAppId())
            .setPriority(properties.getPriority())
            .setCorrelationId(correlationId)
            .setType(typeName)
            .setHeader("to", to);

        if (method.getReturnType() == void.class) {
          rabbitTemplate.send(properties.prefix(properties.getExchangeName()), "",
              messageBuilder.build());
          return null;
        }

        Message build =
            messageBuilder.setReplyTo(properties.prefix(properties.getResponseQueueName())).build();
        log.info("Sending RPC to: {}, correlationId: {}, type: {}", to, correlationId, typeName);
        Message messageResult =
            rabbitTemplate.sendAndReceive(properties.prefix(properties.getExchangeName()), "",
                build);
        log.info("RPC response received: {}", messageResult);

        if (messageResult != null) {
          return toObjectArray(messageResult.getBody());
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return null;
    }

    /**
     * Constructs the fully qualified type name for a method.
     *
     * @param method the method to construct the name for
     * @return the fully qualified name of the method
     */
    private String getTypeName(Method method) {
      String className = method.getDeclaringClass().getName();
      String methodName = method.getName();
      return className + "." + methodName + "()";
    }

    /**
     * A nested configuration class to load application context values.
     */
    @Configuration
    static class ApplicationContextLoader implements ApplicationContextAware {

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
      }
    }
  }
}

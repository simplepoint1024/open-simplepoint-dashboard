/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.rpc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.simplepoint.core.ApplicationContextHolder;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.data.amqp.rpc.properties.ArpcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

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

  RemoteImplListener(
      final RabbitTemplate rabbitTemplate,
      final ArpcProperties properties
  ) {
    this.rabbitTemplate = rabbitTemplate;
    this.properties = properties;
  }

  /**
   * Processes incoming messages from the specified queue.
   * Dynamically invokes methods on beans based on the message type and payload.
   *
   * @param message the incoming AMQP {@link Message} to process
   */
  @RabbitListener(queues = ArpcProperties.REQUEST_QUEUE_NAME_KEY)
  void process(Message message) {
    try {
      MessageProperties requestMessageProps = message.getMessageProperties();
      String correlationId = requestMessageProps.getCorrelationId();
      String replyTo = requestMessageProps.getReplyTo();
      String type = requestMessageProps.getType();
      String to = properties.prefix(requestMessageProps.getHeader("to"));

      log.info("Received a request from {} correlationId:{} type:{}",
          requestMessageProps.getAppId(), correlationId, type);

      // Check if the message type is valid
      if (type == null) {
        log.warn("Message type is null, unable to process the message.");
        return;
      }

      // Extract class and method information from the message type
      int index = type.lastIndexOf('.');
      String className = type.substring(0, index);
      Map<String, ?> beans = applicationContext.getBeansOfType(
          ClassUtils.forName(className, ApplicationContextHolder.getClassloader()));

      for (Object bean : beans.values()) {
        // Process the bean if annotated with @AmqpRemoteService
        if (bean.getClass().isAnnotationPresent(AmqpRemoteService.class)) {
          String methodName = type.substring(index + 1).replace("()", "");
          List<Class<?>> classes = new ArrayList<>();

          // Convert message body to payload array
          Object[] payload = (Object[]) RemoteProxyFactory.toObjectArray(message.getBody());
          for (Object object : payload) {
            classes.add(object.getClass());
          }

          // Invoke the appropriate method on the bean
          Method declaredMethod =
              bean.getClass().getDeclaredMethod(methodName, classes.toArray(new Class<?>[0]));
          declaredMethod.setAccessible(true);
          Object invoke = declaredMethod.invoke(bean, payload);
          Class<?> returnType = declaredMethod.getReturnType();
          if (returnType == void.class) {
            log.info("Method {} invoked successfully on bean {} with no return value.",
                methodName, bean.getClass().getName());
            return;
          }
          if (replyTo == null || replyTo.isEmpty()) {
            log.warn("ReplyTo is null or empty, unable to send a reply for method {}.",
                methodName);
            return;
          }
          // Build the reply message
          Message build = MessageBuilder
              .withBody(RemoteProxyFactory.toByteArray(invoke))
              .setCorrelationId(correlationId)
              .setReplyTo(replyTo)
              .setPriority(properties.getPriority())
              .setHeader("to", to)
              .setAppId(properties.prefix(properties.getAppId()))
              .build();

          log.info("Sending with CorrelationId:{} to:{} reply to:{}", correlationId, to, replyTo);
          rabbitTemplate.send(replyTo, build);

          return;
        }
      }
    } catch (Exception e) {
      log.error(e.getMessage(), e);
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
}

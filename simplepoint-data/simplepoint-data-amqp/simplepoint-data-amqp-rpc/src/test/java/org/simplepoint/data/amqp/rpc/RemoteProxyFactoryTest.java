package org.simplepoint.data.amqp.rpc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.data.amqp.rpc.properties.ArpcProperties;
import org.springframework.amqp.core.AmqpMessageReturnedException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class RemoteProxyFactoryTest {

  @Mock
  private RabbitTemplate rabbitTemplate;

  @Mock
  private RabbitTemplate auditRabbitTemplate;

  @Mock
  private ConnectionFactory connectionFactory;

  private ArpcProperties properties;

  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() throws Exception {
    properties = new ArpcProperties();
    properties.setAppId("host");
    properties.setPrefix("simplepoint.arpc.");
    properties.setPriority(10);
    properties.setExchangeName("exchange.direct");
    properties.setProviders(Map.of("sample", "common"));
    meterRegistry = new SimpleMeterRegistry();
    setStaticField("rabbitTemplate", rabbitTemplate);
    setStaticField("auditRabbitTemplate", auditRabbitTemplate);
    setStaticField("properties", properties);
    setStaticField("meterRegistry", meterRegistry);
    lenient().when(rabbitTemplate.getUUID()).thenReturn("corr-1");
    lenient().when(auditRabbitTemplate.getUUID()).thenReturn("corr-1");
    lenient().when(rabbitTemplate.getConnectionFactory()).thenReturn(connectionFactory);
    lenient().when(auditRabbitTemplate.getConnectionFactory()).thenReturn(connectionFactory);
    properties.setProviders(Map.of(
        "sample", "common",
        "auditing.login-log", "auditing"
    ));
  }

  @AfterEach
  void tearDown() throws Exception {
    setStaticField("rabbitTemplate", null);
    setStaticField("auditRabbitTemplate", null);
    setStaticField("properties", null);
    setStaticField("meterRegistry", null);
  }

  @Test
  void invokeShouldSendEmptyArgumentArrayForNoArgMethod() throws Throwable {
    Message reply = MessageBuilder.withBody(RemoteProxyFactory.toByteArray("pong")).build();
    when(rabbitTemplate.sendAndReceive(any(), any(), any(Message.class), any(CorrelationData.class))).thenReturn(reply);
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "sample"));
    Method method = SampleApi.class.getMethod("ping");
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {SampleApi.class}, handler);

    Object result = handler.invoke(proxy, method, null);

    assertEquals("pong", result);
    ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).sendAndReceive(eq(properties.exchange()), eq("simplepoint.arpc.common"),
        captor.capture(), any(CorrelationData.class));
    Message request = captor.getValue();
    Object body = RemoteProxyFactory.toObjectArray(request.getBody());
    assertEquals(0, ((Object[]) body).length);
    assertEquals(null, request.getMessageProperties().getReplyTo());
    assertEquals(RemoteProtocol.PROTOCOL_VERSION,
        request.getMessageProperties().getHeaders().get(RemoteProtocol.HEADER_PROTOCOL_VERSION));
    assertEquals(1.0d, meterRegistry.get(RemoteMetrics.CLIENT_REQUESTS)
        .tag("target", "simplepoint.arpc.common")
        .tag("interface", SampleApi.class.getName())
        .tag("method", "ping")
        .tag("outcome", "success")
        .counter()
        .count());
  }

  @Test
  void invokeShouldThrowRemoteInvocationExceptionForExplicitRemoteError() throws Throwable {
    Message errorReply = MessageBuilder.withBody(
            RemoteProxyFactory.toByteArray(new RemoteInvocationError(IllegalStateException.class.getName(), "boom")))
        .setHeader(RemoteProtocol.HEADER_REMOTE_ERROR, true)
        .build();
    when(rabbitTemplate.sendAndReceive(any(), any(), any(Message.class), any(CorrelationData.class)))
        .thenAnswer(invocation -> {
          CorrelationData correlationData = invocation.getArgument(3);
          correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
          return errorReply;
        });
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "sample"));
    Method method = SampleApi.class.getMethod("ping");
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {SampleApi.class}, handler);

    RemoteInvocationException exception = assertThrows(RemoteInvocationException.class,
        () -> handler.invoke(proxy, method, null));

    assertEquals("simplepoint.arpc.common", exception.getTarget());
    assertEquals(IllegalStateException.class.getName(), exception.getRemoteType());
    assertEquals(1.0d, meterRegistry.get(RemoteMetrics.CLIENT_REQUESTS)
        .tag("target", "simplepoint.arpc.common")
        .tag("interface", SampleApi.class.getName())
        .tag("method", "ping")
        .tag("outcome", "remote_error")
        .counter()
        .count());
  }

  @Test
  void invokeShouldRecordTimeoutMetricWhenNoReplyArrives() throws Throwable {
    when(rabbitTemplate.sendAndReceive(any(), any(), any(Message.class), any(CorrelationData.class)))
        .thenAnswer(invocation -> {
          CorrelationData correlationData = invocation.getArgument(3);
          correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
          return null;
        });
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "sample"));
    Method method = SampleApi.class.getMethod("ping");
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {SampleApi.class}, handler);

    RemoteInvocationException exception = assertThrows(RemoteInvocationException.class,
        () -> handler.invoke(proxy, method, null));

    assertEquals("simplepoint.arpc.common", exception.getTarget());
    assertEquals(1.0d, meterRegistry.get(RemoteMetrics.CLIENT_REQUESTS)
        .tag("target", "simplepoint.arpc.common")
        .tag("interface", SampleApi.class.getName())
        .tag("method", "ping")
        .tag("outcome", "timeout")
        .counter()
        .count());
  }

  @Test
  void invokeShouldRejectUnsupportedReplyProtocolVersion() throws Throwable {
    Message reply = MessageBuilder.withBody(RemoteProxyFactory.toByteArray("pong"))
        .setHeader(RemoteProtocol.HEADER_PROTOCOL_VERSION, "2")
        .build();
    when(rabbitTemplate.sendAndReceive(any(), any(), any(Message.class), any(CorrelationData.class))).thenReturn(reply);
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "sample"));
    Method method = SampleApi.class.getMethod("ping");
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {SampleApi.class}, handler);

    RemoteInvocationException exception = assertThrows(RemoteInvocationException.class,
        () -> handler.invoke(proxy, method, null));

    assertEquals("simplepoint.arpc.common", exception.getTarget());
    assertEquals(IllegalStateException.class.getName(), exception.getRemoteType());
    assertTrue(exception.getMessage().contains("unsupported protocol version [2]"));
    assertEquals(1.0d, meterRegistry.get(RemoteMetrics.CLIENT_REQUESTS)
        .tag("target", "simplepoint.arpc.common")
        .tag("interface", SampleApi.class.getName())
        .tag("method", "ping")
        .tag("outcome", "error")
        .counter()
        .count());
  }

  @Test
  void invokeShouldSurfaceReturnedMessageAsUnroutablePublish() throws Throwable {
    ReturnedMessage returnedMessage = new ReturnedMessage(
        MessageBuilder.withBody(RemoteProxyFactory.toByteArray("pong")).build(),
        312,
        "NO_ROUTE",
        properties.exchange(),
        "simplepoint.arpc.common"
    );
    when(rabbitTemplate.sendAndReceive(any(), any(), any(Message.class), any(CorrelationData.class)))
        .thenThrow(new AmqpMessageReturnedException("returned", returnedMessage));
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "sample"));
    Method method = SampleApi.class.getMethod("ping");
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {SampleApi.class}, handler);

    RemoteInvocationException exception = assertThrows(RemoteInvocationException.class,
        () -> handler.invoke(proxy, method, null));

    assertEquals("simplepoint.arpc.common", exception.getTarget());
    assertEquals(AmqpMessageReturnedException.class.getName(), exception.getRemoteType());
    assertTrue(exception.getMessage().contains("returned by broker: NO_ROUTE"));
    assertEquals(1.0d, meterRegistry.get(RemoteMetrics.CLIENT_REQUESTS)
        .tag("target", "simplepoint.arpc.common")
        .tag("interface", SampleApi.class.getName())
        .tag("method", "ping")
        .tag("outcome", "error")
        .counter()
        .count());
  }

  @Test
  void invokeShouldSurfacePublisherNack() throws Throwable {
    when(connectionFactory.isPublisherConfirms()).thenReturn(true);
    when(rabbitTemplate.sendAndReceive(any(), any(), any(Message.class), any(CorrelationData.class)))
        .thenAnswer(invocation -> {
          CorrelationData correlationData = invocation.getArgument(3);
          correlationData.getFuture().complete(new CorrelationData.Confirm(false, "broker-nack"));
          return null;
        });
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "sample"));
    Method method = SampleApi.class.getMethod("ping");
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {SampleApi.class}, handler);

    RemoteInvocationException exception = assertThrows(RemoteInvocationException.class,
        () -> handler.invoke(proxy, method, null));

    assertEquals("simplepoint.arpc.common", exception.getTarget());
    assertEquals(IllegalStateException.class.getName(), exception.getRemoteType());
    assertTrue(exception.getMessage().contains("nacked by broker: broker-nack"));
  }

  @Test
  void invokeShouldAwaitPublisherConfirmForVoidCalls() throws Throwable {
    when(connectionFactory.isPublisherConfirms()).thenReturn(true);
    doAnswer(invocation -> {
      CorrelationData correlationData = invocation.getArgument(3);
      correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(rabbitTemplate).send(any(), any(), any(Message.class), any(CorrelationData.class));
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "sample"));
    Method method = SampleApi.class.getMethod("fire");
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {SampleApi.class}, handler);

    Object result = handler.invoke(proxy, method, null);

    assertEquals(null, result);
    verify(rabbitTemplate).send(eq(properties.exchange()), eq("simplepoint.arpc.common"),
        any(Message.class), any(CorrelationData.class));
  }

  @Test
  void invokeShouldOnlyWarnForAuditVoidCallsWhenBrokerReturnsMessage() throws Throwable {
    ReturnedMessage returnedMessage = new ReturnedMessage(
        MessageBuilder.withBody(RemoteProxyFactory.toByteArray("pong")).build(),
        312,
        "NO_ROUTE",
        properties.exchange(),
        "simplepoint.arpc.auditing"
    );
    doAnswer(invocation -> {
      throw new AmqpMessageReturnedException("returned", returnedMessage);
    }).when(auditRabbitTemplate).send(any(), any(), any(Message.class), any(CorrelationData.class));
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "auditing.login-log"));
    Method method = SampleApi.class.getMethod("fire");
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {SampleApi.class}, handler);

    Object result = assertDoesNotThrow(() -> handler.invoke(proxy, method, null));

    assertEquals(null, result);
    verify(auditRabbitTemplate).send(eq(properties.exchange()), eq("simplepoint.arpc.auditing"),
        any(Message.class), any(CorrelationData.class));
  }

  @Test
  void invokeShouldStillThrowForNonAuditVoidCallsWhenBrokerReturnsMessage() throws Throwable {
    ReturnedMessage returnedMessage = new ReturnedMessage(
        MessageBuilder.withBody(RemoteProxyFactory.toByteArray("pong")).build(),
        312,
        "NO_ROUTE",
        properties.exchange(),
        "simplepoint.arpc.common"
    );
    doAnswer(invocation -> {
      throw new AmqpMessageReturnedException("returned", returnedMessage);
    }).when(rabbitTemplate).send(any(), any(), any(Message.class), any(CorrelationData.class));
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "sample"));
    Method method = SampleApi.class.getMethod("fire");
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {SampleApi.class}, handler);

    RemoteInvocationException exception = assertThrows(RemoteInvocationException.class,
        () -> handler.invoke(proxy, method, null));

    assertEquals("simplepoint.arpc.common", exception.getTarget());
    assertEquals(AmqpMessageReturnedException.class.getName(), exception.getRemoteType());
  }

  @Test
  void invokeShouldNotBlockOnPublisherConfirmForRequestReplyCalls() throws Throwable {
    Message reply = MessageBuilder.withBody(RemoteProxyFactory.toByteArray("pong")).build();
    when(rabbitTemplate.sendAndReceive(any(), any(), any(Message.class), any(CorrelationData.class))).thenReturn(reply);
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "sample"));
    Method method = SampleApi.class.getMethod("ping");
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {SampleApi.class}, handler);

    Object result = handler.invoke(proxy, method, null);

    assertEquals("pong", result);
  }

  @Test
  void invokeShouldSkipOneWayConfirmWaitWhenPublisherConfirmsDisabled() throws Throwable {
    doAnswer(invocation -> null).when(rabbitTemplate)
        .send(any(), any(), any(Message.class), any(CorrelationData.class));
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "sample"));
    Method method = SampleApi.class.getMethod("fire");
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {SampleApi.class}, handler);

    Object result = handler.invoke(proxy, method, null);

    assertEquals(null, result);
  }

  @Test
  void invokeShouldUseRemoteInterfaceNameForInheritedMethods() throws Throwable {
    Message reply = MessageBuilder.withBody(RemoteProxyFactory.toByteArray("pong")).build();
    when(rabbitTemplate.sendAndReceive(any(), any(), any(Message.class), any(CorrelationData.class))).thenReturn(reply);
    RemoteProxyFactory.RemoteInvocationHandler handler =
        new RemoteProxyFactory.RemoteInvocationHandler(Map.of("to", "sample"));
    Method method = InheritedSampleApi.class.getMethod("ping");
    Object proxy = Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class[] {InheritedSampleApi.class},
        handler
    );

    Object result = handler.invoke(proxy, method, null);

    assertEquals("pong", result);
    ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).sendAndReceive(eq(properties.exchange()), eq("simplepoint.arpc.common"),
        captor.capture(), any(CorrelationData.class));
    Message request = captor.getValue();
    assertEquals(InheritedSampleApi.class.getName(),
        request.getMessageProperties().getHeaders().get(RemoteProtocol.HEADER_INTERFACE_NAME));
    assertEquals(
        RemoteProtocol.signature(InheritedSampleApi.class.getName(), "ping", new String[0]),
        request.getMessageProperties().getType()
    );
  }

  private void setStaticField(final String name, final Object value) throws Exception {
    Field field = RemoteProxyFactory.RemoteInvocationHandler.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(null, value);
  }

  interface SampleApi {
    String ping();

    void fire();
  }

  interface ParentSampleApi {
    String ping();
  }

  interface InheritedSampleApi extends ParentSampleApi {
  }
}

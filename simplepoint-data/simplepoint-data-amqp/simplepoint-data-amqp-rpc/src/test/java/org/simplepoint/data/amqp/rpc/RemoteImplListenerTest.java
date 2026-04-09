package org.simplepoint.data.amqp.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.data.amqp.rpc.properties.ArpcProperties;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationContext;

@ExtendWith(MockitoExtension.class)
class RemoteImplListenerTest {

  @Mock
  private RabbitTemplate rabbitTemplate;

  @Mock
  private ApplicationContext applicationContext;

  private RemoteImplListener listener;

  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    ArpcProperties properties = new ArpcProperties();
    properties.setAppId("common");
    properties.setPrefix("simplepoint.arpc.");
    properties.setPriority(10);
    meterRegistry = new SimpleMeterRegistry();
    listener = new RemoteImplListener(rabbitTemplate, properties, meterRegistry);
    listener.setApplicationContext(applicationContext);

    SampleRemoteService service = new SampleRemoteService();
    lenient().when(applicationContext.getBeansWithAnnotation(AmqpRemoteService.class))
        .thenReturn(Map.of("sampleRemoteService", service));
    lenient().when(applicationContext.getType("sampleRemoteService"))
        .thenAnswer(invocation -> SampleRemoteService.class);
  }

  @Test
  void processShouldInvokeNoArgMethod() throws Exception {
    Message request = requestMessage(SampleApi.class.getMethod("ping"), new Object[0], "reply.queue");

    listener.process(request);

    Message response = captureResponse();
    Object body = RemoteProxyFactory.toObjectArray(response.getBody());
    assertEquals("pong", body);
    assertEquals(RemoteProtocol.PROTOCOL_VERSION,
        response.getMessageProperties().getHeaders().get(RemoteProtocol.HEADER_PROTOCOL_VERSION));
    assertEquals(1.0d, meterRegistry.get(RemoteMetrics.SERVER_REQUESTS)
        .tag("interface", SampleApi.class.getName())
        .tag("method", "ping")
        .tag("outcome", "success")
        .counter()
        .count());
  }

  @Test
  void processShouldResolveDeclaredInterfaceParameterTypes() throws Exception {
    Map<String, String> values = new HashMap<>();
    values.put("name", "rpc");
    Message request = requestMessage(SampleApi.class.getMethod("echo", Map.class), new Object[] {values}, "reply.queue");

    listener.process(request);

    Object response = RemoteProxyFactory.toObjectArray(captureResponse().getBody());
    assertEquals("rpc", response);
  }

  @Test
  void processShouldReturnExplicitErrorReply() throws Exception {
    Message request = requestMessage(SampleApi.class.getMethod("boom"), new Object[0], "reply.queue");

    listener.process(request);

    ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq("reply.queue"), captor.capture());
    Message reply = captor.getValue();
    assertEquals(true, reply.getMessageProperties().getHeaders().get(RemoteProtocol.HEADER_REMOTE_ERROR));
    Object body = RemoteProxyFactory.toObjectArray(reply.getBody());
    RemoteInvocationError error = assertInstanceOf(RemoteInvocationError.class, body);
    assertEquals(IllegalStateException.class.getName(), error.getType());
    assertEquals("boom", error.getMessage());
    assertEquals(1.0d, meterRegistry.get(RemoteMetrics.SERVER_REQUESTS)
        .tag("interface", SampleApi.class.getName())
        .tag("method", "boom")
        .tag("outcome", "error")
        .counter()
        .count());
  }

  @Test
  void processShouldAcceptLegacyRequestWithoutProtocolVersion() throws Exception {
    Message request = requestMessage(SampleApi.class.getMethod("ping"), new Object[0], "reply.queue", false);

    listener.process(request);

    Object response = RemoteProxyFactory.toObjectArray(captureResponse().getBody());
    assertEquals("pong", response);
  }

  @Test
  void processShouldReturnExplicitErrorReplyForUnsupportedProtocolVersion() throws Exception {
    Message request = requestMessage(SampleApi.class.getMethod("ping"), new Object[0], "reply.queue", true, "2");

    listener.process(request);

    Message reply = captureResponse();
    assertEquals(RemoteProtocol.PROTOCOL_VERSION,
        reply.getMessageProperties().getHeaders().get(RemoteProtocol.HEADER_PROTOCOL_VERSION));
    assertEquals(true, reply.getMessageProperties().getHeaders().get(RemoteProtocol.HEADER_REMOTE_ERROR));
    RemoteInvocationError error =
        assertInstanceOf(RemoteInvocationError.class, RemoteProxyFactory.toObjectArray(reply.getBody()));
    assertEquals(IllegalStateException.class.getName(), error.getType());
    assertTrue(error.getMessage().contains("Unsupported AMQP RPC protocol version [2]"));
  }

  @Test
  void processShouldBuildDispatchCacheOnce() throws Exception {
    Message ping = requestMessage(SampleApi.class.getMethod("ping"), new Object[0], "reply.queue");
    Message echo = requestMessage(SampleApi.class.getMethod("echo", Map.class), new Object[] {Map.of("name", "cache")}, "reply.queue");

    listener.process(ping);
    listener.process(echo);

    verify(applicationContext, times(1)).getBeansWithAnnotation(AmqpRemoteService.class);
  }

  @Test
  void processShouldDeadLetterFailedOneWayRequest() throws Exception {
    Message request = requestMessage(SampleApi.class.getMethod("oneWayBoom"), new Object[0], null);

    AmqpRejectAndDontRequeueException exception = assertThrows(AmqpRejectAndDontRequeueException.class,
        () -> listener.process(request));

    assertTrue(exception.getMessage().contains("no replyTo"));
    verify(rabbitTemplate, never()).send(eq("reply.queue"), any(Message.class));
  }

  @Test
  void processShouldDeadLetterWhenReplyPublishFails() throws Exception {
    Message request = requestMessage(SampleApi.class.getMethod("ping"), new Object[0], "reply.queue");
    doThrow(new AmqpException("reply publish failed") {
    }).when(rabbitTemplate).send(eq("reply.queue"), any(Message.class));

    AmqpRejectAndDontRequeueException exception = assertThrows(AmqpRejectAndDontRequeueException.class,
        () -> listener.process(request));

    assertTrue(exception.getMessage().contains("Failed to publish RPC reply"));
  }

  private Message captureResponse() {
    ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate, times(1)).send(eq("reply.queue"), captor.capture());
    return captor.getValue();
  }

  private Message requestMessage(final java.lang.reflect.Method method, final Object[] arguments, final String replyTo) {
    return requestMessage(method, arguments, replyTo, true);
  }

  private Message requestMessage(final java.lang.reflect.Method method, final Object[] arguments,
                                 final String replyTo, final boolean includeProtocolVersion) {
    return requestMessage(method, arguments, replyTo, includeProtocolVersion, RemoteProtocol.PROTOCOL_VERSION);
  }

  private Message requestMessage(final java.lang.reflect.Method method, final Object[] arguments,
                                 final String replyTo, final boolean includeProtocolVersion,
                                 final String protocolVersion) {
    var builder = MessageBuilder.withBody(RemoteProxyFactory.toByteArray(arguments))
        .setCorrelationId("corr-1")
        .setType(RemoteProtocol.signature(method))
        .setHeader(RemoteProtocol.HEADER_INTERFACE_NAME, method.getDeclaringClass().getName())
        .setHeader(RemoteProtocol.HEADER_METHOD_NAME, method.getName())
        .setHeader(RemoteProtocol.HEADER_PARAMETER_TYPES,
            RemoteProtocol.encodeParameterTypes(RemoteProtocol.parameterTypeNames(method)));
    if (replyTo != null) {
      builder.setReplyTo(replyTo);
    }
    if (includeProtocolVersion) {
      builder.setHeader(RemoteProtocol.HEADER_PROTOCOL_VERSION, protocolVersion);
    }
    return builder.build();
  }

  @AmqpRemoteClient(to = "sample")
  interface SampleApi {
    String ping();

    String echo(Map<String, String> values);

    String boom();

    void oneWayBoom();
  }

  @AmqpRemoteService
  static final class SampleRemoteService implements SampleApi {

    @Override
    public String ping() {
      return "pong";
    }

    @Override
    public String echo(final Map<String, String> values) {
      return values.get("name");
    }

    @Override
    public String boom() {
      throw new IllegalStateException("boom");
    }

    @Override
    public void oneWayBoom() {
      throw new IllegalStateException("one-way-boom");
    }
  }
}

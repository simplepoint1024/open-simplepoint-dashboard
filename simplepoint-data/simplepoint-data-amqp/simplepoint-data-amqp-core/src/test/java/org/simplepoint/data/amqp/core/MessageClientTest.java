package org.simplepoint.data.amqp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;

@ExtendWith(MockitoExtension.class)
class MessageClientTest {

  @Mock
  private RabbitTemplate rabbitTemplate;

  @Mock
  private MessageConverter messageConverter;

  private MessageClient client;

  @BeforeEach
  void setUp() {
    client = new MessageClient(rabbitTemplate);
    when(rabbitTemplate.getMessageConverter()).thenReturn(messageConverter);
    when(rabbitTemplate.getUUID()).thenReturn("corr-1");
    when(messageConverter.toMessage(any(), any(MessageProperties.class))).thenAnswer(invocation ->
        new Message("request".getBytes(StandardCharsets.UTF_8), invocation.getArgument(1))
    );
    when(messageConverter.fromMessage(any(Message.class))).thenReturn("response");
  }

  @Test
  void sendAndReceiveShouldKeepReplyToAndTypeInCorrectFields() {
    when(rabbitTemplate.sendAndReceive(anyString(), anyString(), any(Message.class)))
        .thenReturn(new Message("reply".getBytes(StandardCharsets.UTF_8), new MessageProperties()));

    String result = client.sendAndReceive(
        "exchange",
        "routing-key",
        "reply.queue",
        "sample.type",
        "payload",
        String.class
    );

    assertEquals("response", result);
    ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).sendAndReceive(eq("exchange"), eq("routing-key"), captor.capture());
    Message request = captor.getValue();
    assertEquals("reply.queue", request.getMessageProperties().getReplyTo());
    assertEquals("sample.type", request.getMessageProperties().getType());
  }
}

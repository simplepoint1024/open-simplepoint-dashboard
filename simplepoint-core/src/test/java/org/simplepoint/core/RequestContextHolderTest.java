package org.simplepoint.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletRequestAttributes;

class RequestContextHolderTest {

  @BeforeEach
  void setUpRequestContext() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    org.springframework.web.context.request.RequestContextHolder
        .setRequestAttributes(new ServletRequestAttributes(request, response));
  }

  @AfterEach
  void tearDown() {
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void setContext_andGetContext_returnsStoredValue() {
    RequestContextHolder.setContext("MY_KEY", "myValue");
    String result = RequestContextHolder.getContext("MY_KEY", String.class);
    assertThat(result).isEqualTo("myValue");
  }

  @Test
  void getContext_missingKey_returnsNull() {
    String result = RequestContextHolder.getContext("NONEXISTENT", String.class);
    assertThat(result).isNull();
  }

  @Test
  void getContext_wrongType_throwsIllegalStateException() {
    RequestContextHolder.setContext("INT_KEY", 42);
    assertThatThrownBy(() -> RequestContextHolder.getContext("INT_KEY", String.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not of type");
  }

  @Test
  void clearContext_removesStoredValue() {
    RequestContextHolder.setContext("CLEAR_KEY", "value");
    RequestContextHolder.clearContext("CLEAR_KEY");
    assertThat(RequestContextHolder.getContext("CLEAR_KEY", String.class)).isNull();
  }

  @Test
  void setContext_withNoRequestAttributes_doesNotThrow() {
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    // Should silently no-op when no request context
    RequestContextHolder.setContext("KEY", "value");
  }

  @Test
  void getContext_withNoRequestAttributes_returnsNull() {
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    String result = RequestContextHolder.getContext("KEY", String.class);
    assertThat(result).isNull();
  }

  @Test
  void clearContext_withNoRequestAttributes_doesNotThrow() {
    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    RequestContextHolder.clearContext("KEY");
  }

  @Test
  void constants_haveExpectedValues() {
    assertThat(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY).isEqualTo("AUTH_CTX");
    assertThat(RequestContextHolder.TENANT_CONTEXT_KEY).isEqualTo("TENANT_CTX");
    assertThat(RequestContextHolder.USER_CONTEXT_KEY).isEqualTo("USER_CTX");
  }
}

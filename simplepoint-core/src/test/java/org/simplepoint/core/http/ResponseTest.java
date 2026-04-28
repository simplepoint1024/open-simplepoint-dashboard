package org.simplepoint.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class ResponseTest {

  @Test
  void of_withBodyHeadersStatus_createsResponse() {
    Response<String> response = Response.of("body", HttpHeaders.EMPTY, HttpStatus.OK);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("body");
  }

  @Test
  void of_withResponseEntity_wrapsCorrectly() {
    var entity = org.springframework.http.ResponseEntity.ok("payload");
    Response<String> response = Response.of(entity);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("payload");
  }

  @Test
  void okay_noBody_returns200WithJsonContentType() {
    Response<Void> response = Response.okay();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).isNull();
  }

  @Test
  void okay_withBody_returns200WithBody() {
    Response<String> response = Response.okay("hello");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("hello");
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
  }

  @Test
  void ise_noBody_returns500() {
    Response<Void> response = Response.ise();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
  }

  @Test
  void ise_withBody_returns500WithBody() {
    Response<String> response = Response.ise("error details");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).isEqualTo("error details");
  }

  @Test
  void br_returns400() {
    Response<Void> response = Response.br();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
  }

  @Test
  void nf_returns404() {
    Response<Void> response = Response.nf();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void limit_returnsPageResponse() {
    List<String> items = Arrays.asList("a", "b");
    Page<String> page = new PageImpl<>(items, PageRequest.of(0, 10), 2);
    Response<Page<String>> response = Response.limit(page, String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(page);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
  }

  @Test
  void data_returnsCollectionResponse() {
    List<String> items = Arrays.asList("x", "y", "z");
    Response<java.util.Collection<String>> response = Response.data(items);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsExactlyInAnyOrderElementsOf(items);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
  }

  @Test
  void of_withBodyBuilder_builds200() {
    Response<Void> response = Response.of(
        org.springframework.http.ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
    );
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void of_withHeadersBuilder_builds200() {
    Response<Void> response = Response.of(
        org.springframework.http.ResponseEntity.noContent()
    );
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }
}

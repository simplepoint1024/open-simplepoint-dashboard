/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.http;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * HTTP Unified response structure.
 *
 * @param <T> Entity type.
 */
@Schema(description = "HTTP 统一响应结构")
public class Response<T> extends ResponseEntity<T> {
  /**
   * Unique Construction.
   *
   * @param body       response body.
   * @param headers    response headers.
   * @param statusCode Response status code.
   */
  public Response(T body, HttpHeaders headers,
                  HttpStatusCode statusCode) {
    super(body, headers, statusCode);
  }

  /**
   * of.
   *
   * @param body       response body.
   * @param headers    response headers.
   * @param statusCode Response status code.
   * @param <T>        type.
   * @return Return a unified request result.
   */
  public static <T> Response<T> of(T body,
                                   HttpHeaders headers,
                                   HttpStatusCode statusCode) {

    return new Response<>(body, headers, statusCode);
  }

  /**
   * of.
   *
   * @param response Response entity.
   * @param <T>      type.
   * @return Return a unified request result.
   */
  public static <T> Response<T> of(ResponseEntity<T> response) {
    return of(response.getBody(), response.getHeaders(), response.getStatusCode());
  }

  /**
   * of.
   *
   * @param build Response body builder.
   * @param <T>   type
   * @return Return a unified request result.
   */
  public static <T> Response<T> of(BodyBuilder build) {
    return of(build.build());
  }

  /**
   * of.
   *
   * @param headers To HTTP Response.
   * @param <T>     type
   * @return Return a unified request result.
   */
  public static <T> Response<T> of(HeadersBuilder<?> headers) {
    return of(headers.build());
  }

  /**
   * Return to pagination query.
   *
   * @param pageable Used to pass paging parameters during HTTP requests.
   * @param resource class.
   * @param <T>      Return a unified request result.
   * @return Return a unified request result.
   */
  public static <T> Response<Page<T>> limit(Page<T> pageable, Class<T> resource) {
    return of(
        ok().contentType(MediaType.APPLICATION_JSON)
            .body(pageable)
    );
  }

  /**
   * Return to collection query.
   *
   * @param data Collection data.
   * @param <T>  entity type.
   * @return Return a unified request result.
   */
  public static <T> Response<Collection<T>> data(Collection<T> data) {
    return of(
        ok().contentType(MediaType.APPLICATION_JSON)
            .body(data)
    );
  }

  /**
   * HTTP request successful code with 200.
   *
   * @param <T> entity type.
   * @return Return a unified request result.
   */
  public static <T> Response<T> okay() {
    return of(ok().contentType(MediaType.APPLICATION_JSON));
  }

  /**
   * HTTP request successful code with 200.
   *
   * @param body entity.
   * @param <T>  entity type.
   * @return Return a unified request result.
   */
  public static <T> Response<T> okay(T body) {
    return of(ok().contentType(MediaType.APPLICATION_JSON).body(body));
  }

  /**
   * HTTP request failure code with 500.
   *
   * @param <T> entity type.
   * @return Return a unified request result.
   */
  public static <T> Response<T> ise() {
    return of(internalServerError().contentType(MediaType.APPLICATION_JSON));
  }

  /**
   * HTTP Request failure code with 500.
   *
   * @param body entity type.
   * @param <T>  entity type.
   * @return Return a unified request result.
   */
  public static <T> Response<T> ise(T body) {
    return of(internalServerError().contentType(MediaType.APPLICATION_JSON).body(body));
  }

  /**
   * Bad Request.
   *
   * @param <T> entity type.
   * @return Return a unified request result.
   */
  public static <T> Response<T> br() {
    return of(badRequest().contentType(MediaType.APPLICATION_JSON));
  }

  /**
   * Page not found.
   *
   * @param <T> entity type.
   * @return Return a unified request result.
   */
  public static <T> Response<T> nf() {
    return of(notFound());
  }
}

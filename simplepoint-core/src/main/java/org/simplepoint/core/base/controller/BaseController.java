/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.base.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import java.io.Serializable;
import org.simplepoint.api.base.BaseEntity;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.core.http.Response;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * A generic base controller providing common functionality for managing entities.
 * 一个通用的基础控制器，提供管理实体的常见功能
 *
 * @param <S> the service type extending BaseService
 *            继承自 BaseService 的服务类型
 * @param <T> the entity type extending BaseEntity
 *            继承自 BaseEntity 的实体类型
 * @param <I> the entity identifier type extending Serializable
 *            继承自 Serializable 的实体标识类型
 */
public class BaseController<S extends BaseService<T, I>, T extends BaseEntity<I>, I extends Serializable> {

  /**
   * The service instance used for managing entities.
   * 用于管理实体的服务实例
   */
  protected final S service;

  /**
   * Constructor initializing the controller with a service instance.
   * 使用服务实例初始化控制器的构造函数
   *
   * @param service the service instance
   *                服务实例
   */
  public BaseController(
      final S service
  ) {
    this.service = service;
  }

  /**
   * Retrieves metadata about the Role entity.
   *
   * @return a Response containing metadata about the Role entity
   */
  @GetMapping("/schema")
  @Operation(summary = "获取实体元数据", description = "检索有关实体的元数据")
  public Response<ObjectNode> schema() {
    return ok(service.schema());
  }

  /**
   * Returns an internal server error response.
   * 返回一个服务器内部错误响应
   *
   * @param <T> the response type
   *            响应类型
   * @return an internal server error response 服务器内部错误响应
   */
  public static <T> Response<T> ise() {
    return Response.ise();
  }

  /**
   * Returns a bad request response.
   * 返回一个错误请求响应
   *
   * @param body     the response body
   *                 响应体
   * @param resource the resource class type
   *                 资源类类型
   * @param <T>      the response type
   *                 响应类型
   * @return a bad request response 错误请求响应
   */
  public static <T> Response<T> br(T body, Class<T> resource) {
    return Response.br();
  }

  /**
   * Returns a not found response.
   * 返回一个未找到响应
   *
   * @param body     the response body
   *                 响应体
   * @param resource the resource class type
   *                 资源类类型
   * @param <T>      the response type
   *                 响应类型
   * @return a not found response 未找到响应
   */
  public static <T> Response<T> nf(T body, Class<T> resource) {
    return Response.nf();
  }

  /**
   * Returns a success response with the provided body.
   * 返回一个包含提供的响应体的成功响应
   *
   * @param body the response body
   *             响应体
   * @param <T>  the response type
   *             响应类型
   * @return a success response 成功响应
   */
  public static <T> Response<T> ok(T body) {
    return Response.okay(body);
  }

  /**
   * Returns a generic success response.
   * 返回一个通用的成功响应
   *
   * @return a success response 成功响应
   */
  protected Response<T> ok() {
    return Response.okay();
  }

  /**
   * Returns a paginated response with the given entity list.
   * 返回一个包含给定实体列表的分页响应
   *
   * @param pageable the paginated entity list
   *                 分页的实体列表
   * @param resource the entity class type
   *                 实体类类型
   * @return a paginated response 分页响应
   */
  protected Response<Page<T>> limit(Page<T> pageable, Class<T> resource) {
    return Response.limit(pageable, resource);
  }
}


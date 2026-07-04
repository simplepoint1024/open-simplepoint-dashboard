/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.gateway.server.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards known SPA routes to the host application's entry document.
 */
@Hidden
@Controller
public class SpaForwardController {

  private static final Resource INDEX_HTML = new ClassPathResource("static/index.html");

  /**
   * Returns the host SPA entry document for front-end history routes.
   *
   * @return host entry document response
   */
  @GetMapping({
      "/dashboard",
      "/profile",
      "/settings",
      "/system",
      "/system/**",
      "/i18n",
      "/i18n/**",
      "/monitoring",
      "/monitoring/**",
      "/platform",
      "/platform/**",
      "/support",
      "/support/**",
      "/dna",
      "/dna/dashboard",
      "/dna/drivers",
      "/dna/data-sources",
      "/dna/metadata",
      "/dna/dialects",
      "/dna/federation",
      "/dna/federation/data-catalogs",
      "/dna/federation/jdbc-users",
      "/dna/federation/sql-console",
      "/dna/federation/query-policies",
      "/dna/federation/query-audits",
      "/dna/federation/query-templates",
      "/dna/federation/views",
      "/dna/health",
      "/dna/data-assets",
      "/dna/data-quality"
  })
  public ResponseEntity<Resource> forwardSpaRoute() {
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .cacheControl(CacheControl.noStore())
        .body(INDEX_HTML);
  }
}

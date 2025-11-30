///*
// * Copyright (c) 2025 Jinxu Liu or Organization
// * Licensed under the Apache License, Version 2.0 (the "License");
// * You may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// * https://www.apache.org/licenses/LICENSE-2.0
// */
//
//package org.simplepoint.plugin.oidc.service.initialize;
//
//import java.util.List;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.boot.ApplicationArguments;
//import org.springframework.boot.ApplicationRunner;
//import org.springframework.boot.autoconfigure.security.oauth2.server.servlet.AuthorizationServerPropertiesMapperDelegate;
//import org.springframework.boot.autoconfigure.security.oauth2.server.servlet.OAuth2AuthorizationServerProperties;
//import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
//import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
//import org.springframework.stereotype.Component;
//import org.springframework.transaction.annotation.Transactional;
//
//
///**
// * Initializes OAuth2 client registrations on application startup.
// *
// * <p>This component automatically registers OAuth2 clients defined in
// * {@link OAuth2AuthorizationServerProperties}. If a client is not already registered,
// * it will be saved to the {@link RegisteredClientRepository}.
// * </p>
// */
//@Slf4j
//@Component
//public class ClientRegistrationInitialize implements ApplicationRunner {
//
//  /**
//   * List of pre-configured OAuth2 clients from application properties.
//   */
//  private final List<RegisteredClient> registeredClients;
//
//  /**
//   * Repository for storing registered OAuth2 clients.
//   */
//  private final RegisteredClientRepository registeredClientRepository;
//
//  /**
//   * Constructs an instance of {@code ClientRegistrationInitialize}.
//   *
//   * @param clientProperties           the authorization server properties defining OAuth2 clients
//   * @param registeredClientRepository the repository storing registered clients
//   * @throws IllegalArgumentException if any parameter is {@code null}
//   */
//  public ClientRegistrationInitialize(
//      OAuth2AuthorizationServerProperties clientProperties,
//      RegisteredClientRepository registeredClientRepository) {
//    this.registeredClients =
//        new AuthorizationServerPropertiesMapperDelegate(clientProperties).asRegisteredClients();
//    this.registeredClientRepository = registeredClientRepository;
//  }
//
//  /**
//   * Runs the client registration process on application startup.
//   *
//   * <p>Iterates through configured clients, checks if they are already registered,
//   * and registers missing clients while encoding their secrets.
//   * </p>
//   *
//   * @param args the application startup arguments
//   * @throws Exception if an error occurs during client registration
//   */
//  @Override
//  @Transactional
//  public void run(ApplicationArguments args) throws Exception {
//    for (RegisteredClient registeredClient : registeredClients) {
//      log.info("Checking client registration for {}", registeredClient.getClientId());
//
//      if (registeredClientRepository.findByClientId(registeredClient.getClientId()) == null) {
//        log.info("Found client with id {}", registeredClient.getClientId());
//
//        // Encode client secret before saving
//        registeredClientRepository.save(registeredClient);
//
//        log.info("Saved registered client {}", registeredClient.getClientId());
//      }
//    }
//  }
//}
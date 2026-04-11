/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.auditing.logging.monitor.logback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.auditing.logging.api.pojo.command.ErrorLogRecordCommand;
import org.simplepoint.plugin.auditing.logging.api.service.ErrorLogRemoteService;
import org.simplepoint.plugin.auditing.logging.monitor.properties.ErrorLogMonitorProperties;
import org.simplepoint.plugin.auditing.logging.monitor.support.ErrorLogCommandFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;

class ErrorLogReportingAppenderTest {

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void shouldConvertLoggingEventToAuditCommand() {
    ErrorLogCommandFactory commandFactory = new ErrorLogCommandFactory(properties(), environment("authorization"));
    LoggingEvent event = event(Level.ERROR, "Login failed", new IllegalStateException("account locked"));

    ErrorLogRecordCommand command = commandFactory.create(event);

    assertEquals("ERROR", command.getLevel());
    assertEquals("authorization", command.getSourceService());
    assertEquals("org.simplepoint.tests.Logging", command.getLoggerName());
    assertEquals("Login failed", command.getMessage());
    assertEquals(Instant.ofEpochMilli(1712100000000L), command.getOccurredAt());
    assertEquals("java.lang.IllegalStateException", command.getExceptionType());
    assertEquals("account locked", command.getExceptionMessage());
    assertNotNull(command.getStackTrace());
  }

  @Test
  void shouldReportWarnAndErrorButIgnorePlainInfo() {
    RecordingRemoteService remoteService = new RecordingRemoteService();
    ErrorLogReportingAppender appender = new ErrorLogReportingAppender(
        remoteService,
        new ErrorLogCommandFactory(properties(), environment("common"))
    );
    LoggerContext loggerContext = new LoggerContext();
    appender.setContext(loggerContext);
    appender.setName("testErrorLogAppender");
    appender.start();
    appender.enableReporting();

    appender.doAppend(event(Level.INFO, "skip me", null));
    appender.doAppend(event(Level.WARN, "warn me", null));
    appender.doAppend(event(Level.INFO, "capture throwable", new IllegalArgumentException("bad request")));

    assertEquals(2, remoteService.commands.size());
    assertEquals("WARN", remoteService.commands.get(0).getLevel());
    assertEquals("warn me", remoteService.commands.get(0).getMessage());
    assertEquals("INFO", remoteService.commands.get(1).getLevel());
    assertEquals("java.lang.IllegalArgumentException", remoteService.commands.get(1).getExceptionType());
    assertEquals("bad request", remoteService.commands.get(1).getExceptionMessage());
    assertNull(remoteService.commands.get(0).getExceptionType());
  }

  @Test
  void shouldNotBlockOtherThreadsWhenWarnReportingWaitsOnRemoteCall() throws Exception {
    CountDownLatch remoteCallStarted = new CountDownLatch(1);
    CountDownLatch allowRemoteCallToFinish = new CountDownLatch(1);
    BlockingRemoteService remoteService = new BlockingRemoteService(remoteCallStarted, allowRemoteCallToFinish);
    ErrorLogReportingAppender appender = new ErrorLogReportingAppender(
        remoteService,
        new ErrorLogCommandFactory(properties(), environment("authorization"))
    );
    LoggerContext loggerContext = new LoggerContext();
    appender.setContext(loggerContext);
    appender.setName("testErrorLogAppender");
    appender.start();
    appender.enableReporting();

    Thread warnThread = new Thread(() -> appender.doAppend(event(Level.WARN, "warn me", null)), "warn-thread");
    warnThread.start();
    assertTrue(remoteCallStarted.await(2, TimeUnit.SECONDS));

    FutureTask<Void> infoAppendTask = new FutureTask<>(() -> {
      appender.doAppend(event(Level.INFO, "skip me", null));
      return null;
    });
    Thread infoThread = new Thread(infoAppendTask, "info-thread");
    infoThread.start();

    assertDoesNotThrow(() -> infoAppendTask.get(500, TimeUnit.MILLISECONDS));

    allowRemoteCallToFinish.countDown();
    warnThread.join(2000);
    infoThread.join(2000);

    assertEquals(1, remoteService.commands.size());
    assertEquals("WARN", remoteService.commands.get(0).getLevel());
  }

  @Test
  void shouldIgnoreEventsUntilReportingIsEnabled() {
    RecordingRemoteService remoteService = new RecordingRemoteService();
    ErrorLogReportingAppender appender = new ErrorLogReportingAppender(
        remoteService,
        new ErrorLogCommandFactory(properties(), environment("authorization"))
    );
    LoggerContext loggerContext = new LoggerContext();
    appender.setContext(loggerContext);
    appender.setName("testErrorLogAppender");
    appender.start();

    appender.doAppend(event(Level.ERROR, "startup error", new IllegalStateException("booting")));

    assertEquals(0, remoteService.commands.size());

    appender.enableReporting();
    appender.doAppend(event(Level.ERROR, "runtime error", new IllegalStateException("ready")));

    assertEquals(1, remoteService.commands.size());
    assertEquals("runtime error", remoteService.commands.get(0).getMessage());
  }

  private ErrorLogMonitorProperties properties() {
    return new ErrorLogMonitorProperties();
  }

  private StandardEnvironment environment(final String applicationName) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("spring.application.name", applicationName)));
    return environment;
  }

  private LoggingEvent event(final Level level, final String message, final Throwable throwable) {
    LoggingEvent event = new LoggingEvent();
    event.setLevel(level);
    event.setLoggerName("org.simplepoint.tests.Logging");
    event.setThreadName("main");
    event.setMessage(message);
    event.setTimeStamp(1712100000000L);
    if (throwable != null) {
      event.setThrowableProxy(new ThrowableProxy(throwable));
    }
    return event;
  }

  private static class RecordingRemoteService implements ErrorLogRemoteService {
    protected final List<ErrorLogRecordCommand> commands = new ArrayList<>();

    @Override
    public void record(final ErrorLogRecordCommand command) {
      commands.add(command);
    }
  }

  private static final class BlockingRemoteService extends RecordingRemoteService {
    private final CountDownLatch started;
    private final CountDownLatch release;

    private BlockingRemoteService(final CountDownLatch started, final CountDownLatch release) {
      this.started = started;
      this.release = release;
    }

    @Override
    public void record(final ErrorLogRecordCommand command) {
      commands.add(command);
      started.countDown();
      try {
        release.await(2, TimeUnit.SECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting to release remote call", exception);
      }
    }
  }
}

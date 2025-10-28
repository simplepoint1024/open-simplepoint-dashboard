package org.simplepoint.example.plugin.controller;

import org.simplepoint.example.plugin.service.LoadPluginService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for handling plugin loading requests in the SimplePoint example plugin application.
 */
@RequestMapping("/plugin")
@RestController
public class LoadPluginController {
  /**
   * Service for loading plugins.
   */
  private final LoadPluginService loadPluginService;

  /**
   * Constructs a new LoadPluginController instance.
   *
   * @param loadPluginService the service for loading plugins
   */
  public LoadPluginController(LoadPluginService loadPluginService) {
    this.loadPluginService = loadPluginService;
  }

  /**
   * Endpoint to say hello from LoadPluginController.
   *
   * @return a greeting message
   */
  @RequestMapping("/hello")
  public String hello() {
    return "Hello from LoadPluginController";
  }
}

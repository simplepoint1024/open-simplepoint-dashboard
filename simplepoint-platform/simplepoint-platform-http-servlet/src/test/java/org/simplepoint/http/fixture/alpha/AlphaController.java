package org.simplepoint.http.fixture.alpha;

/** Isolated fixture for selected-module HTTP ownership and route checks. */
@org.springframework.web.bind.annotation.RestController
public class AlphaController {
  /** Identifies the module serving an otherwise identical endpoint. */
  @org.springframework.web.bind.annotation.GetMapping("/records")
  public java.util.Map<String, String> records() {
    return java.util.Map.of("module", "alpha");
  }
}

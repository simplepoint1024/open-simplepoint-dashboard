package org.simplepoint.standalone.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The host uses the shared HashRouter application; business forms keep their existing route URLs. */
@Controller
public class StandaloneDocumentController {
  /** Delivers the packaged application only after the document security chain authenticates it. */
  @GetMapping({"/", "/index.html"})
  public ResponseEntity<Resource> index() {
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore())
        .body(new ClassPathResource("static/index.html"));
  }
}

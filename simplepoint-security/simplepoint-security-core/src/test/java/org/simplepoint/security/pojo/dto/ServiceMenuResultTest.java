package org.simplepoint.security.pojo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.security.entity.TreeMenu;

class ServiceMenuResultTest {

  @Test
  void empty_constant_hasNoServicesOrRoutes() {
    assertThat(ServiceMenuResult.EMPTY.services()).isEmpty();
    assertThat(ServiceMenuResult.EMPTY.routes()).isEmpty();
    assertThat(ServiceMenuResult.EMPTY.entryPoint()).isNull();
  }

  @Test
  void of_setsDefaultEntryPoint() {
    ServiceMenuResult.ServiceEntry entry = ServiceMenuResult.ServiceEntry.of("common");
    ServiceMenuResult result = ServiceMenuResult.of(Set.of(entry), List.of());
    assertThat(result.entryPoint()).isEqualTo("/mf/mf-manifest.json");
    assertThat(result.services()).containsExactly(entry);
    assertThat(result.routes()).isEmpty();
  }

  @Test
  void of_withCustomEntryPoint() {
    ServiceMenuResult.ServiceEntry entry = ServiceMenuResult.ServiceEntry.of("dna", "/dna/entry");
    TreeMenu route = new TreeMenu();
    ServiceMenuResult result = ServiceMenuResult.of(Set.of(entry), List.of(route), "/custom/manifest.json");
    assertThat(result.entryPoint()).isEqualTo("/custom/manifest.json");
    assertThat(result.services()).containsExactly(entry);
    assertThat(result.routes()).containsExactly(route);
  }

  @Test
  void serviceEntry_of_withName() {
    ServiceMenuResult.ServiceEntry entry = ServiceMenuResult.ServiceEntry.of("myService");
    assertThat(entry.name()).isEqualTo("myService");
    assertThat(entry.entry()).isNull();
  }

  @Test
  void serviceEntry_of_withNameAndEntry() {
    ServiceMenuResult.ServiceEntry entry = ServiceMenuResult.ServiceEntry.of("myService", "http://localhost/mf.json");
    assertThat(entry.name()).isEqualTo("myService");
    assertThat(entry.entry()).isEqualTo("http://localhost/mf.json");
  }

  @Test
  void serviceEntry_equality() {
    ServiceMenuResult.ServiceEntry e1 = ServiceMenuResult.ServiceEntry.of("a", "b");
    ServiceMenuResult.ServiceEntry e2 = ServiceMenuResult.ServiceEntry.of("a", "b");
    assertThat(e1).isEqualTo(e2);
  }

  @Test
  void serviceMenuResult_constructorAndAccessors() {
    ServiceMenuResult.ServiceEntry entry = new ServiceMenuResult.ServiceEntry("svc", "/entry");
    ServiceMenuResult result = new ServiceMenuResult(Set.of(entry), List.of(), "/ep");
    assertThat(result.services()).containsExactly(entry);
    assertThat(result.entryPoint()).isEqualTo("/ep");
  }
}

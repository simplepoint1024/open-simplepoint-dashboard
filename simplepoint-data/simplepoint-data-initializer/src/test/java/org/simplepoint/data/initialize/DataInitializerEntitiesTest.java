package org.simplepoint.data.initialize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.initialize.entity.DataInitialize;
import org.simplepoint.data.initialize.entity.id.DataInitializeId;
import org.simplepoint.data.initialize.properties.DataInitializeProperties;
import org.simplepoint.data.initialize.properties.InitializerSettings;

class DataInitializerEntitiesTest {

  // ---- InitializerSettings ----

  @Test
  void initializerSettings_record_enabled() {
    InitializerSettings enabled = new InitializerSettings(true);
    InitializerSettings disabled = new InitializerSettings(false);
    assertThat(enabled.enabled()).isTrue();
    assertThat(disabled.enabled()).isFalse();
    assertThat(enabled).isNotEqualTo(disabled);
  }

  // ---- DataInitializeProperties ----

  @Test
  void dataInitializeProperties_defaultEnabled() {
    DataInitializeProperties props = new DataInitializeProperties();
    assertThat(props.isEnabled()).isTrue();
  }

  @Test
  void dataInitializeProperties_settersGetters() {
    DataInitializeProperties props = new DataInitializeProperties();
    props.setEnabled(false);
    props.setModule(Map.of("i18n", new InitializerSettings(true)));
    assertThat(props.isEnabled()).isFalse();
    assertThat(props.getModule()).containsKey("i18n");
    assertThat(props.getModule().get("i18n").enabled()).isTrue();
  }

  // ---- DataInitialize entity constants ----

  @Test
  void dataInitialize_statusConstants() {
    assertThat(DataInitialize.STATUS_INIT).isEqualTo(0);
    assertThat(DataInitialize.STATUS_DONE).isEqualTo(1);
    assertThat(DataInitialize.STATUS_FAIL).isEqualTo(2);
  }

  @Test
  void dataInitialize_settersGetters() {
    DataInitialize entity = new DataInitialize();
    entity.setServiceName("common");
    entity.setModuleName("i18n-messages");
    entity.setInitStatus(DataInitialize.STATUS_DONE);
    assertThat(entity.getServiceName()).isEqualTo("common");
    assertThat(entity.getModuleName()).isEqualTo("i18n-messages");
    assertThat(entity.getInitStatus()).isEqualTo(DataInitialize.STATUS_DONE);
  }

  // ---- DataInitializeId ----

  @Test
  void dataInitializeId_settersGetters() {
    DataInitializeId id = new DataInitializeId();
    id.setServiceName("common");
    id.setModuleName("init-module");
    assertThat(id.getServiceName()).isEqualTo("common");
    assertThat(id.getModuleName()).isEqualTo("init-module");
  }

  @Test
  void dataInitializeId_equality() {
    DataInitializeId id1 = new DataInitializeId();
    id1.setServiceName("common");
    id1.setModuleName("init");
    DataInitializeId id2 = new DataInitializeId();
    id2.setServiceName("common");
    id2.setModuleName("init");
    assertThat(id1).isEqualTo(id2);
    assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
  }
}

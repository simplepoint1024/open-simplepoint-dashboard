package org.simplepoint.plugin.i18n.api.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class I18nEntityTest {

  // ---- Countries ----

  @Test
  void countries_preInsert_setsEnabledWhenNull() {
    Countries c = new Countries();
    assertThat(c.getEnabled()).isNull();
    c.preInsert();
    assertThat(c.getEnabled()).isTrue();
  }

  @Test
  void countries_preInsert_doesNotOverrideExplicitFalse() {
    Countries c = new Countries();
    c.setEnabled(Boolean.FALSE);
    c.preInsert();
    assertThat(c.getEnabled()).isFalse();
  }

  @Test
  void countries_fieldsSetterGetter() {
    Countries c = new Countries();
    c.setNameEnglish("China");
    c.setIsoCode2("CN");
    c.setIsoCode3("CHN");
    c.setPhoneCode("+86");
    assertThat(c.getNameEnglish()).isEqualTo("China");
    assertThat(c.getIsoCode2()).isEqualTo("CN");
    assertThat(c.getIsoCode3()).isEqualTo("CHN");
    assertThat(c.getPhoneCode()).isEqualTo("+86");
  }

  // ---- Language ----

  @Test
  void language_preInsert_setsEnabledWhenNull() {
    Language lang = new Language();
    assertThat(lang.getEnabled()).isNull();
    lang.preInsert();
    assertThat(lang.getEnabled()).isTrue();
  }

  @Test
  void language_preInsert_doesNotOverrideExplicitTrue() {
    Language lang = new Language();
    lang.setEnabled(true);
    lang.preInsert();
    assertThat(lang.getEnabled()).isTrue();
  }

  @Test
  void language_fieldsSetterGetter() {
    Language lang = new Language();
    lang.setCode("zh-CN");
    lang.setLocale("zh_CN");
    lang.setNameEnglish("Chinese");
    assertThat(lang.getCode()).isEqualTo("zh-CN");
    assertThat(lang.getLocale()).isEqualTo("zh_CN");
    assertThat(lang.getNameEnglish()).isEqualTo("Chinese");
  }

  // ---- TimeZone ----

  @Test
  void timeZone_prePersist_setsEnabledWhenNull() {
    TimeZone tz = new TimeZone();
    assertThat(tz.getEnabled()).isNull();
    tz.prePersist();
    assertThat(tz.getEnabled()).isTrue();
  }

  @Test
  void timeZone_fieldsSetterGetter() {
    TimeZone tz = new TimeZone();
    tz.setCode("Asia/Shanghai");
    tz.setUtcOffset("+08:00");
    tz.setCountryCode("CN");
    assertThat(tz.getCode()).isEqualTo("Asia/Shanghai");
    assertThat(tz.getUtcOffset()).isEqualTo("+08:00");
    assertThat(tz.getCountryCode()).isEqualTo("CN");
  }

  // ---- Namespace ----

  @Test
  void namespace_constructorSetsFields() {
    Namespace ns = new Namespace("Platform", "platform");
    assertThat(ns.getName()).isEqualTo("Platform");
    assertThat(ns.getCode()).isEqualTo("platform");
  }

  @Test
  void namespace_setterGetter() {
    Namespace ns = new Namespace("X", "x");
    ns.setModule("core");
    ns.setDescription("Core namespace");
    assertThat(ns.getModule()).isEqualTo("core");
    assertThat(ns.getDescription()).isEqualTo("Core namespace");
  }

  // ---- Region ----

  @Test
  void region_setterGetter() {
    Region r = new Region();
    r.setNameEnglish("Beijing");
    r.setCode("BJ");
    r.setCountryCode("CN");
    r.setLevel("province");
    assertThat(r.getNameEnglish()).isEqualTo("Beijing");
    assertThat(r.getCode()).isEqualTo("BJ");
    assertThat(r.getCountryCode()).isEqualTo("CN");
    assertThat(r.getLevel()).isEqualTo("province");
  }
}

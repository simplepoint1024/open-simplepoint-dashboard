package org.simplepoint.api.locale;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalizedTextsTest {
  @Test void canonicalizesAndResolvesOnlyExplicitOverrides() {
    var value = LocalizedTexts.validate(new LocalizedText("客户", "zh_CN",
        Map.of("en", "Customer", "en_GB", "Client", "zh_Hant_TW", "客戶")), 100, 32);
    assertEquals("zh-CN", value.defaultLocale());
    assertEquals("Client", LocalizedTexts.override(value, "en-GB"));
    assertEquals("Customer", LocalizedTexts.override(value, "en-US"));
    assertEquals("客戶", LocalizedTexts.override(value, "zh-Hant-TW"));
    assertNull(LocalizedTexts.override(value, "ja-JP"));
    assertEquals("sl-rozaj", LocalizedTexts.locale("sl-rozaj"));
  }
  @Test void validatesUnicodeBoundsAndRejectsAmbiguousOrUnboundedLanguages() {
    String emoji = "😀".repeat(100);
    assertEquals(emoji, LocalizedTexts.validate(new LocalizedText(emoji, "zh-CN", Map.of()),100,32).defaultValue());
    assertThrows(IllegalArgumentException.class, () -> LocalizedTexts.validate(new LocalizedText(emoji+"😀", "zh-CN", Map.of()),100,32));
    assertThrows(IllegalArgumentException.class, () -> LocalizedTexts.validate(new LocalizedText("名称", "zh-CN", Map.of("en-US","A","en_US","B")),100,32));
    assertThrows(IllegalArgumentException.class, () -> LocalizedTexts.validate(new LocalizedText("名称", "zh-CN", Map.of("en"," ")),100,32));
    assertThrows(IllegalArgumentException.class, () -> LocalizedTexts.validate(new LocalizedText("名称", "zh-CN", Map.of("en","A","ja","B")),100,1));
    for (String tag : new String[]{"", "und", "en-u-ca-gregory", "x-private", "not a tag"})
      assertThrows(IllegalArgumentException.class, () -> LocalizedTexts.locale(tag), tag);
  }
}

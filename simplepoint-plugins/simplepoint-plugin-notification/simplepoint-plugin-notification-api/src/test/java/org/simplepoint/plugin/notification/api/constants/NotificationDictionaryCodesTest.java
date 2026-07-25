package org.simplepoint.plugin.notification.api.constants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.schema.DictionaryField;
import org.simplepoint.plugin.notification.api.entity.SystemNotification;

class NotificationDictionaryCodesTest {

  @Test
  void constantsRemainStable() {
    assertEquals("notification.category", NotificationDictionaryCodes.CATEGORY);
    assertEquals("notification.priority", NotificationDictionaryCodes.PRIORITY);
    assertEquals("notification.audience-type", NotificationDictionaryCodes.AUDIENCE_TYPE);
    assertEquals("notification.status", NotificationDictionaryCodes.STATUS);
  }

  @Test
  void notificationEnumFieldsExposeDictionaryMetadata() throws Exception {
    assertDictionary("category", NotificationDictionaryCodes.CATEGORY);
    assertDictionary("priority", NotificationDictionaryCodes.PRIORITY);
    assertDictionary("audienceType", NotificationDictionaryCodes.AUDIENCE_TYPE);
    assertDictionary("status", NotificationDictionaryCodes.STATUS);
  }

  private static void assertDictionary(
      final String fieldName,
      final String expectedCode
  ) throws Exception {
    Field field = SystemNotification.class.getDeclaredField(fieldName);
    DictionaryField dictionaryField = field.getAnnotation(DictionaryField.class);
    assertEquals(expectedCode, dictionaryField.value());
  }
}

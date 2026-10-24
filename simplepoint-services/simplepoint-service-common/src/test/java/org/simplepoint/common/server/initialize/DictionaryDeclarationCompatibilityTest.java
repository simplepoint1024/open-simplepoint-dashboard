package org.simplepoint.common.server.initialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryDeclarationWriter;
import org.simplepoint.plugin.rbac.tenant.service.support.DictionaryCatalogWriteBoundary;

/** Golden metadata captured from the installed Common artifact before declaration extraction. */
class DictionaryDeclarationCompatibilityTest {
  @Test
  void createAndRefreshRetainAllLegacyMetadataAndBootstrapKeys() throws Exception {
    var mapper = new ObjectMapper();
    var expected =
        mapper.readTree(getClass().getResourceAsStream("/bootstrap/legacy-dictionaries.json"));
    for (boolean refresh : List.of(false, true)) {
      var output = new ArrayList<Map<String, Object>>();
      for (String[] entry :
          List.of(
              new String[] {
                "ExternalIdentityProviderDictionaryInitializer",
                "externalIdentityProviderDictionaryContribution"
              },
              new String[] {
                "NotificationDictionaryInitializer", "notificationDictionaryBootstrapContribution"
              })) {
        var dictionaries = new ArrayList<Dictionary>();
        var items = new ArrayList<DictionaryItem>();
        var loader = getClass().getClassLoader();
        var dictionaryService =
            (DictionaryService)
                Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] {DictionaryService.class},
                    (proxy, method, values) -> {
                      if (method.getName().equals("findAll")) {
                        if (!refresh) {
                          return List.of();
                        }
                        var old = new Dictionary();
                        old.setCode((String) ((Map<?, ?>) values[0]).get("code"));
                        old.setName("old");
                        old.setEnabled(false);
                        return List.of(old);
                      }
                      if (method.getName().equals(refresh ? "modifyById" : "create")) {
                        dictionaries.add((Dictionary) values[0]);
                        return values[0];
                      }
                      throw new AssertionError(method.getName());
                    });
        var itemService =
            (DictionaryItemService)
                Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] {DictionaryItemService.class},
                    (proxy, method, values) -> {
                      if (method.getName().equals("findAll")) {
                        if (!refresh) {
                          return List.of();
                        }
                        String code = (String) ((Map<?, ?>) values[0]).get("dictionaryCode");
                        var oldItems = new ArrayList<DictionaryItem>();
                        for (var contribution : expected) {
                          for (var dictionary : contribution.get("dictionaries")) {
                            if (!dictionary.get("code").asText().equals(code)) {
                              continue;
                            }
                            for (var item : dictionary.get("items")) {
                              var old = new DictionaryItem();
                              old.setDictionaryCode(code);
                              old.setValue(item.get("value").asText());
                              old.setName("old");
                              old.setEnabled(false);
                              oldItems.add(old);
                            }
                          }
                        }
                        return oldItems;
                      }
                      if (method.getName().equals(refresh ? "modifyById" : "create")) {
                        items.add((DictionaryItem) values[0]);
                        return values[0];
                      }
                      throw new AssertionError(method.getName());
                    });
        var type = Class.forName("org.simplepoint.common.server.initialize." + entry[0]);
        var factory =
            (PlatformBootstrapContribution)
                type.getMethod(entry[1], DictionaryDeclarationWriter.class)
                    .invoke(
                        type.getConstructor().newInstance(),
                        new DictionaryDeclarationWriter(
                            org.mockito.Mockito.mock(DictionaryCatalogWriteBoundary.class),
                            dictionaryService,
                            itemService,
                            org.mockito.Mockito.mock(
                                org.simplepoint.plugin.rbac.tenant.service.initialize
                                    .DictionaryRegistrationStore.class)));
        var contribution = factory.contribution();
        assertEquals("dictionary", contribution.contributionType());
        contribution.action().run();
        assertTrue(dictionaries.stream().allMatch(d -> Boolean.TRUE.equals(d.getEnabled())));
        assertTrue(items.stream().allMatch(i -> Boolean.TRUE.equals(i.getEnabled())));
        var record = new LinkedHashMap<String, Object>();
        record.put("moduleCode", contribution.moduleCode());
        record.put("contributionKey", contribution.contributionKey());
        record.put("version", contribution.version());
        record.put("order", contribution.order());
        record.put("checksum", contribution.checksum());
        record.put(
            "dictionaries",
            dictionaries.stream()
                .map(
                    d ->
                        Map.of(
                            "code",
                            d.getCode(),
                            "name",
                            d.getName(),
                            "description",
                            d.getDescription(),
                            "sort",
                            d.getSort(),
                            "items",
                            items.stream()
                                .filter(i -> i.getDictionaryCode().equals(d.getCode()))
                                .map(
                                    i ->
                                        Map.of(
                                            "value",
                                            i.getValue(),
                                            "name",
                                            i.getName(),
                                            "i18nKey",
                                            i.getI18nKey(),
                                            "description",
                                            i.getDescription(),
                                            "sort",
                                            i.getSort()))
                                .toList()))
                .toList());
        output.add(record);
      }
      assertEquals(expected, mapper.valueToTree(output), "refresh=" + refresh);
    }
  }
}

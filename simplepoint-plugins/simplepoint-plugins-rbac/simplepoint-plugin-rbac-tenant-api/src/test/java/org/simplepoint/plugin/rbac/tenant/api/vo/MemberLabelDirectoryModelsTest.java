package org.simplepoint.plugin.rbac.tenant.api.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Label;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Page;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Request;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;

class MemberLabelDirectoryModelsTest {
  private final Revision revision = new Revision("584fed19-82b1-4fe6-9273-8dd28bfd534c", 9);

  @Test
  void boundsRequestsAndRequiresRevisionOnEveryContinuation() {
    assertThrows(IllegalArgumentException.class, () -> new Request("tenant", null, "user", 1));
    assertThrows(IllegalArgumentException.class, () -> new Request("tenant", null, null, 0));
    assertThrows(IllegalArgumentException.class, () -> new Request("tenant", null, null, 201));
    assertThrows(IllegalArgumentException.class, () -> new Request(" tenant", null, null, 1));
    assertThrows(IllegalArgumentException.class, () -> new Request("***", null, null, 1));
    assertThrows(IllegalArgumentException.class, () -> new Revision("1-1-1-1-1", 0));
    assertThrows(IllegalArgumentException.class, () -> new Revision(revision.epoch(), -1));
    var request = new Request("😀".repeat(255), revision, "user", 200);
    assertEquals(200, request.limit());
    assertThrows(
        IllegalArgumentException.class, () -> new Request("😀".repeat(256), null, null, 1));
  }

  @Test
  void forbidsPartialRestartAndAmbiguousCursorsAndDuplicateRows() {
    var rows = List.of(new Label("a", "张三"));
    assertThrows(
        IllegalArgumentException.class, () -> new Page("t", revision, true, false, rows, null));
    assertThrows(
        IllegalArgumentException.class, () -> new Page("t", revision, false, false, rows, null));
    assertThrows(
        IllegalArgumentException.class, () -> new Page("t", revision, false, true, rows, "b"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Page("t", revision, false, true, List.of(rows.getFirst(), rows.getFirst()), null));
    var mutable = new java.util.ArrayList<>(rows);
    var page = new Page("t", revision, false, true, mutable, "a");
    mutable.clear();
    assertEquals(rows, page.labels());
    assertThrows(UnsupportedOperationException.class, () -> page.labels().clear());
  }

  @Test
  void roundTripsNullNamesUnicodeAndExactLongRevisions() throws Exception {
    var mapper = JsonMapper.builder().build();
    var page =
        new Page(
            "tenant",
            new Revision(revision.epoch(), Long.MAX_VALUE),
            false,
            true,
            List.of(new Label("a", "😀".repeat(767)), new Label("b", null)),
            null);
    assertEquals(page, mapper.readValue(mapper.writeValueAsBytes(page), Page.class));
    var request = new Request("tenant", revision, "a", 200);
    assertEquals(request, mapper.readValue(mapper.writeValueAsBytes(request), Request.class));
    assertThrows(IllegalArgumentException.class, () -> new Label("a", "😀".repeat(768)));
  }
}

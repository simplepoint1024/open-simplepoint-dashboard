package org.simplepoint.plugin.auditing.redis.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryDetail;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryType;
import org.simplepoint.plugin.auditing.redis.api.model.RedisValueUpsertCommand;
import org.simplepoint.plugin.auditing.redis.service.query.RedisEntryFieldPolicy;
import org.simplepoint.plugin.auditing.redis.service.query.RedisEntryPreviewReader;
import org.simplepoint.plugin.auditing.redis.service.query.RedisEntryQueryService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class RedisMonitoringServiceImplTest {
  @Mock StringRedisTemplate redis;
  @Mock RedisEntryPreviewReader previews;
  @Mock RedisEntryQueryService queries;
  @InjectMocks RedisMonitoringServiceImpl service;

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  private RedisValueUpsertCommand command(String key, String value) {
    return new RedisValueUpsertCommand(key, value, null, true);
  }

  private RedisEntryDetail detail(String key) {
    return new RedisEntryDetail(
        key, RedisEntryType.STRING, null, true, 5L, true, "hello", false, false, "UTF-8");
  }

  private void policy(String field, String access) {
    var context = new AuthorizationContext();
    context.setFieldPermissions(Map.of(field, access));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
  }

  @Test
  void delete_emptyKeys_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.delete(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void delete_nullCollection_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.delete(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void delete_blankKeys_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.delete(List.of("  ", ""))).hasMessageContaining("blank");
    verifyNoInteractions(redis, queries);
  }

  @Test
  void delete_validKeys_delegatesToTemplate() {
    service.delete(List.of(" key,1 ", "key2", "key2"));
    verify(redis).delete(List.of(" key,1 ", "key2"));
    verify(queries).invalidate();
  }

  @Test
  void create_nullCommand_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.create(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void create_nullValue_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.create(command("key", null))).hasMessageContaining("value");
  }

  @Test
  void update_nullCommand_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.update(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void create_blankKey_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.create(command("", "value"))).hasMessageContaining("blank");
  }

  @Test
  void create_nullKey_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.create(command(null, "value"))).hasMessageContaining("blank");
  }

  @Test
  void detail_keyNotFound_throwsNoSuchElementException() {
    when(previews.read("key", true))
        .thenThrow(new NoSuchElementException("Redis key does not exist"));
    assertThatThrownBy(() -> service.detail("key")).isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void detail_stringType_returnsDetail() {
    when(previews.read("key", true)).thenReturn(detail("key"));
    var result = service.detail("key");
    assertThat(result.getValue()).isEqualTo("hello");
    assertThat(result.getEditable()).isTrue();
  }

  @Test
  void detail_persistentKey_returnsIsPersistentTrue() {
    when(previews.read("key", true)).thenReturn(detail("key"));
    assertThat(service.detail("key").getPersistent()).isTrue();
  }

  @Test
  void create_keyAlreadyExists_throwsIllegalArgument() {
    when(redis.hasKey("key")).thenReturn(true);
    assertThatThrownBy(() -> service.create(command("key", "value")))
        .hasMessageContaining("already exists");
    verifyNoInteractions(previews, queries);
  }

  @Test
  void create_persistentKey_setsValueWithoutTtl() {
    var values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(previews.read("key", true)).thenReturn(detail("key"));
    assertThat(service.create(command("key", "hello")).getValue()).isEqualTo("hello");
    verify(values).set("key", "hello");
    verify(queries).invalidate();
  }

  @Test
  void update_keyNotFound_throwsNoSuchElementException() {
    when(previews.read("key", true)).thenThrow(new NoSuchElementException());
    assertThatThrownBy(() -> service.update(command("key", "value")))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void update_nonStringType_throwsIllegalArgument() {
    var row = detail("key");
    row.setType(RedisEntryType.HASH);
    when(previews.read("key", true)).thenReturn(row);
    assertThatThrownBy(() -> service.update(command("key", "value")))
        .hasMessageContaining("string");
    verifyNoInteractions(redis, queries);
  }

  @Test
  void update_stringType_updatesAndReturnsDetail() {
    var values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(previews.read("key", true)).thenAnswer(invocation -> detail("key"));
    service.update(command("key", "updated"));
    verify(values).set("key", "updated");
    verify(queries).invalidate();
  }

  @Test
  void detail_blankKey_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.detail("")).hasMessageContaining("blank");
  }

  @Test
  void hiddenValuesAreNotReadAndLegacyDetailProjectionCannotBypassSummaryPolicy() {
    policy("RedisEntrySummary#valuePreview", "MASKED");
    var row = detail("key");
    row.setValue(null);
    row.setEditable(false);
    when(previews.read("key", false)).thenReturn(row);
    var result = RedisEntryFieldPolicy.project(service.detail("key"));
    assertThat(result).containsEntry("value", "***").containsEntry("editable", "***");
    assertThatThrownBy(() -> service.update(command("key", "changed")))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(redis, queries);
  }

  @Test
  void hiddenIdentityPreventsReadWriteAndDeleteBeforeRedisAccess() {
    for (String owner :
        List.of("RedisEntrySummary", "RedisEntryDetail", "RedisValueUpsertCommand")) {
      policy(owner + "#key", "HIDDEN");
      assertThatThrownBy(() -> service.detail("key")).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.create(command("key", "value")))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> service.delete(List.of("key")))
          .isInstanceOf(AccessDeniedException.class);
    }
    verifyNoInteractions(redis, previews, queries);
  }

  @Test
  void visibleOnlyTypeAllowsValueEditsButReadonlyValueOrLifetimeDoesNot() {
    policy("RedisEntryDetail#type", "VISIBLE");
    assertThat(RedisEntryFieldPolicy.canEdit()).isTrue();
    for (String field : List.of("value", "ttlSeconds", "persistent")) {
      policy("RedisValueUpsertCommand#" + field, "VISIBLE");
      assertThatThrownBy(() -> service.update(command("key", "value")))
          .isInstanceOf(AccessDeniedException.class);
    }
    verifyNoInteractions(redis, previews, queries);
  }

  @Test
  void oversizedInvalidUtf8AndInvalidTtlCommandsFailBeforeRedisAccess() {
    for (String value : List.of("键".repeat(90000), String.valueOf((char) 0xd800))) {
      assertThatThrownBy(() -> service.create(command("key", value)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (Long ttl : new Long[] {null, 0L, -1L}) {
      assertThatThrownBy(
              () -> service.create(new RedisValueUpsertCommand("key", "value", ttl, false)))
          .hasMessageContaining("ttlSeconds");
    }
    assertThatThrownBy(() -> service.delete(java.util.Collections.nCopies(101, "key")))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(redis, previews, queries);
  }

  @Test
  void sampledOrBinaryExistingValuesCannotBeOverwritten() {
    var row = detail("key");
    row.setEditable(false);
    row.setTruncated(true);
    when(previews.read("key", true)).thenReturn(row);
    assertThatThrownBy(() -> service.update(command("key", "small")))
        .hasMessageContaining("complete UTF-8");
    verifyNoInteractions(redis, queries);
  }
}

package org.simplepoint.plugin.auditing.redis.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryDetail;
import org.simplepoint.plugin.auditing.redis.api.model.RedisEntryType;
import org.simplepoint.plugin.auditing.redis.api.model.RedisValueUpsertCommand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisMonitoringServiceImplTest {

  @Mock
  StringRedisTemplate stringRedisTemplate;

  @Mock
  ObjectMapper objectMapper;

  @InjectMocks
  RedisMonitoringServiceImpl service;

  // ── delete ────────────────────────────────────────────────────────────────

  @Test
  void delete_emptyKeys_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.delete(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At least one");
  }

  @Test
  void delete_nullCollection_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.delete(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At least one");
  }

  @Test
  void delete_blankKeys_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.delete(List.of("  ", "")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At least one");
  }

  @Test
  void delete_validKeys_delegatesToTemplate() {
    service.delete(List.of("key1", "key2"));
    verify(stringRedisTemplate).delete(List.of("key1", "key2"));
  }

  // ── normalizeCommand ─────────────────────────────────────────────────────

  @Test
  void create_nullCommand_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.create(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }

  @Test
  void create_nullValue_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.create(new RedisValueUpsertCommand("key", null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("value");
  }

  @Test
  void update_nullCommand_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.update(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }

  // ── normalizeKey ──────────────────────────────────────────────────────────

  @Test
  void create_blankKey_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.create(new RedisValueUpsertCommand("  ", "val", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void create_nullKey_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.create(new RedisValueUpsertCommand(null, "val", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  // ── detail ────────────────────────────────────────────────────────────────

  @Test
  void detail_keyNotFound_throwsNoSuchElementException() {
    when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(null);

    assertThatThrownBy(() -> service.detail("mykey"))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("mykey");
  }

  @Test
  void detail_stringType_returnsDetail() {
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(DataType.STRING);
    when(stringRedisTemplate.getExpire(eq("mykey"), any(TimeUnit.class))).thenReturn(60L);
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.size("mykey")).thenReturn(5L);
    when(valueOps.get("mykey")).thenReturn("hello");

    RedisEntryDetail detail = service.detail("mykey");

    assertThat(detail.getKey()).isEqualTo("mykey");
    assertThat(detail.getType()).isEqualTo(RedisEntryType.STRING);
    assertThat(detail.getValue()).isEqualTo("hello");
    assertThat(detail.getEditable()).isTrue();
  }

  @Test
  void detail_persistentKey_returnsIsPersistentTrue() {
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(DataType.STRING);
    when(stringRedisTemplate.getExpire(eq("pkey"), any(TimeUnit.class))).thenReturn(-1L);
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.size("pkey")).thenReturn(3L);
    when(valueOps.get("pkey")).thenReturn("abc");

    RedisEntryDetail detail = service.detail("pkey");

    assertThat(detail.getPersistent()).isTrue();
    assertThat(detail.getTtlSeconds()).isNull();
  }

  // ── create ────────────────────────────────────────────────────────────────

  @Test
  void create_keyAlreadyExists_throwsIllegalArgument() {
    when(stringRedisTemplate.hasKey("mykey")).thenReturn(true);

    assertThatThrownBy(() -> service.create(new RedisValueUpsertCommand("mykey", "val", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  void create_persistentKey_setsValueWithoutTtl() {
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    when(stringRedisTemplate.hasKey("newkey")).thenReturn(false);
    when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(DataType.STRING);
    when(stringRedisTemplate.getExpire(eq("newkey"), any(TimeUnit.class))).thenReturn(-1L);
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.size("newkey")).thenReturn(5L);
    when(valueOps.get("newkey")).thenReturn("hello");

    RedisEntryDetail result = service.create(new RedisValueUpsertCommand("newkey", "hello", null, true));

    assertThat(result.getKey()).isEqualTo("newkey");
    verify(valueOps).set("newkey", "hello");
  }

  // ── update ────────────────────────────────────────────────────────────────

  @Test
  void update_keyNotFound_throwsNoSuchElementException() {
    when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(null);

    assertThatThrownBy(() -> service.update(new RedisValueUpsertCommand("mykey", "val", null, null)))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("mykey");
  }

  @Test
  void update_nonStringType_throwsIllegalArgument() {
    when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(DataType.HASH);

    assertThatThrownBy(() -> service.update(new RedisValueUpsertCommand("mykey", "val", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("string");
  }

  @Test
  void update_stringType_updatesAndReturnsDetail() {
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(DataType.STRING);
    when(stringRedisTemplate.getExpire(eq("mykey"), any(TimeUnit.class))).thenReturn(300L);
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    when(valueOps.size("mykey")).thenReturn(7L);
    when(valueOps.get("mykey")).thenReturn("updated");

    RedisEntryDetail result = service.update(new RedisValueUpsertCommand("mykey", "updated", null, true));

    assertThat(result.getKey()).isEqualTo("mykey");
    assertThat(result.getType()).isEqualTo(RedisEntryType.STRING);
    verify(valueOps).set("mykey", "updated");
  }

  // ── limit ─────────────────────────────────────────────────────────────────

  @Test
  void limit_noKeys_returnsEmptyPage() {
    when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(null);

    Page<org.simplepoint.plugin.auditing.redis.api.model.RedisEntrySummary> page =
        service.limit(null, null, PageRequest.of(0, 10));

    assertThat(page.getContent()).isEmpty();
    assertThat(page.getTotalElements()).isZero();
  }

  @Test
  void limit_nullPageable_returnsUnpagedResult() {
    when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(null);

    Page<org.simplepoint.plugin.auditing.redis.api.model.RedisEntrySummary> page =
        service.limit("*", null, null);

    assertThat(page.getContent()).isEmpty();
  }

  @Test
  void limit_invalidTypeFilter_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.limit(null, "INVALID_TYPE", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("INVALID_TYPE");
  }

  @Test
  void detail_blankKey_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.detail("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }
}

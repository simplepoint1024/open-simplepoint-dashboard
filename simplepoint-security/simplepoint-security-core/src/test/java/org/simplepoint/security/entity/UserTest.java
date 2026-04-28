package org.simplepoint.security.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class UserTest {

  @Test
  void prePersist_setsDefaultsWhenAllFieldsNull() {
    User user = new User();
    user.prePersist();
    assertThat(user.getEnabled()).isTrue();
    assertThat(user.getAccountNonExpired()).isTrue();
    assertThat(user.getAccountNonLocked()).isTrue();
    assertThat(user.getCredentialsNonExpired()).isTrue();
    assertThat(user.superAdmin()).isFalse();
  }

  @Test
  void prePersist_doesNotOverrideExistingValues() {
    User user = new User();
    user.setEnabled(false);
    user.setAccountNonExpired(false);
    user.setAccountNonLocked(false);
    user.setCredentialsNonExpired(false);
    user.setSuperAdmin(true);
    user.prePersist();
    assertThat(user.getEnabled()).isFalse();
    assertThat(user.getAccountNonExpired()).isFalse();
    assertThat(user.getAccountNonLocked()).isFalse();
    assertThat(user.getCredentialsNonExpired()).isFalse();
    assertThat(user.superAdmin()).isTrue();
  }

  @Test
  void getUsername_returnsId() {
    User user = new User();
    user.setId("user-001");
    assertThat(user.getUsername()).isEqualTo("user-001");
  }

  @Test
  void superAdmin_returnsFalse_whenNull() {
    User user = new User();
    assertThat(user.superAdmin()).isFalse();
  }

  @Test
  void superAdmin_returnsTrue_whenTrue() {
    User user = new User();
    user.setSuperAdmin(true);
    assertThat(user.superAdmin()).isTrue();
  }

  @Test
  void user_passwordAndOtherFields() {
    User user = new User();
    user.setPassword("secret");
    user.setNickname("Alice");
    user.setPhoneNumber("13800138000");
    user.setEmail("alice@example.com");
    user.setTwoFactorEnabled(true);
    user.setTwoFactorSecret("TOTP_SECRET");
    assertThat(user.getPassword()).isEqualTo("secret");
    assertThat(user.getNickname()).isEqualTo("Alice");
    assertThat(user.getPhoneNumber()).isEqualTo("13800138000");
    assertThat(user.getEmail()).isEqualTo("alice@example.com");
    assertThat(user.getTwoFactorEnabled()).isTrue();
    assertThat(user.getTwoFactorSecret()).isEqualTo("TOTP_SECRET");
  }

  @Test
  void user_authorities_defaultEmpty() {
    User user = new User();
    assertThat(user.getAuthorities()).isEmpty();
  }

  @Test
  void user_authorities_canBeSet() {
    User user = new User();
    user.setAuthorities(Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
    assertThat(user.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMIN");
  }
}

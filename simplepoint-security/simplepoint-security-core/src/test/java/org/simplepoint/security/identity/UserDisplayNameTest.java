package org.simplepoint.security.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.simplepoint.security.entity.User;

class UserDisplayNameTest {
  @Test
  void explicitStructuredAndEmailSourcesHaveOnePriorityWithoutInventingNames() {
    var user = new User();
    user.setId("internal-id");
    user.setNickname("nickname-is-not-a-name");
    user.setPhoneNumber("13800000000");
    assertThat(UserDisplayName.display(user)).isNull();
    user.setEmail(" person@example.test ");
    assertThat(UserDisplayName.display(user)).isEqualTo("person@example.test");
    assertThat(UserDisplayName.fullName(user)).isNull();
    user.setFamilyName(" Zhang ");
    user.setMiddleName("   ");
    user.setGivenName(" San ");
    assertThat(UserDisplayName.display(user)).isEqualTo("Zhang San");
    user.setName(" 张三 ");
    assertThat(UserDisplayName.display(user)).isEqualTo("张三");
    user.setName(" ");
    assertThat(UserDisplayName.display(user)).isEqualTo("Zhang San");
    assertThat(UserDisplayName.display(null)).isNull();
  }

  @Test
  void protectedFieldsNeverAppearInPermissionAwareHql() {
    assertThat(UserDisplayName.hql(field -> field.equals("email")))
        .contains("u.email")
        .doesNotContain(
            "u.name", "u.familyName", "u.middleName", "u.givenName", "u.id", "nickname");
    assertThat(UserDisplayName.hql(field -> false)).doesNotContain("u.");
  }
}

package org.simplepoint.plugin.dna.jdbc.driver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class DnaJdbcDataSourceTest {

  @Test
  void defaultConstructorSetsNullUrl() {
    DnaJdbcDataSource ds = new DnaJdbcDataSource();
    assertThat(ds.getUrl()).isNull();
    assertThat(ds.getUser()).isNull();
    assertThat(ds.getPassword()).isNull();
  }

  @Test
  void urlConstructorSetsUrl() {
    DnaJdbcDataSource ds = new DnaJdbcDataSource("jdbc:simplepoint:dna://host:1234");
    assertThat(ds.getUrl()).isEqualTo("jdbc:simplepoint:dna://host:1234");
  }

  @Test
  void getConnectionThrowsWhenUrlNotSet() {
    DnaJdbcDataSource ds = new DnaJdbcDataSource();
    assertThatThrownBy(ds::getConnection)
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("URL has not been set");
  }

  @Test
  void settersAndGetters() {
    DnaJdbcDataSource ds = new DnaJdbcDataSource();
    ds.setUrl("jdbc:simplepoint:dna://host:15432");
    ds.setUser("alice");
    ds.setPassword("secret");
    ds.setLoginTimeout(30);
    assertThat(ds.getUrl()).isEqualTo("jdbc:simplepoint:dna://host:15432");
    assertThat(ds.getUser()).isEqualTo("alice");
    assertThat(ds.getPassword()).isEqualTo("secret");
    assertThat(ds.getLoginTimeout()).isEqualTo(30);
  }

  @Test
  void isWrapperForSelf() throws SQLException {
    DnaJdbcDataSource ds = new DnaJdbcDataSource();
    assertThat(ds.isWrapperFor(DnaJdbcDataSource.class)).isTrue();
    assertThat(ds.isWrapperFor(javax.sql.DataSource.class)).isTrue();
    assertThat(ds.isWrapperFor(String.class)).isFalse();
  }

  @Test
  void unwrapSelf() throws SQLException {
    DnaJdbcDataSource ds = new DnaJdbcDataSource();
    assertThat(ds.unwrap(DnaJdbcDataSource.class)).isSameAs(ds);
  }

  @Test
  void unwrapThrowsForUnrelatedType() {
    DnaJdbcDataSource ds = new DnaJdbcDataSource();
    assertThatThrownBy(() -> ds.unwrap(String.class))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("Cannot unwrap");
  }
}

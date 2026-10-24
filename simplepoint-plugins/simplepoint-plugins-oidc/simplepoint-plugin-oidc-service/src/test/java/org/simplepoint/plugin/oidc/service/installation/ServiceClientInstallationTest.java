package org.simplepoint.plugin.oidc.service.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleClientPlan;
import org.simplepoint.plugin.oidc.service.repository.impl.RegisteredClientRepositoryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

class ServiceClientInstallationTest {
  private final RegisteredClientRepositoryImpl clients = mock(RegisteredClientRepositoryImpl.class);
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
  private final ServiceClientInstallation installation = new ServiceClientInstallation(clients, passwords);

  @Test
  void freshClientsHaveOnlyTheRequiredMachineGrantAndExactRetriesDoNotRewriteSecrets() {
    var credential = credential("alpha");
    assertThat(installation.installed(List.of(credential))).isFalse();
    var saved = org.mockito.ArgumentCaptor.forClass(RegisteredClient.class);
    installation.provision(List.of(credential));
    verify(clients).save(saved.capture());
    var raw = saved.getValue();
    assertThat(raw.getScopes()).containsExactly("service-router.invoke");
    assertThat(raw.getAuthorizationGrantTypes()).extracting("value").containsExactly("client_credentials");
    assertThat(raw.getClientAuthenticationMethods()).extracting("value").containsExactly("client_secret_basic");
    assertThat(raw.getRedirectUris()).isEmpty();
    var durable = RegisteredClient.from(raw).clientSecret(passwords.encode(raw.getClientSecret())).build();
    when(clients.findByClientId(credential.clientId())).thenReturn(durable);
    assertThat(installation.installed(List.of(credential))).isTrue();
    installation.provision(List.of(credential));
    verify(clients, times(1)).save(any());
    assertThat(credential.toString()).doesNotContain(credential.secret());
  }

  @Test
  void differentSecretOrModifiedGrantRejectsWithoutAdoptingExistingConfiguration() {
    var credential = credential("alpha");
    var saved = org.mockito.ArgumentCaptor.forClass(RegisteredClient.class);
    installation.provision(List.of(credential));
    verify(clients).save(saved.capture());
    var durable = RegisteredClient.from(saved.getValue()).clientSecret(passwords.encode(credential.secret())).build();
    when(clients.findByClientId(credential.clientId())).thenReturn(durable);
    var wrong = new ServiceClientInstallation.Credential(credential.operation(), credential.module(),
        credential.clientId(), "b".repeat(43));
    assertThatThrownBy(() -> installation.provision(List.of(wrong))).isInstanceOf(IllegalStateException.class);
    when(clients.findByClientId(credential.clientId())).thenReturn(RegisteredClient.from(durable).scope("extra").build());
    assertThatThrownBy(() -> installation.installed(List.of(credential))).isInstanceOf(IllegalStateException.class);
    verify(clients, times(1)).save(any());
  }

  @Test
  void mixedDeploymentsDuplicateModulesAndShortSecretsAreRejectedBeforePersistence() {
    assertThatThrownBy(() -> installation.provision(List.of(credential("alpha"), credential("beta"))))
        .isInstanceOf(IllegalArgumentException.class);
    var value = credential("alpha");
    assertThatThrownBy(() -> installation.provision(List.of(value, value))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ServiceClientInstallation.Credential(value.operation(), "alpha", value.clientId(), "short"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(clients);
  }

  private ServiceClientInstallation.Credential credential(String module) {
    UUID operation = UUID.randomUUID();
    return new ServiceClientInstallation.Credential(operation, module,
        ModuleClientPlan.deploymentClientId("simplepoint-module-" + module, operation), "a".repeat(43));
  }
}

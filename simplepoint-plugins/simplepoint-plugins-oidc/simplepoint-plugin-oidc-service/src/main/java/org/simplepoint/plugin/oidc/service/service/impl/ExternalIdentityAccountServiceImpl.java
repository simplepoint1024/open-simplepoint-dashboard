package org.simplepoint.plugin.oidc.service.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityLink;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.error.ExternalIdentityBindingException;
import org.simplepoint.plugin.oidc.api.error.ExternalIdentityBindingException.Reason;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityBinding;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityConnectionView;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityAccountService;
import org.simplepoint.plugin.oidc.service.repository.JpaExternalIdentityLinkRepository;
import org.simplepoint.plugin.oidc.service.repository.JpaExternalIdentityProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns external account persistence independently of browser sessions and identity's database. */
@Service
@Transactional
public class ExternalIdentityAccountServiceImpl implements ExternalIdentityAccountService {
  private final JpaExternalIdentityProviderRepository providers;
  private final JpaExternalIdentityLinkRepository links;

  /** Uses OIDC-owned repositories and the module-selected transaction manager. */
  public ExternalIdentityAccountServiceImpl(
      JpaExternalIdentityProviderRepository providers, JpaExternalIdentityLinkRepository links) {
    this.providers = providers;
    this.links = links;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ExternalIdentityBinding> find(String providerId, String subject) {
    var provider =
        providers
            .findActiveById(required(providerId, 64))
            .orElseThrow(() -> failure(Reason.PROVIDER_UNAVAILABLE));
    requireEnabled(provider);
    return links
        .findActiveByProviderAndSubject(provider.getId(), required(subject, 512))
        .map(ExternalIdentityAccountServiceImpl::reference);
  }

  @Override
  public ExternalIdentityBinding bind(
      String providerId, String subject, String email, String userId) {
    String stableSubject = required(subject, 512);
    String account = required(userId, 64);
    final String contact = email == null || email.isBlank() ? null : required(email, 320);
    var provider = locked(providerId);
    requireEnabled(provider);
    var existing =
        links.findActiveByProviderAndSubject(provider.getId(), stableSubject).orElse(null);
    if (existing != null) {
      if (!existing.getUserId().equals(account)) {
        throw failure(Reason.SUBJECT_IN_USE);
      }
      return reference(existing);
    }
    if (links.findActiveByProviderAndUserId(provider.getId(), account).isPresent()) {
      throw failure(Reason.ACCOUNT_IN_USE);
    }
    var link = new ExternalIdentityLink();
    link.setProviderId(provider.getId());
    link.setExternalSubject(stableSubject);
    link.setUserId(account);
    link.setEmailAtLink(contact);
    return reference(links.save(link));
  }

  @Override
  public void recordLogin(ExternalIdentityBinding binding) {
    if (binding == null) {
      throw failure(Reason.INVALID_INPUT);
    }
    var current =
        links
            .findEffectiveByIdForUpdate(
                required(binding.id(), 255),
                required(binding.providerId(), 64),
                required(binding.userId(), 64))
            .orElseThrow(() -> failure(Reason.BINDING_REVOKED));
    current.setLastLoginAt(Instant.now());
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExternalIdentityConnectionView> connections(String userId) {
    Map<String, ExternalIdentityLink> current =
        links.findAllActiveByUserId(required(userId, 64)).stream()
            .collect(Collectors.toMap(ExternalIdentityLink::getProviderId, Function.identity()));
    return providers.findAllActive().stream()
        .filter(
            provider ->
                Boolean.TRUE.equals(provider.getEnabled()) || current.containsKey(provider.getId()))
        .map(provider -> view(provider, current.get(provider.getId())))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isLinked(String userId, String registrationId) {
    var provider = provider(registrationId);
    return links.findActiveByProviderAndUserId(provider.getId(), required(userId, 64)).isPresent();
  }

  @Override
  public void unlink(String userId, String registrationId) {
    var provider = locked(provider(registrationId).getId());
    var link =
        links
            .findActiveByProviderAndUserIdForUpdate(provider.getId(), required(userId, 64))
            .orElseThrow(() -> failure(Reason.NOT_LINKED));
    // Preserve the row explicitly; generic deletion semantics must not erase binding history.
    link.setDeletedAt(Instant.now());
    links.save(link);
  }

  private ExternalIdentityProvider provider(String registrationId) {
    return providers
        .findActiveByRegistrationId(required(registrationId, 64))
        .orElseThrow(() -> failure(Reason.PROVIDER_UNAVAILABLE));
  }

  private ExternalIdentityProvider locked(String providerId) {
    return providers
        .findActiveByIdForUpdate(required(providerId, 64))
        .orElseThrow(() -> failure(Reason.PROVIDER_UNAVAILABLE));
  }

  private static void requireEnabled(ExternalIdentityProvider provider) {
    if (!Boolean.TRUE.equals(provider.getEnabled())) {
      throw failure(Reason.PROVIDER_UNAVAILABLE);
    }
  }

  private static String required(String value, int maximum) {
    if (value == null || value.isBlank()) {
      throw failure(Reason.INVALID_INPUT);
    }
    String result = value.trim();
    if (result.codePointCount(0, result.length()) > maximum) {
      throw failure(Reason.INVALID_INPUT);
    }
    return result;
  }

  private static ExternalIdentityBinding reference(ExternalIdentityLink link) {
    return new ExternalIdentityBinding(
        link.getId(), link.getProviderId(), link.getExternalSubject(), link.getUserId());
  }

  private static ExternalIdentityConnectionView view(
      ExternalIdentityProvider provider, ExternalIdentityLink link) {
    return new ExternalIdentityConnectionView(
        provider.getRegistrationId(),
        provider.getDisplayName(),
        provider.getPreset(),
        Boolean.TRUE.equals(provider.getEnabled()),
        link != null,
        link == null ? null : link.getEmailAtLink(),
        link == null ? null : link.getCreatedAt(),
        link == null ? null : link.getLastLoginAt());
  }

  private static ExternalIdentityBindingException failure(Reason reason) {
    return new ExternalIdentityBindingException(reason);
  }
}

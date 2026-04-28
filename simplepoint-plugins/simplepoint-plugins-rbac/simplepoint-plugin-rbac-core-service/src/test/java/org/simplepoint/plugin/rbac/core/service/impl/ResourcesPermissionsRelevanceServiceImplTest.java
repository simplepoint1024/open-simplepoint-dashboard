package org.simplepoint.plugin.rbac.core.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.rbac.core.api.repository.ResourcesRelevanceRepository;
import org.simplepoint.security.entity.ResourcesPermissionsRelevance;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResourcesPermissionsRelevanceServiceImplTest {

    @Mock
    ResourcesRelevanceRepository resourcesRelevanceRepository;

    @InjectMocks
    ResourcesPermissionsRelevanceServiceImpl service;

    @Test
    void removeAllByAuthority_delegatesToRepository() {
        service.removeAllByAuthority("auth1");
        verify(resourcesRelevanceRepository).removeAllByAuthority("auth1");
    }

    @Test
    void removeAllByAuthorities_delegatesToRepository() {
        Collection<String> authorities = Set.of("auth1", "auth2");
        service.removeAllByAuthorities(authorities);
        verify(resourcesRelevanceRepository).removeAllByAuthorities(authorities);
    }

    @Test
    void authorize_delegatesToRepository() {
        ResourcesPermissionsRelevance rel = new ResourcesPermissionsRelevance();
        Collection<ResourcesPermissionsRelevance> collection = List.of(rel);
        service.authorize(collection);
        verify(resourcesRelevanceRepository).authorize(collection);
    }

    @Test
    void unauthorize_delegatesToRepository() {
        Collection<String> resourceAuthorities = Set.of("res1");
        service.unauthorize("auth1", resourceAuthorities);
        verify(resourcesRelevanceRepository).unauthorize("auth1", resourceAuthorities);
    }

    @Test
    void authorized_delegatesToRepository() {
        when(resourcesRelevanceRepository.authorized("res1")).thenReturn(List.of("auth1"));
        Collection<String> result = service.authorized("res1");
        assertThat(result).containsExactly("auth1");
    }

    @Test
    void loadAllResourceAuthorities_delegatesToRepository() {
        Collection<String> input = Set.of("res1", "res2");
        when(resourcesRelevanceRepository.loadAllResourceAuthorities(input)).thenReturn(List.of("auth1", "auth2"));
        Collection<String> result = service.loadAllResourceAuthorities(input);
        assertThat(result).hasSize(2);
    }
}

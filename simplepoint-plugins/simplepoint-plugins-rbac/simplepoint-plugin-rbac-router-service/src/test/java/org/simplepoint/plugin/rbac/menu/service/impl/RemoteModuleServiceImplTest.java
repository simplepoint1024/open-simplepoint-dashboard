package org.simplepoint.plugin.rbac.menu.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.rbac.menu.api.repository.RemoteModuleRepository;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RemoteModuleServiceImplTest {

    @Mock
    RemoteModuleRepository remoteModuleRepository;

    @Mock
    DetailsProviderService detailsProviderService;

    @InjectMocks
    RemoteModuleServiceImpl service;

    @Test
    void loadApps_returnsEmptySet() {
        assertThat(service.loadApps()).isEmpty();
    }
}

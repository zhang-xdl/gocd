/*
 * Copyright 2020 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thoughtworks.go.server.service.materials;

import com.thoughtworks.go.ClearSingleton;
import com.thoughtworks.go.domain.config.PluginConfiguration;
import com.thoughtworks.go.domain.packagerepository.ConfigurationPropertyMother;
import com.thoughtworks.go.domain.packagerepository.PackageRepository;
import com.thoughtworks.go.plugin.access.packagematerial.PackageConfiguration;
import com.thoughtworks.go.plugin.access.packagematerial.PackageConfigurations;
import com.thoughtworks.go.plugin.access.packagematerial.PackageRepositoryExtension;
import com.thoughtworks.go.plugin.access.packagematerial.RepositoryMetadataStore;
import com.thoughtworks.go.plugin.api.material.packagerepository.RepositoryConfiguration;
import com.thoughtworks.go.plugin.api.response.Result;
import com.thoughtworks.go.plugin.api.response.validation.ValidationError;
import com.thoughtworks.go.plugin.api.response.validation.ValidationResult;
import com.thoughtworks.go.plugin.infra.PluginManager;
import com.thoughtworks.go.plugin.infra.plugininfo.GoPluginDescriptor;
import com.thoughtworks.go.server.service.EntityHashingService;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.server.service.SecurityService;
import com.thoughtworks.go.server.service.result.HttpLocalizedOperationResult;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.Arrays;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class PackageRepositoryServiceTest {

    @Mock
    private PluginManager pluginManager;
    @Mock
    private PackageRepositoryExtension packageRepositoryExtension;
    @Mock
    private GoConfigService goConfigService;
    @Mock
    private SecurityService securityService;
    @Mock
    private EntityHashingService entityHashingService;
    private PackageRepositoryService service;

    @Rule
    public final ClearSingleton clearSingleton = new ClearSingleton();

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        service = new PackageRepositoryService(pluginManager, packageRepositoryExtension, goConfigService, securityService, entityHashingService);
    }

    @Test
    public void shouldPerformPluginValidationsUsingMetaDataBeforeSavingPackageRepository() throws Exception {
        //metadata setup
        String pluginId = "yum";

        PackageConfigurations repositoryConfiguration = new PackageConfigurations();
        repositoryConfiguration.add(new PackageConfiguration("required").with(PackageConfiguration.REQUIRED, true));
        repositoryConfiguration.add(new PackageConfiguration("required_secure").with(PackageConfiguration.REQUIRED, true).with(PackageConfiguration.SECURE, true));
        repositoryConfiguration.add(new PackageConfiguration("secure").with(PackageConfiguration.SECURE, true).with(PackageConfiguration.REQUIRED, false));
        repositoryConfiguration.add(new PackageConfiguration("not_required_not_secure"));
        RepositoryMetadataStore.getInstance().addMetadataFor(pluginId, repositoryConfiguration);

        PackageRepository packageRepository = new PackageRepository();
        packageRepository.setPluginConfiguration(new PluginConfiguration(pluginId, "1.0"));
        packageRepository.getConfiguration().add(ConfigurationPropertyMother.create("required", false, ""));
        packageRepository.getConfiguration().add(ConfigurationPropertyMother.create("required_secure", true, ""));
        packageRepository.getConfiguration().add(ConfigurationPropertyMother.create("secure", true, ""));
        packageRepository.getConfiguration().add(ConfigurationPropertyMother.create("not_required_not_secure", false, ""));

        when(packageRepositoryExtension.isRepositoryConfigurationValid(eq(pluginId), any(RepositoryConfiguration.class))).thenReturn(new ValidationResult());
        when(pluginManager.getPluginDescriptorFor(pluginId)).thenReturn(getPluginDescriptor(pluginId));

        service.performPluginValidationsFor(packageRepository);

        assertThat(packageRepository.getConfiguration().get(0).getConfigurationValue().errors().getAllOn("value"), is(Arrays.asList("This field is required")));
        assertThat(packageRepository.getConfiguration().get(1).getEncryptedConfigurationValue().errors().getAllOn("value"), is(Arrays.asList("This field is required")));
    }

    private GoPluginDescriptor getPluginDescriptor(String pluginId) {
        return GoPluginDescriptor.builder().id(pluginId).version("1.0").isBundledPlugin(true).build();
    }

    @Test
    public void shouldInvokePluginValidationsBeforeSavingPackageRepository() throws Exception {
        String pluginId = "yum";
        PackageRepository packageRepository = new PackageRepository();
        RepositoryMetadataStore.getInstance().addMetadataFor(pluginId, new PackageConfigurations());
        packageRepository.setPluginConfiguration(new PluginConfiguration(pluginId, "1.0"));
        packageRepository.getConfiguration().add(ConfigurationPropertyMother.create("url", false, "junk-url"));

        ArgumentCaptor<RepositoryConfiguration> packageConfigurationsArgumentCaptor = ArgumentCaptor.forClass(RepositoryConfiguration.class);
        ValidationResult expectedValidationResult = new ValidationResult();
        expectedValidationResult.addError(new ValidationError("url", "url format incorrect"));

        when(pluginManager.getPluginDescriptorFor(pluginId)).thenReturn(getPluginDescriptor("yum"));
        when(packageRepositoryExtension.isRepositoryConfigurationValid(eq(pluginId), packageConfigurationsArgumentCaptor.capture())).thenReturn(expectedValidationResult);

        service = new PackageRepositoryService(pluginManager, packageRepositoryExtension, goConfigService, securityService, entityHashingService);
        service.performPluginValidationsFor(packageRepository);
        assertThat(packageRepository.getConfiguration().get(0).getConfigurationValue().errors().getAllOn("value"), is(Arrays.asList("url format incorrect")));
    }

    @Test
    public void shouldAddErrorWhenPluginIdIsMissing() {
        PackageRepository packageRepository = new PackageRepository();
        service.performPluginValidationsFor(packageRepository);
        assertThat(packageRepository.getPluginConfiguration().errors().getAllOn(PluginConfiguration.ID), is(Arrays.asList("Please select package repository plugin")));
    }

    @Test
    public void shouldAddErrorWhenPluginIdIsInvalid() {
        when(pluginManager.plugins()).thenReturn(Arrays.asList(getPluginDescriptor("valid")));
        PackageRepository packageRepository = new PackageRepository();
        packageRepository.setPluginConfiguration(new PluginConfiguration("missing-plugin", "1.0"));
        service.performPluginValidationsFor(packageRepository);
        assertThat(packageRepository.getPluginConfiguration().errors().getAllOn(PluginConfiguration.ID), is(Arrays.asList("Invalid plugin id")));
    }

    @Test
    public void shouldUpdatePluginVersionWhenValid() {
        String pluginId = "valid";
        RepositoryMetadataStore.getInstance().addMetadataFor(pluginId, new PackageConfigurations());
        when(pluginManager.getPluginDescriptorFor(pluginId)).thenReturn(getPluginDescriptor(pluginId));
        when(packageRepositoryExtension.isRepositoryConfigurationValid(eq(pluginId), any(RepositoryConfiguration.class))).thenReturn(new ValidationResult());
        PackageRepository packageRepository = new PackageRepository();
        packageRepository.setPluginConfiguration(new PluginConfiguration(pluginId, ""));
        service.performPluginValidationsFor(packageRepository);
        assertThat(packageRepository.getPluginConfiguration().getVersion(), is("1.0"));
    }

    @Test
    public void shouldPerformCheckConnectionOnPlugin() throws Exception {
        String pluginId = "yum";
        PackageRepository packageRepository = packageRepository(pluginId);
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();

        PackageRepositoryService service = new PackageRepositoryService(pluginManager, packageRepositoryExtension, goConfigService, securityService, entityHashingService);

        ArgumentCaptor<RepositoryConfiguration> argumentCaptor = ArgumentCaptor.forClass(RepositoryConfiguration.class);
        when(packageRepositoryExtension.checkConnectionToRepository(eq(pluginId), argumentCaptor.capture())).thenReturn(new Result().withSuccessMessages("Accessed Repo File!!!"));

        service.checkConnection(packageRepository, result);

        RepositoryConfiguration packageConfigurations = argumentCaptor.getValue();
        PackageMaterialTestHelper.assertPackageConfiguration(packageConfigurations.list(), packageRepository.getConfiguration());
        assertThat(result.isSuccessful(), is(true));
        assertThat(result.message(), is("Connection OK. Accessed Repo File!!!"));
        verify(packageRepositoryExtension).checkConnectionToRepository(eq(pluginId), any(RepositoryConfiguration.class));
    }

    @Test
    public void shouldPerformCheckConnectionOnPluginAndCatchAnyExceptionsThrownByThePlugin() throws Exception {
        String pluginId = "yum";
        PackageRepository packageRepository = packageRepository(pluginId);
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();

        PackageRepositoryService service = new PackageRepositoryService(pluginManager, packageRepositoryExtension, goConfigService, securityService, entityHashingService);

        ArgumentCaptor<RepositoryConfiguration> argumentCaptor = ArgumentCaptor.forClass(RepositoryConfiguration.class);
        when(packageRepositoryExtension.checkConnectionToRepository(eq(pluginId), argumentCaptor.capture())).thenThrow(new RuntimeException("Check Connection not implemented!!"));

        service.checkConnection(packageRepository, result);

        assertThat(result.isSuccessful(), is(false));
        assertThat(result.message(), is("Could not connect to package repository. Reason(s): Check Connection not implemented!!"));
        verify(packageRepositoryExtension).checkConnectionToRepository(eq(pluginId), any(RepositoryConfiguration.class));
    }

    @Test
    public void shouldPopulateErrorsForCheckConnectionOnPlugin() throws Exception {
        String pluginId = "yum";
        PackageRepository packageRepository = packageRepository(pluginId);
        HttpLocalizedOperationResult result = new HttpLocalizedOperationResult();
        PackageRepositoryService service = new PackageRepositoryService(pluginManager, packageRepositoryExtension, goConfigService, securityService, entityHashingService);

        ArgumentCaptor<RepositoryConfiguration> argumentCaptor = ArgumentCaptor.forClass(RepositoryConfiguration.class);

        when(packageRepositoryExtension.checkConnectionToRepository(eq(pluginId), argumentCaptor.capture())).thenReturn(new Result().withErrorMessages("Repo invalid!!", "Could not connect"));
        service.checkConnection(packageRepository, result);

        RepositoryConfiguration packageConfigurations = argumentCaptor.getValue();
        PackageMaterialTestHelper.assertPackageConfiguration(packageConfigurations.list(), packageRepository.getConfiguration());
        assertThat(result.isSuccessful(), is(false));

        assertThat(result.message(), is("Could not connect to package repository. Reason(s): Repo invalid!!\nCould not connect"));
        verify(packageRepositoryExtension).checkConnectionToRepository(eq(pluginId), any(RepositoryConfiguration.class));
    }

    private PackageRepository packageRepository(String pluginId) {
        PackageRepository packageRepository = new PackageRepository();
        packageRepository.setPluginConfiguration(new PluginConfiguration(pluginId, "1.0"));
        packageRepository.getConfiguration().add(ConfigurationPropertyMother.create("p1", false, "v1"));
        packageRepository.getConfiguration().add(ConfigurationPropertyMother.create("p2", true, "v2"));
        packageRepository.getConfiguration().add(ConfigurationPropertyMother.create("p3", true, "v3"));
        packageRepository.getConfiguration().add(ConfigurationPropertyMother.create("p4", false, "v4"));
        return packageRepository;
    }
}

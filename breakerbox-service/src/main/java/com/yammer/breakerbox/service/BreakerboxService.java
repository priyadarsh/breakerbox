package com.yammer.breakerbox.service;

import com.google.common.collect.ImmutableMap;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DynamicConfiguration;
import com.netflix.config.sources.URLConfigurationSource;
import com.netflix.turbine.init.TurbineInit;
import com.netflix.turbine.streaming.servlet.TurbineStreamServlet;
import com.yammer.azure.TableClient;
import com.yammer.azure.TableClientFactory;
import com.yammer.azure.healthchecks.TableClientHealthcheck;
import com.yammer.breakerbox.service.archaius.TenacityPollingScheduler;
import com.yammer.breakerbox.service.config.BreakerboxConfiguration;
import com.yammer.breakerbox.service.core.TenacityStore;
import com.yammer.breakerbox.service.resources.ArchaiusResource;
import com.yammer.breakerbox.service.resources.ConfigureResource;
import com.yammer.breakerbox.service.resources.DashboardResource;
import com.yammer.breakerbox.service.store.ScheduledTenacityPoller;
import com.yammer.breakerbox.service.store.TenacityPropertyKeysStore;
import com.yammer.breakerbox.service.tenacity.BreakerboxDependencyKey;
import com.yammer.breakerbox.service.tenacity.TenacityPoller;
import com.yammer.dropwizard.Service;
import com.yammer.dropwizard.config.Bootstrap;
import com.yammer.dropwizard.config.Environment;
import com.yammer.tenacity.client.TenacityClientFactory;
import com.yammer.tenacity.core.bundle.TenacityBundle;
import com.yammer.tenacity.core.config.TenacityConfiguration;
import com.yammer.tenacity.core.properties.TenacityPropertyKey;
import com.yammer.tenacity.core.properties.TenacityPropertyRegister;
import com.yammer.tenacity.dashboard.bundle.TenacityDashboardBundle;

import java.net.URL;
import java.util.concurrent.TimeUnit;

public class BreakerboxService extends Service<BreakerboxConfiguration> {
    public static void main(String[] args) throws Exception {
        new BreakerboxService().run(args);
    }

    private BreakerboxService() {}

    @Override
    public void initialize(Bootstrap<BreakerboxConfiguration> bootstrap) {
        bootstrap.setName("Breakerbox");
        bootstrap.addBundle(new TenacityBundle(BreakerboxDependencyKey.values()));
        bootstrap.addBundle(new TenacityDashboardBundle());

        TurbineInit.init();
    }
    
    private static void registerProperties(BreakerboxConfiguration configuration) {
        new TenacityPropertyRegister(ImmutableMap.<TenacityPropertyKey, TenacityConfiguration>of(
                BreakerboxDependencyKey.BRKRBX_SERVICES, configuration.getBreakerboxServicesTenacity()))
                .register();
    }

    @Override
    public void run(BreakerboxConfiguration configuration, Environment environment) throws Exception {
        registerProperties(configuration);
        
        final TableClient tableClient = new TableClientFactory(configuration.getAzure()).create();
        final TenacityStore tenacityStore = new TenacityStore(tableClient);
        final TenacityPropertyKeysStore tenacityPropertyKeysStore = new TenacityPropertyKeysStore(
                new TenacityPoller.Factory(
                        new TenacityClientFactory(configuration.getTenacityClient())
                                .build(environment)));
        
        environment.addHealthCheck(new TableClientHealthcheck(tableClient));

        environment.addServlet(new TurbineStreamServlet(), "/turbine.stream");

        environment.addResource(new ArchaiusResource(tenacityStore));
        environment.addResource(new ConfigureResource(tenacityStore, tenacityPropertyKeysStore));
        environment.addResource(new DashboardResource());

        environment.managedScheduledExecutorService("scheduled-tenacity-poller-%d", 1)
                .scheduleAtFixedRate(
                        new ScheduledTenacityPoller(tenacityPropertyKeysStore),
                        0,
                        1,
                        TimeUnit.MINUTES);

        ConfigurationManager.install(new DynamicConfiguration(
                new URLConfigurationSource(
                        new URL("file:///Users/cgray/code/breakerbox/breakerbox-service/config.properties"),
                        new URL("http://localhost:8080/archaius/breakerbox")),
                new TenacityPollingScheduler()));
    }
}
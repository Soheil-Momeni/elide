/*
 * Copyright 2019, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.spring.config;

import com.yahoo.elide.Elide;
import com.yahoo.elide.ElideSettingsBuilder;
import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.audit.Slf4jLogger;
import com.yahoo.elide.core.datastore.DataStore;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import com.yahoo.elide.core.dictionary.Injector;
import com.yahoo.elide.core.filter.dialect.RSQLFilterDialect;
import com.yahoo.elide.datastores.aggregation.AggregationDataStore;
import com.yahoo.elide.datastores.aggregation.QueryEngine;
import com.yahoo.elide.datastores.aggregation.cache.Cache;
import com.yahoo.elide.datastores.aggregation.cache.CaffeineCache;
import com.yahoo.elide.datastores.aggregation.core.QueryLogger;
import com.yahoo.elide.datastores.aggregation.core.Slf4jQueryLogger;
import com.yahoo.elide.datastores.aggregation.metadata.MetaDataStore;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.ConnectionDetails;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.DataSourceConfiguration;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.SQLQueryEngine;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.annotation.FromSubquery;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.annotation.FromTable;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.dialects.SQLDialectFactory;
import com.yahoo.elide.datastores.jpa.JpaDataStore;
import com.yahoo.elide.datastores.jpa.transaction.NonJtaTransaction;
import com.yahoo.elide.datastores.multiplex.MultiplexManager;
import com.yahoo.elide.modelconfig.DBPasswordExtractor;
import com.yahoo.elide.modelconfig.compile.ElideDynamicEntityCompiler;
import com.yahoo.elide.modelconfig.model.DBConfig;
import com.yahoo.elide.swagger.SwaggerBuilder;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.swagger.models.Info;
import io.swagger.models.Swagger;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Consumer;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;

/**
 * Auto Configuration For Elide Services.  Override any of the beans (by defining your own) to change
 * the default behavior.
 */
@Configuration
@EnableConfigurationProperties(ElideConfigProperties.class)
@Slf4j
public class ElideAutoConfiguration {

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private final Consumer<EntityManager> txCancel = (em) -> { em.unwrap(Session.class).cancelQuery(); };

    /**
     * Creates a entity compiler for compiling dynamic config classes.
     * @param settings Config Settings.
     * @return An instance of ElideDynamicEntityCompiler.
     * @throws Exception Exception thrown.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "elide.aggregation-store.enabled", havingValue = "true")
    public ElideDynamicEntityCompiler buildElideDynamicEntityCompiler(ElideConfigProperties settings) throws Exception {

        ElideDynamicEntityCompiler compiler = null;

        if (isDynamicConfigEnabled(settings)) {
            compiler = new ElideDynamicEntityCompiler(settings.getDynamicConfig().getPath());
        }
        return compiler;
    }

    /**
     * Creates the default Password Extractor Implementation.
     * @return An instance of DBPasswordExtractor.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "elide.aggregation-store.enabled", havingValue = "true")
    public DBPasswordExtractor getDBPasswordExtractor() {

        return new DBPasswordExtractor() {
            @Override
            public String getDBPassword(DBConfig config) {
                return StringUtils.EMPTY;
            }
        };
    }

    /**
     * Provides the default Hikari DataSource Configuration.
     * @return An instance of DataSourceConfiguration.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "elide.aggregation-store.enabled", havingValue = "true")
    public DataSourceConfiguration getDataSourceConfiguration() {
        return new DataSourceConfiguration() {
        };
    }

    /**
     * Creates the Elide instance with standard settings.
     * @param dictionary Stores the static metadata about Elide models.
     * @param dataStore The persistence store.
     * @param settings Elide settings.
     * @return A new elide instance.
     */
    @Bean
    @ConditionalOnMissingBean
    public Elide initializeElide(EntityDictionary dictionary,
            DataStore dataStore, ElideConfigProperties settings) {

        ElideSettingsBuilder builder = new ElideSettingsBuilder(dataStore)
                .withEntityDictionary(dictionary)
                .withDefaultMaxPageSize(settings.getMaxPageSize())
                .withDefaultPageSize(settings.getPageSize())
                .withJoinFilterDialect(new RSQLFilterDialect(dictionary))
                .withSubqueryFilterDialect(new RSQLFilterDialect(dictionary))
                .withAuditLogger(new Slf4jLogger())
                .withISO8601Dates("yyyy-MM-dd'T'HH:mm'Z'", TimeZone.getTimeZone("UTC"));

        return new Elide(builder.build());
    }

    /**
     * Creates the entity dictionary for Elide which contains static metadata about Elide models.
     * Override to load check classes or life cycle hooks.
     * @param beanFactory Injector to inject Elide models.
     * @param dynamicCompiler An instance of objectprovider for ElideDynamicEntityCompiler.
     * @param settings Elide configuration settings.
     * @return a newly configured EntityDictionary.
     * @throws ClassNotFoundException Exception thrown.
     */
    @Bean
    @ConditionalOnMissingBean
    public EntityDictionary buildDictionary(AutowireCapableBeanFactory beanFactory,
            ObjectProvider<ElideDynamicEntityCompiler> dynamicCompiler, ElideConfigProperties settings)
            throws ClassNotFoundException {
        EntityDictionary dictionary = new EntityDictionary(new HashMap<>(),
                new Injector() {
                    @Override
                    public void inject(Object entity) {
                        beanFactory.autowireBean(entity);
                    }

                    @Override
                    public <T> T instantiate(Class<T> cls) {
                        return beanFactory.createBean(cls);
                    }
                });

        dictionary.scanForSecurityChecks();

        if (isAggregationStoreEnabled(settings) && isDynamicConfigEnabled(settings)) {
            ElideDynamicEntityCompiler compiler = dynamicCompiler.getIfAvailable();
            Set<Class<?>> annotatedClass = compiler.findAnnotatedClasses(SecurityCheck.class);
            dictionary.addSecurityChecks(annotatedClass);
        }

        return dictionary;
    }

    /**
     * Create a QueryEngine instance for aggregation data store to use.
     * @param defaultDataSource DataSource for JPA.
     * @param dynamicCompiler An instance of objectprovider for ElideDynamicEntityCompiler.
     * @param settings Elide configuration settings.
     * @param dataSourceConfiguration DataSource Configuration
     * @param dbPasswordExtractor Password Extractor Implementation
     * @return An instance of a QueryEngine
     * @throws ClassNotFoundException Exception thrown.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "elide.aggregation-store.enabled", havingValue = "true")
    public QueryEngine buildQueryEngine(DataSource defaultDataSource,
                                        ObjectProvider<ElideDynamicEntityCompiler> dynamicCompiler,
                                        ElideConfigProperties settings,
                                        DataSourceConfiguration dataSourceConfiguration,
                                        DBPasswordExtractor dbPasswordExtractor) throws ClassNotFoundException {

        boolean enableMetaDataStore = settings.getAggregationStore().isEnableMetaDataStore();
        ConnectionDetails defaultConnectionDetails = new ConnectionDetails(defaultDataSource,
                        SQLDialectFactory.getDialect(settings.getAggregationStore().getDefaultDialect()));
        if (isDynamicConfigEnabled(settings)) {
            MetaDataStore metaDataStore = new MetaDataStore(dynamicCompiler.getIfAvailable(), enableMetaDataStore);
            Map<String, ConnectionDetails> connectionDetailsMap = new HashMap<>();

            dynamicCompiler.getIfAvailable().getElideSQLDBConfig().getDbconfigs().forEach(dbConfig -> {
                connectionDetailsMap.put(dbConfig.getName(),
                                new ConnectionDetails(
                                                dataSourceConfiguration.getDataSource(dbConfig, dbPasswordExtractor),
                                                SQLDialectFactory.getDialect(dbConfig.getDialect())));
            });

            return new SQLQueryEngine(metaDataStore, defaultConnectionDetails, connectionDetailsMap);
        } else {
            MetaDataStore metaDataStore = new MetaDataStore(enableMetaDataStore);
            return new SQLQueryEngine(metaDataStore, defaultConnectionDetails);
        }
    }

    /**
     * Creates the DataStore Elide.  Override to use a different store.
     * @param entityManagerFactory The JPA factory which creates entity managers.
     * @param queryEngine QueryEngine instance for aggregation data store.
     * @param compiler ElideDynamicEntityCompiler.
     * @param settings Elide configuration settings.
     * @return An instance of a JPA DataStore.
     * @throws ClassNotFoundException Exception thrown.
     */
    @Bean
    @ConditionalOnMissingBean
    public DataStore buildDataStore(EntityManagerFactory entityManagerFactory,
                                    @Autowired(required = false) QueryEngine queryEngine,
                                    @Autowired(required = false) ElideDynamicEntityCompiler compiler,
                                    ElideConfigProperties settings,
                                    @Autowired(required = false) Cache cache,
                                    @Autowired(required = false) QueryLogger querylogger)
            throws ClassNotFoundException {

        JpaDataStore jpaDataStore = new JpaDataStore(entityManagerFactory::createEntityManager,
                                                     (em) -> { return new NonJtaTransaction(em, txCancel); });

        if (isAggregationStoreEnabled(settings)) {
            AggregationDataStore.AggregationDataStoreBuilder aggregationDataStoreBuilder =
                            AggregationDataStore.builder().queryEngine(queryEngine);
            if (isDynamicConfigEnabled(settings)) {
                Set<Class<?>> annotatedClass = compiler.findAnnotatedClasses(FromTable.class);
                annotatedClass.addAll(compiler.findAnnotatedClasses(FromSubquery.class));
                aggregationDataStoreBuilder.dynamicCompiledClasses(annotatedClass);
            }
            aggregationDataStoreBuilder.cache(cache);
            aggregationDataStoreBuilder.queryLogger(querylogger);
            AggregationDataStore aggregationDataStore = aggregationDataStoreBuilder.build();

            // meta data store needs to be put at first to populate meta data models
            return new MultiplexManager(jpaDataStore, queryEngine.getMetaDataStore(), aggregationDataStore);
        }

        return jpaDataStore;
    }

    /**
     * Creates a query result cache to be used by {@link #buildDataStore}, or null if cache is to be disabled.
     * @param settings Elide configuration settings.
     * @return An instance of a query cache, or null.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "elide.aggregation-store.enabled", havingValue = "true")
    public Cache buildQueryCache(ElideConfigProperties settings) {
        CaffeineCache cache = null;

        int maxCacheItems = settings.getAggregationStore().getQueryCacheMaximumEntries();
        if (maxCacheItems > 0) {
            cache = new CaffeineCache(maxCacheItems, settings.getAggregationStore().getDefaultCacheExpirationMinutes());
            if (meterRegistry != null) {
                CaffeineCacheMetrics.monitor(meterRegistry, cache.getImplementation(), "elideQueryCache");
            }
        }
        return cache;
    }

    /**
     * Creates a querylogger to be used by {@link #buildDataStore} for aggregation.
     * @return The default Noop QueryLogger.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "elide.aggregation-store.enabled", havingValue = "true")
    public QueryLogger buildQueryLogger() {
        return new Slf4jQueryLogger();
    }

    /**
     * Creates a singular swagger document for JSON-API.
     * @param dictionary Contains the static metadata about Elide models.
     * @param settings Elide configuration settings.
     * @return An instance of a JPA DataStore.
     */
    @Bean
    @ConditionalOnMissingBean
    public Swagger buildSwagger(EntityDictionary dictionary, ElideConfigProperties settings) {
        Info info = new Info()
                .title(settings.getSwagger().getName())
                .version(settings.getSwagger().getVersion());

        SwaggerBuilder builder = new SwaggerBuilder(dictionary, info).withLegacyFilterDialect(false);

        Swagger swagger = builder.build().basePath(settings.getJsonApi().getPath());

        return swagger;
    }

    private boolean isDynamicConfigEnabled(ElideConfigProperties settings) {

        boolean enabled = false;
        if (settings.getDynamicConfig() != null) {
            enabled = settings.getDynamicConfig().isEnabled();
        }

        return enabled;

    }

    private boolean isAggregationStoreEnabled(ElideConfigProperties settings) {

        boolean enabled = false;
        if (settings.getAggregationStore() != null) {
            enabled = settings.getAggregationStore().isEnabled();
        }

        return enabled;

    }
}

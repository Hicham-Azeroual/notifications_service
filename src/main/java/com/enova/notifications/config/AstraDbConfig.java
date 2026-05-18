package com.enova.notifications.config;

// Added new imports for driver configuration
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.data.cassandra.config.AbstractReactiveCassandraConfiguration;
import org.springframework.data.cassandra.config.SchemaAction;
import org.springframework.data.cassandra.config.SessionBuilderConfigurer;

import java.io.IOException;
import java.time.Duration;

@Configuration
public class AstraDbConfig extends AbstractReactiveCassandraConfiguration {

    @Value("classpath:secure-connect-sih-notifications.zip")
    private Resource secureConnectBundle;

    @Value("${spring.cassandra.username}")
    private String username;

    @Value("${spring.cassandra.password}")
    private String password;

    @Override
    protected String getKeyspaceName() {
        return "sih_notifications";
    }

    @Override
    public SchemaAction getSchemaAction() {
        return SchemaAction.CREATE_IF_NOT_EXISTS;
    }

    @Override
    public String[] getEntityBasePackages() {
        return new String[]{"com.enova.notifications"};
    }

    @Bean
    @Override
    protected SessionBuilderConfigurer getSessionBuilderConfigurer() {
        return new SessionBuilderConfigurer() {
            @Override
            public CqlSessionBuilder configure(CqlSessionBuilder cqlSessionBuilder) {

                // 1. Create a configuration loader to increase the default timeouts
                DriverConfigLoader loader = DriverConfigLoader.programmaticBuilder()
                        .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(10))
                        .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, Duration.ofSeconds(10))
                        .withDuration(DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT, Duration.ofSeconds(10))
                        .build();

                try {
                    return cqlSessionBuilder
                            .withCloudSecureConnectBundle(secureConnectBundle.getURL())
                            .withAuthCredentials(username, password)
                            // 2. Apply the custom timeouts to your session
                            .withConfigLoader(loader);
                } catch (IOException e) {
                    throw new IllegalStateException("Could not find or read the Astra secure bundle.", e);
                }
            }
        };
    }
}
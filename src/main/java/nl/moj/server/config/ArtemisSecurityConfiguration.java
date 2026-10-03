package nl.moj.server.config;

import java.util.Set;

import org.apache.activemq.artemis.core.config.impl.SecurityConfiguration;
import org.apache.activemq.artemis.core.security.Role;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManagerImpl;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "moj.broker.authentication-enabled", havingValue = "true")
@EnableConfigurationProperties(ArtemisProperties.class)
public class ArtemisSecurityConfiguration {

    @Bean
    static BeanPostProcessor embeddedBrokerSecurity(ArtemisProperties properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof EmbeddedActiveMQ broker) {
                    if (!StringUtils.hasText(properties.getUser()) || !StringUtils.hasText(properties.getPassword())) {
                        throw new IllegalStateException("Embedded broker authentication requires a username and password");
                    }
                    SecurityConfiguration security = new SecurityConfiguration();
                    security.addUser(properties.getUser(), properties.getPassword());
                    security.addRole(properties.getUser(), "moj-worker");
                    broker.setSecurityManager(new ActiveMQSecurityManagerImpl(security));
                }
                if (bean instanceof org.apache.activemq.artemis.core.config.Configuration configuration) {
                    configuration.setSecurityEnabled(true);
                    // These queues are created by Boot before the broker starts.
                    // Application clients only need to send and consume messages.
                    Set<Role> roles = Set.of(new Role("moj-worker", true, true, false, false, false, false, false, false,
                            false, false));
                    configuration.getSecurityRoles().put("operation_request", roles);
                    configuration.getSecurityRoles().put("operation_response", roles);
                }
                return bean;
            }
        };
    }
}

package nl.moj.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConfigurationCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ArtemisSecurityConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArtemisAutoConfiguration.class))
            .withUserConfiguration(ArtemisSecurityConfiguration.class)
            .withPropertyValues("moj.broker.authentication-enabled=true", "spring.artemis.mode=embedded",
                    "spring.artemis.embedded.queues=operation_request,operation_response", "spring.artemis.user=test-user",
                    "spring.artemis.password=test-password");

    @Test
    void authenticatesRemoteClientsAndAllowsBothOperationQueues() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        runner.withBean(ArtemisConfigurationCustomizer.class, () -> configuration -> {
            try {
                configuration.addAcceptorConfiguration("test-tcp", "tcp://127.0.0.1:" + port);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).run(context -> {
            assertThat(context).hasNotFailed();
            EmbeddedActiveMQ broker = context.getBean(EmbeddedActiveMQ.class);
            assertThat(broker.getActiveMQServer().getConfiguration().isSecurityEnabled()).isTrue();
            try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + port)) {
                try (Connection connection = factory.createConnection("test-user", "test-password")) {
                    connection.start();
                    Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                    for (String name : new String[] { "operation_request", "operation_response" }) {
                        var queue = session.createQueue(name);
                        try (MessageConsumer consumer = session.createConsumer(queue)) {
                            session.createProducer(queue).send(session.createTextMessage("authenticated"));
                            assertThat(((TextMessage) consumer.receive(5000)).getText()).isEqualTo("authenticated");
                        }
                    }
                }
                assertThatThrownBy(() -> factory.createConnection("test-user", "wrong-password"))
                        .isInstanceOf(JMSException.class);
                assertThatThrownBy(() -> factory.createConnection("admin", "admin")).isInstanceOf(JMSException.class);
                assertThatThrownBy(factory::createConnection).isInstanceOf(JMSException.class);
            }
        });
    }

    @Test
    void refusesToStartWithMissingCredentials() {
        runner.withPropertyValues("spring.artemis.password=").run(context -> assertThat(context).hasFailed());
    }

}

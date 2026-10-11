package nl.moj.server.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class WebSocketSecurityConfigurationTest {
    @Test
    void sessionPrincipalCanConnectAndSubscribe() {
        var channel = securedChannel();
        for (var command : List.of(StompCommand.CONNECT, StompCommand.SUBSCRIBE)) {
            var headers = StompHeaderAccessor.create(command);
            headers.setUser(UsernamePasswordAuthenticationToken.authenticated("participant", "unused",
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            headers.setDestination("/topic/session");
            assertTrue(channel.send(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders())));
        }
    }

    @Test
    void anonymousMessagesAreRejected() {
        var channel = securedChannel();
        var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        assertThrows(MessageDeliveryException.class,
                () -> channel.send(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders())));
    }

    private ExecutorSubscribableChannel securedChannel() {
        var registration = new InspectableRegistration();
        new WebSocketConfiguration.WebSocketSecurityConfig().configureClientInboundChannel(registration);
        var channel = new ExecutorSubscribableChannel();
        channel.setInterceptors(registration.interceptors());
        channel.subscribe(message -> {
        });
        return channel;
    }

    private static class InspectableRegistration extends ChannelRegistration {
        List<ChannelInterceptor> interceptors() {
            return getInterceptors();
        }
    }
}

package nl.moj.server.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import nl.moj.server.teams.repository.TeamRepository;
import nl.moj.server.user.model.User;
import nl.moj.server.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private TeamRepository teamRepository;
    @InjectMocks
    private UserService service;

    @Test
    void browserLoginWithoutFamilyNameSavesNonNullProfile() {
        saveReturnsUser();
        User user = service.createOrUpdate(browserLogin(Map.of(
                "sub", "user-id", "given_name", "Jane", "email", "jane@example.com")));

        assertEquals("user-id", user.getName());
        assertEquals("Jane", user.getGivenName());
        assertEquals("", user.getFamilyName());
        assertEquals("jane@example.com", user.getEmail());
    }

    @Test
    void browserLoginWithoutProfileClaimsSavesEmptyValues() {
        saveReturnsUser();
        User user = service.createOrUpdate(browserLogin(Map.of("sub", "user-id")));

        assertEquals("", user.getGivenName());
        assertEquals("", user.getFamilyName());
        assertEquals("", user.getEmail());
    }

    @Test
    void bearerLoginWithoutProfileClaimsPreservesExistingProfile() {
        User existing = User.builder().name("user-id").givenName("Jane")
                .familyName("Doe").email("jane@example.com").build();
        when(userRepository.findByName("user-id")).thenReturn(existing);
        saveReturnsUser();
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject("user-id").build();

        User user = service.createOrUpdate(new JwtAuthenticationToken(jwt));

        assertSame(existing, user);
        assertEquals("Jane", user.getGivenName());
        assertEquals("Doe", user.getFamilyName());
        assertEquals("jane@example.com", user.getEmail());
    }

    @Test
    void suppliedProfileClaimsUpdateExistingUser() {
        User existing = User.builder().name("user-id").givenName("Old")
                .familyName("Name").email("old@example.com").build();
        when(userRepository.findByName("user-id")).thenReturn(existing);
        saveReturnsUser();

        User user = service.createOrUpdate(browserLogin(Map.of("sub", "user-id",
                "given_name", "Jane", "family_name", "Doe", "email", "jane@example.com")));

        assertSame(existing, user);
        assertEquals("Jane", user.getGivenName());
        assertEquals("Doe", user.getFamilyName());
        assertEquals("jane@example.com", user.getEmail());
    }

    private void saveReturnsUser() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            assertNotNull(user.getGivenName());
            assertNotNull(user.getFamilyName());
            assertNotNull(user.getEmail());
            return user;
        });
    }

    private OAuth2AuthenticationToken browserLogin(Map<String, Object> claims) {
        DefaultOidcUser user = new DefaultOidcUser(List.of(),
                new OidcIdToken("test-token", null, null, claims));
        return new OAuth2AuthenticationToken(user, List.of(), "keycloak");
    }
}

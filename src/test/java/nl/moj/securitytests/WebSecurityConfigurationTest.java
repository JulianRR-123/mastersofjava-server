package nl.moj.securitytests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.security.RolesAllowed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import nl.moj.server.authorization.Role;
import nl.moj.server.config.WebSecurityConfiguration;

@SpringJUnitWebConfig(WebSecurityConfigurationTest.TestConfig.class)
class WebSecurityConfigurationTest {
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private WebSecurityConfiguration security;
    @Autowired
    private JwtDecoder decoder;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void mapsKeycloakRolesFromOidcAndJwtMaps() {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("sub", "user-id");
        claims.put("preferred_username", "participant");
        claims.put("azp", "moj");
        claims.put("realm_access", new LinkedHashMap<>(Map.of("roles", List.of("user"))));
        claims.put("resource_access", new LinkedHashMap<>(Map.of(
                "moj", new LinkedHashMap<>(Map.of("roles", List.of("game_master"))),
                "another-client", Map.of("roles", List.of("admin")))));
        var idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60), claims);
        var oidc = security.keycloakOidcAuthoritiesMapper().mapAuthorities(List.of(new OidcUserAuthority(idToken)));
        var jwt = security.customJwtAuthenticationConverter().convert(token(claims));
        assertEquals(Set.of("ROLE_USER", "ROLE_GAME_MASTER"), names(oidc));
        assertEquals(names(oidc), names(jwt.getAuthorities()));
        assertEquals("participant", jwt.getName());
    }

    @Test
    void absentRolesAndUnrelatedClientDoNotGrantAccess() {
        var claims = Map.<String, Object> of("sub", "user-id", "preferred_username", "participant", "azp", "moj",
                "realm_access", Map.of(), "resource_access", Map.of("another-client", Map.of("roles", List.of("admin"))));
        assertEquals(Set.of(), names(security.customJwtAuthenticationConverter().convert(token(claims)).getAuthorities()));
        var idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(60), claims);
        assertEquals(Set.of(),
                names(security.keycloakOidcAuthoritiesMapper().mapAuthorities(List.of(new OidcUserAuthority(idToken)))));
    }

    @Test
    void participantCannotAccessControlWithBearerToken() throws Exception {
        when(decoder.decode("participant-token")).thenReturn(roleToken("user"));
        mvc.perform(get("/control").header("Authorization", "Bearer participant-token")).andExpect(status().isForbidden());
        mvc.perform(get("/play").header("Authorization", "Bearer participant-token")).andExpect(status().isOk());
    }

    @Test
    void administratorCanAccessControlWithBearerToken() throws Exception {
        when(decoder.decode("admin-token")).thenReturn(roleToken("admin"));
        mvc.perform(get("/control").header("Authorization", "Bearer admin-token")).andExpect(status().isOk());
    }

    @Test
    void oidcSessionUsesSameControlPolicy() throws Exception {
        mvc.perform(get("/control").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/control").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void methodRolesUseExistingPrefixedAuthorities() throws Exception {
        when(decoder.decode("method-admin-token")).thenReturn(roleToken("admin"));
        when(decoder.decode("method-user-token")).thenReturn(roleToken("user"));
        mvc.perform(get("/api/admin-test").header("Authorization", "Bearer method-admin-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin-test").header("Authorization", "Bearer method-user-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublicAndControlRequiresLogin() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/control").accept("text/html")).andExpect(status().is3xxRedirection());
    }

    private Jwt roleToken(String role) {
        return token(Map.of("sub", "user-id", "preferred_username", "participant", "azp", "moj",
                "resource_access", Map.of("moj", Map.of("roles", List.of(role)))));
    }

    private Jwt token(Map<String, Object> claims) {
        return Jwt.withTokenValue("token").header("alg", "RS256").claims(c -> c.putAll(claims))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private Set<String> names(java.util.Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).filter(name -> name.startsWith("ROLE_"))
                .collect(Collectors.toSet());
    }

    @Configuration
    @EnableWebMvc
    @Import({ WebSecurityConfiguration.class, ProbeController.class })
    static class TestConfig {
        @Bean
        JwtDecoder decoder() {
            return mock(JwtDecoder.class);
        }

        @Bean
        ClientRegistrationRepository clients() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("keycloak")
                    .clientId("moj").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/keycloak")
                    .authorizationUri("https://auth.example/authorize").tokenUri("https://auth.example/token")
                    .jwkSetUri("https://auth.example/jwks").userInfoUri("https://auth.example/userinfo")
                    .userNameAttributeName("preferred_username").scope("openid").build());
        }
    }

    @RestController
    static class ProbeController {
        @RolesAllowed(Role.ADMIN)
        @GetMapping("/api/admin-test")
        public String admin() {
            return "ok";
        }

        @GetMapping({ "/control", "/play", "/actuator/health" })
        public String ok() {
            return "ok";
        }
    }
}

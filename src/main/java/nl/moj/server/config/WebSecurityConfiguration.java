package nl.moj.server.config;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.core.authority.mapping.SimpleAuthorityMapper;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import lombok.RequiredArgsConstructor;
import nl.moj.server.authorization.Role;

@Configuration
@EnableMethodSecurity(jsr250Enabled = true)
@EnableWebSecurity
@RequiredArgsConstructor
public class WebSecurityConfiguration {

    private final ClientRegistrationRepository clientRegistrationRepository;

    @Bean
    static GrantedAuthorityDefaults grantedAuthorityDefaults() {
        // Existing @RolesAllowed values already include ROLE_.
        return new GrantedAuthorityDefaults("");
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler() {
        OidcClientInitiatedLogoutSuccessHandler successHandler = new OidcClientInitiatedLogoutSuccessHandler(
                clientRegistrationRepository);
        successHandler.setPostLogoutRedirectUri("{baseUrl}");
        return successHandler;
    }

    @Bean //(name = BeanIds.SPRING_SECURITY_FILTER_CHAIN)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/", "/actuator/health", "/error", "/public/**", "/manifest.json", "/browserconfig.xml",
                        "/favicon.ico", "/api/assignment/*/content")
                .permitAll()
                .requestMatchers("/play", "/feedback", "/rankings")
                .hasAnyAuthority(Role.USER, Role.GAME_MASTER, Role.ADMIN) // always access
                .requestMatchers("/control", "/bootstrap", "/assignmentAdmin")
                .hasAnyAuthority(Role.GAME_MASTER, Role.ADMIN) // only facilitators
                .anyRequest()
                .authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(customJwtAuthenticationConverter())))
                .headers(h -> h.frameOptions(f -> f.disable()))
                .csrf(AbstractHttpConfigurer::disable)
                .logout(l -> l.logoutSuccessHandler(oidcLogoutSuccessHandler()))
                .oauth2Login(org.springframework.security.config.Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public GrantedAuthoritiesMapper keycloakOidcAuthoritiesMapper() {
        return authorities -> {
            Set<GrantedAuthority> mappedAuthorities = new HashSet<>();
            authorities.forEach(authority -> {
                if (authority instanceof OidcUserAuthority oidcUserAuthority) {
                    OidcIdToken idToken = oidcUserAuthority.getIdToken();
                    mappedAuthorities.addAll(keycloakAuthorities(idToken.getClaims()));
                }
            });
            return mappedAuthorities;
        };
    }

    @Bean
    public JwtAuthenticationConverter customJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> keycloakAuthorities(jwt.getClaims()));
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    private Collection<GrantedAuthority> keycloakAuthorities(Map<String, Object> claims) {
        Set<GrantedAuthority> authorities = new HashSet<>();
        addRoles(authorities, claims.get("realm_access"));
        if (claims.get("resource_access") instanceof Map<?, ?> resourceAccess) {
            addRoles(authorities, resourceAccess.get(claims.get("azp")));
        }
        SimpleAuthorityMapper mapper = new SimpleAuthorityMapper();
        mapper.setPrefix("ROLE_");
        mapper.setConvertToUpperCase(true);
        return mapper.mapAuthorities(authorities);
    }

    private void addRoles(Set<GrantedAuthority> authorities, Object access) {
        if (access instanceof Map<?, ?> rolesMap && rolesMap.get("roles") instanceof Collection<?> roles) {
            roles.stream().filter(String.class::isInstance).map(String.class::cast)
                    .map(SimpleGrantedAuthority::new).forEach(authorities::add);
        }
    }
}

package nl.moj.server.bootstrap.filter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.FilterChain;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import nl.moj.common.bootstrap.BootstrapService;

@ExtendWith(MockitoExtension.class)
class BootstrapFilterTest {

    @Mock
    private BootstrapService service;

    @InjectMocks
    private BootstrapFilter filter;

    @Test
    void healthCheckBypassesBootstrapOnFreshInstallation() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setServletPath("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(service, never()).isBootstrapNeeded();
    }

    @Test
    void testDoFilterNoBootstrapAndNoUri() throws Exception {
        when(service.isBootstrapNeeded()).thenReturn(false);

        FilterChain filterChain = mock(FilterChain.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getRequestURI()).thenReturn("");

        filter.doFilterInternal(servletRequest, null, filterChain);

        verify(filterChain).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void testDoFilterNoBootstrapButUri() throws Exception {
        when(service.isBootstrapNeeded()).thenReturn(false);
        FilterChain filterChain = mock(FilterChain.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getRequestURI()).thenReturn("bootstrap");

        filter.doFilterInternal(servletRequest, null, filterChain);

        verify(filterChain).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void testDoFilterDoBootstrap() throws Exception {
        when(service.isBootstrapNeeded()).thenReturn(true);
        FilterChain filterChain = mock(FilterChain.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getRequestURI()).thenReturn("bootstrap");
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);
        ServletContext servletContext = mock(ServletContext.class);
        when(servletContext.getContextPath()).thenReturn("");
        filter.setServletContext(servletContext);

        filter.doFilterInternal(servletRequest, servletResponse, filterChain);

        verify(servletResponse).sendRedirect("/bootstrap");
        verify(filterChain, never()).doFilter(Mockito.any(), Mockito.any());
    }
}

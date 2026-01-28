package com.axonect.aee.template.baseapp.application.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestCheckFilterTest {

    @Test
    void testDoFilter_IgnoreRoute() throws ServletException, IOException {
        FilterConfig filterConfig = mock(FilterConfig.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain filterChain = mock(FilterChain.class);

        when(request.getHeaders(any())).thenReturn(Collections.emptyEnumeration());
        when(request.getPathInfo()).thenReturn("/actuator/prometheus");

        RequestCheckFilter requestCheckFilter = new RequestCheckFilter();

        requestCheckFilter.init(filterConfig);
        requestCheckFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void testDoFilter_NotIgnoreRoute() throws ServletException, IOException {
        FilterConfig filterConfig = mock(FilterConfig.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain filterChain = mock(FilterChain.class);

        when(request.getHeaders(any())).thenReturn(Collections.emptyEnumeration());
        when(request.getPathInfo()).thenReturn("/some-other-route");

        RequestCheckFilter requestCheckFilter = new RequestCheckFilter();

        requestCheckFilter.init(filterConfig);
        requestCheckFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}

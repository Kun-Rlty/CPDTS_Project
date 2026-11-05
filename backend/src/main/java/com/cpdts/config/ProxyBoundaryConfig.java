package com.cpdts.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the proxy-boundary filter before Spring Security and MVC so no
 * request with spoofed forwarded headers can reach authentication, CSRF or
 * controller code.
 */
@Configuration(proxyBeanMethods = false)
public class ProxyBoundaryConfig {

    @Bean
    public FilterRegistrationBean<TrustedProxyHeadersFilter> trustedProxyHeadersFilter(
            @Value("${cpdts.proxy.token:}") String proxyToken) {
        FilterRegistrationBean<TrustedProxyHeadersFilter> registration =
                new FilterRegistrationBean<>(new TrustedProxyHeadersFilter(proxyToken));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}

package com.cpdts.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TrustedProxyHeadersFilterTest {

    private static final String TOKEN = "test-proxy-boundary-token";

    @Test
    void forwardsOrdinaryRequestsWithoutAConfiguredToken() throws Exception {
        RecordingChain chain = new RecordingChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TrustedProxyHeadersFilter(" ").doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void forwardsRequestsWithoutForwardedHeaders() throws Exception {
        RecordingChain chain = new RecordingChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TrustedProxyHeadersFilter(TOKEN).doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
    }

    @Test
    void rejectsSpoofedForwardedProtocolWithoutTheProxyToken() throws Exception {
        assertRejected(request -> request.addHeader("X-Forwarded-Proto", "https"));
    }

    @Test
    void rejectsSpoofedForwardedAddressWithoutTheProxyToken() throws Exception {
        assertRejected(request -> request.addHeader("X-Forwarded-For", "203.0.113.9"));
    }

    @Test
    void rejectsSpoofedForwardedHeaderWithoutTheProxyToken() throws Exception {
        assertRejected(request -> request.addHeader("Forwarded", "for=203.0.113.9;proto=https"));
    }

    @Test
    void rejectsWrongProxyToken() throws Exception {
        assertRejected(request -> {
            request.addHeader("X-Forwarded-Proto", "https");
            request.addHeader(TrustedProxyHeadersFilter.PROXY_TOKEN_HEADER, "wrong");
        });
    }

    @Test
    void acceptsForwardedHeadersFromTheConfiguredProxy() throws Exception {
        RecordingChain chain = new RecordingChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        request.addHeader(TrustedProxyHeadersFilter.PROXY_TOKEN_HEADER, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TrustedProxyHeadersFilter(TOKEN).doFilter(request, response, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static void assertRejected(java.util.function.Consumer<MockHttpServletRequest> scenario)
            throws Exception {
        RecordingChain chain = new RecordingChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
        scenario.accept(request);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TrustedProxyHeadersFilter(TOKEN).doFilter(request, response, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("untrusted_forwarded_headers");
    }

    private static final class RecordingChain implements FilterChain {
        private boolean invoked;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                jakarta.servlet.ServletResponse response) {
            invoked = true;
        }
    }
}

package dev.wrenz.stockroom.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void withoutAHeaderUsesTheSocketAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("192.0.2.10");
    }

    @Test
    void theHeaderIsIgnoredUnlessWeAreBehindTheTrustedProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(ClientIpResolver.resolve(request, false)).isEqualTo("192.0.2.10");
    }

    @Test
    void usesTheLastEntryBecauseEarlierOnesCanBeForged() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.9");
        assertThat(ClientIpResolver.resolve(request, true)).isEqualTo("203.0.113.9");
    }

    @Test
    void stripsThePortAzureAppends() {
        MockHttpServletRequest ipv4 = new MockHttpServletRequest();
        ipv4.addHeader("X-Forwarded-For", "203.0.113.9:54321");
        assertThat(ClientIpResolver.resolve(ipv4, true)).isEqualTo("203.0.113.9");

        MockHttpServletRequest ipv6 = new MockHttpServletRequest();
        ipv6.addHeader("X-Forwarded-For", "[2001:db8::1]:443");
        assertThat(ClientIpResolver.resolve(ipv6, true)).isEqualTo("2001:db8::1");

        MockHttpServletRequest bareIpv6 = new MockHttpServletRequest();
        bareIpv6.addHeader("X-Forwarded-For", "2001:db8::1");
        assertThat(ClientIpResolver.resolve(bareIpv6, true)).isEqualTo("2001:db8::1");
    }
}

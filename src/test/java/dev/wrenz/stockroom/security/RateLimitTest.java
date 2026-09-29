package dev.wrenz.stockroom.security;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Simulates production, where the app sits behind Azure's front end (the trusted proxy). */
@TestPropertySource(properties = "stockroom.rate-limit.trust-forwarded-for=true")
class RateLimitTest extends IntegrationTest {

    @Test
    void fiveFailuresLockThatEmailEvenForTheRightPassword() throws Exception {
        api.registerShop("Shop", "owner@shop.test");
        for (int i = 0; i < 5; i++) {
            login("owner@shop.test", "wrong-password", null).andExpect(status().isUnauthorized());
        }
        login("owner@shop.test", "wrong-password", null)
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        login("owner@shop.test", TestApi.PASSWORD, null).andExpect(status().isTooManyRequests());
    }

    @Test
    void oneIpCannotSprayManyEmails() throws Exception {
        for (int i = 0; i < 30; i++) {
            login("user" + i + "@shop.test", "wrong-password", null).andExpect(status().isUnauthorized());
        }
        login("user31@shop.test", "wrong-password", null).andExpect(status().isTooManyRequests());
    }

    @Test
    void forgedLeadingForwardedForValuesAreIgnored() throws Exception {
        for (int i = 0; i < 30; i++) {
            login("user" + i + "@shop.test", "wrong-password", "10.0.0." + i + ", 203.0.113.9")
                    .andExpect(status().isUnauthorized());
        }
        // The real client (last entry) is still 203.0.113.9, whatever the first entry claims.
        login("user31@shop.test", "wrong-password", "10.9.9.9, 203.0.113.9").andExpect(status().isTooManyRequests());
        // A different real client is unaffected.
        login("user32@shop.test", "wrong-password", "203.0.113.9, 198.51.100.7").andExpect(status().isUnauthorized());
    }

    private ResultActions login(String email, String password, String forwardedFor) throws Exception {
        var request = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mvc.perform(request);
    }
}

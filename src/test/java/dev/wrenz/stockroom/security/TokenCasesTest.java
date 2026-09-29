package dev.wrenz.stockroom.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The five token cases from spec section 6. Each checks the status AND the ProblemDetail shape. */
class TokenCasesTest extends IntegrationTest {

    @Autowired JwtEncoder realEncoder;

    @Test
    void missingToken() throws Exception {
        expectProblem(mvc.perform(get("/api/users")), 401, "UNAUTHORIZED");
    }

    @Test
    void malformedToken() throws Exception {
        expectProblem(mvc.perform(get("/api/users").header("Authorization", "Bearer not-a-jwt")), 401, "UNAUTHORIZED");
    }

    @Test
    void tokenSignedWithAnotherSecret() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        JwtEncoder attacker = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "an-attacker-secret-also-over-32-bytes!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        String forged = encode(attacker, owner, Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS));
        expectProblem(mvc.perform(get("/api/users").header("Authorization", "Bearer " + forged)), 401, "UNAUTHORIZED");
    }

    @Test
    void expiredToken() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        String expired = encode(realEncoder, owner, twoHoursAgo, twoHoursAgo.plus(1, ChronoUnit.HOURS));
        expectProblem(mvc.perform(get("/api/users").header("Authorization", "Bearer " + expired)), 401, "UNAUTHORIZED");
    }

    /** Swagger keeps sending the last token; an expired one must never block getting a new one. */
    @Test
    void anExpiredTokenDoesNotBlockLoginOrRegister() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS);
        String expired = "Bearer " + encode(realEncoder, owner, twoHoursAgo, twoHoursAgo.plus(1, ChronoUnit.HOURS));
        mvc.perform(post("/api/auth/login").header("Authorization", expired).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@shop.test\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/register").header("Authorization", "Bearer garbage").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopName\":\"Two\",\"email\":\"two@shop.test\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void staffOnAnOwnerEndpoint() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        TestApi.Session staff = api.createStaff(owner, "staff@shop.test");
        expectProblem(mvc.perform(post("/api/users").header("Authorization", staff.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@shop.test\",\"password\":\"password123\"}")), 403, "FORBIDDEN");
    }

    private static String encode(JwtEncoder encoder, TestApi.Session s, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(Long.toString(s.userId()))
                .issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("shopId", s.shopId()).claim("role", "OWNER")
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static void expectProblem(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.code").value(code));
    }
}

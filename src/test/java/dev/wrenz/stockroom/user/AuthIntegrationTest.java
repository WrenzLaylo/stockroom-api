package dev.wrenz.stockroom.user;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthIntegrationTest extends IntegrationTest {

    @Test
    void registerCreatesShopAndOwnerAndReturnsAToken() throws Exception {
        register("Aling Nena Store", "nena@shop.test", TestApi.PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.role").value("OWNER"));
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user", String.class)).startsWith("$2a$10$");
    }

    @Test
    void emailIsTrimmedAndCaseInsensitive() throws Exception {
        register("Shop", "  Owner@Shop.test ", TestApi.PASSWORD).andExpect(status().isCreated());
        api.login("owner@shop.test", TestApi.PASSWORD);
        login("  OWNER@shop.TEST", TestApi.PASSWORD).andExpect(status().isOk());
        register("Other", "owner@SHOP.test", TestApi.PASSWORD)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop", Integer.class)).isEqualTo(1);
    }

    @Test
    void passwordLimitIsCountedInBytes() throws Exception {
        register("A", "a@shop.test", "a".repeat(72)).andExpect(status().isCreated());
        register("B", "b@shop.test", "a".repeat(73)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        register("C", "c@shop.test", "é".repeat(40)).andExpect(status().isBadRequest());
        register("D", "d@shop.test", "short").andExpect(status().isBadRequest());
    }

    @Test
    void wrongPasswordAndUnknownEmailGetIdenticalResponses() throws Exception {
        api.registerShop("Shop", "owner@shop.test");
        String wrongPassword = login("owner@shop.test", "not-the-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nobody@shop.test", "not-the-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void ownerAddsStaffWhoCanLogInAndBothAppearInTheList() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        TestApi.Session staff = api.createStaff(owner, "staff@shop.test");
        assertThat(staff.shopId()).isEqualTo(owner.shopId());
        mvc.perform(get("/api/users").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].role").value("STAFF"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void theDemoEmailDomainIsReservedForTheSeeder() throws Exception {
        register("Demo Sari-Sari Store", "owner@demo.stockroom.dev", TestApi.PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        mvc.perform(post("/api/users").header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"staff@DEMO.stockroom.dev\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email LIKE '%@demo.stockroom.dev'", Integer.class)).isZero();
    }

    @Test
    void staffEmailMustBeUnique() throws Exception {
        TestApi.Session owner = api.registerShop("Shop", "owner@shop.test");
        mvc.perform(post("/api/users").header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@shop.test\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    private ResultActions register(String shop, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"shopName":"%s","email":"%s","password":"%s"}
                """.formatted(shop, email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, password)));
    }
}

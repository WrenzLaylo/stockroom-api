package dev.wrenz.stockroom.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Small HTTP helpers so tests read as scenarios. Every call goes through the real API. */
public final class TestApi {

    public static final String PASSWORD = "password123";

    public record Session(String token, long userId, long shopId) {
        public String bearer() { return "Bearer " + token; }
    }

    private final MockMvc mvc;

    public TestApi(MockMvc mvc) {
        this.mvc = mvc;
    }

    public Session registerShop(String shopName, String email) throws Exception {
        String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"shopName":"%s","email":"%s","password":"%s"}
                        """.formatted(shopName, email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return session(json);
    }

    public Session login(String email, String password) throws Exception {
        String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return session(json);
    }

    public Session createStaff(Session owner, String email) throws Exception {
        mvc.perform(post("/api/users").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        return login(email, PASSWORD);
    }

    public long createProduct(Session owner, String sku, String price, int stock) throws Exception {
        String json = mvc.perform(post("/api/products").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"sku":"%s","name":"Product %s","price":%s,"initialStock":%d}
                                """.formatted(sku, sku, price, stock)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    public int stockOf(Session session, long productId) throws Exception {
        String json = mvc.perform(get("/api/products/" + productId).header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.stockQty");
    }

    public long placeOrder(Session session, long productId, int quantity) throws Exception {
        String json = mvc.perform(post("/api/orders").header("Authorization", session.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"items":[{"productId":%d,"quantity":%d}]}
                                """.formatted(productId, quantity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static Session session(String json) {
        return new Session(JsonPath.read(json, "$.accessToken"),
                ((Number) JsonPath.read(json, "$.userId")).longValue(),
                ((Number) JsonPath.read(json, "$.shopId")).longValue());
    }
}

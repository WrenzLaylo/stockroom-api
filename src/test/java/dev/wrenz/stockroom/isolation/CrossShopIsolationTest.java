package dev.wrenz.stockroom.isolation;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec section 8: shop B tries to read and change shop A's data. Owners get 404 everywhere;
 * staff get 404 where staff are allowed and 403 on owner-only endpoints (the role check runs
 * before any lookup, so 403 reveals nothing). Shop A must be untouched afterwards.
 */
class CrossShopIsolationTest extends IntegrationTest {

    TestApi.Session ownerA, ownerB, staffB;
    long productA, orderA;
    String shopABefore;

    @BeforeEach
    void twoShops() throws Exception {
        ownerA = api.registerShop("Shop A", "a@shop.test");
        ownerB = api.registerShop("Shop B", "b@shop.test");
        staffB = api.createStaff(ownerB, "staff@b.test");
        productA = api.createProduct(ownerA, "A-1", "10.00", 5);
        orderA = api.placeOrder(ownerA, productA, 1);
        shopABefore = shopAState();
    }

    @AfterEach
    void shopAIsUntouched() {
        assertThat(shopAState()).isEqualTo(shopABefore);
    }

    // One test per operation, so a leak in any single path fails its own test (and @AfterEach
    // reports any change to shop A) instead of being hidden behind an earlier assertion.

    @Test
    void ownerBCannotReadAProduct() throws Exception {
        notFound(get("/api/products/" + productA), ownerB);
    }

    @Test
    void ownerBCannotEditAProduct() throws Exception {
        notFound(put("/api/products/" + productA).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"HACK\",\"name\":\"Hacked\",\"price\":0.00,\"lowStockThreshold\":0}"), ownerB);
    }

    @Test
    void ownerBCannotArchiveAProduct() throws Exception {
        notFound(delete("/api/products/" + productA), ownerB);
    }

    @Test
    void ownerBCannotRestockAProduct() throws Exception {
        notFound(post("/api/products/" + productA + "/restock").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":100}"), ownerB);
    }

    @Test
    void ownerBCannotReadStockHistory() throws Exception {
        notFound(get("/api/products/" + productA + "/movements"), ownerB);
    }

    @Test
    void ownerBCannotOrderAnotherShopsProduct() throws Exception {
        notFound(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(productA)), ownerB);
    }

    @Test
    void ownerBCannotReadAnOrder() throws Exception {
        notFound(get("/api/orders/" + orderA), ownerB);
    }

    @Test
    void ownerBCannotCancelAnOrder() throws Exception {
        notFound(post("/api/orders/" + orderA + "/cancel"), ownerB);
    }

    @Test
    void staffOfShopBGet404WhereStaffMayGoAnd403Elsewhere() throws Exception {
        notFound(get("/api/products/" + productA), staffB);
        notFound(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(productA)), staffB);
        notFound(get("/api/orders/" + orderA), staffB);
        forbidden(delete("/api/products/" + productA), staffB);
        forbidden(post("/api/products/" + productA + "/restock").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":100}"), staffB);
        forbidden(post("/api/orders/" + orderA + "/cancel"), staffB);
    }

    @Test
    void listsOnlyShowYourOwnShop() throws Exception {
        mvc.perform(get("/api/products").header("Authorization", ownerB.bearer()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/orders").header("Authorization", ownerB.bearer()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/users").header("Authorization", ownerB.bearer()))
                .andExpect(jsonPath("$.length()").value(2));
    }

    private void notFound(MockHttpServletRequestBuilder request, TestApi.Session as) throws Exception {
        expect(mvc.perform(request.header("Authorization", as.bearer())), 404, "NOT_FOUND");
    }

    private void forbidden(MockHttpServletRequestBuilder request, TestApi.Session as) throws Exception {
        expect(mvc.perform(request.header("Authorization", as.bearer())), 403, "FORBIDDEN");
    }

    private static void expect(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    private String shopAState() {
        return jdbc.queryForObject("""
                SELECT p.stock_qty || '|' || p.name || '|' || p.active || '|' || p.price
                       || '|' || (SELECT count(*) FROM stock_movement WHERE shop_id = p.shop_id)
                       || '|' || (SELECT string_agg(status, ',' ORDER BY id) FROM customer_order WHERE shop_id = p.shop_id)
                FROM product p WHERE p.id = ?
                """, String.class, productA);
    }
}

package dev.wrenz.stockroom.order;

import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderIntegrationTest extends IntegrationTest {

    TestApi.Session owner;
    TestApi.Session staff;
    long noodles;   // 12.50, stock 10
    long soap;      // 3.00, stock 2

    @BeforeEach
    void shop() throws Exception {
        owner = api.registerShop("Shop", "owner@shop.test");
        staff = api.createStaff(owner, "staff@shop.test");
        noodles = api.createProduct(owner, "NOODLES", "12.50", 10);
        soap = api.createProduct(owner, "SOAP", "3.00", 2);
    }

    @Test
    void staffPlacesAMultiLineOrder() throws Exception {
        order(staff, "{\"items\":[{\"productId\":%d,\"quantity\":2},{\"productId\":%d,\"quantity\":1}]}".formatted(soap, noodles))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.total").value(18.5))
                .andExpect(jsonPath("$.createdBy").value((int) staff.userId()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value((int) noodles))
                .andExpect(jsonPath("$.items[0].lineTotal").value(12.5));
        assertThat(api.stockOf(owner, noodles)).isEqualTo(9);
        assertThat(api.stockOf(owner, soap)).isZero();
        assertThat(count("SELECT count(*) FROM stock_movement WHERE reason = 'SALE'")).isEqualTo(2);
    }

    @Test
    void insufficientStockRejectsTheWholeOrderAndChangesNothing() throws Exception {
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":1},{\"productId\":%d,\"quantity\":3}]}".formatted(noodles, soap))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.productId").value((int) soap))
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.available").value(2));
        assertThat(api.stockOf(owner, noodles)).isEqualTo(10);   // first line rolled back
        assertThat(count("SELECT count(*) FROM customer_order")).isZero();
        assertThat(count("SELECT count(*) FROM stock_movement WHERE reason = 'SALE'")).isZero();
    }

    @Test
    void invalidOrdersAre400() throws Exception {
        order(owner, "{\"items\":[]}").andExpect(status().isBadRequest());
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":0}]}".formatted(noodles)).andExpect(status().isBadRequest());
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":1001}]}".formatted(noodles)).andExpect(status().isBadRequest());
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":1},{\"productId\":%d,\"quantity\":1}]}".formatted(noodles, noodles))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("items"));
    }

    @Test
    void malformedBodyIs400() throws Exception {
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":\"two\"}]}".formatted(noodles))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        order(owner, "{\"items\":[").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":1.9}]}".formatted(noodles)).andExpect(status().isBadRequest());
        order(owner, "{\"items\":[{\"productId\":%d.7,\"quantity\":1}]}".formatted(noodles)).andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM customer_order")).isZero();
        assertThat(api.stockOf(owner, noodles)).isEqualTo(10);
    }

    @Test
    void nullItemIs400WithNoWrites() throws Exception {
        order(owner, "{\"items\":[null]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(count("SELECT count(*) FROM customer_order")).isZero();
    }

    @Test
    void theLargestAllowedOrderTotalFits() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            long id = api.createProduct(owner, "LUX-" + i, "999999.99", 1000);
            items.append(i == 0 ? "" : ",").append("{\"productId\":%d,\"quantity\":1000}".formatted(id));
        }
        order(owner, "{\"items\":[" + items + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(10_999_999_890.00));
    }

    @Test
    void unknownAndArchivedProductsAre404() throws Exception {
        order(owner, "{\"items\":[{\"productId\":999,\"quantity\":1}]}").andExpect(status().isNotFound());
        mvc.perform(delete("/api/products/" + soap).header("Authorization", owner.bearer())).andExpect(status().isNoContent());
        order(owner, "{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(soap))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void laterPriceChangesDoNotRewriteOldOrders() throws Exception {
        long orderId = api.placeOrder(owner, noodles, 1);
        mvc.perform(put("/api/products/" + noodles).header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"NOODLES\",\"name\":\"Noodles\",\"price\":99.00,\"lowStockThreshold\":5}")).andExpect(status().isOk());
        mvc.perform(get("/api/orders/" + orderId).header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.items[0].unitPrice").value(12.5))
                .andExpect(jsonPath("$.total").value(12.5));
    }

    @Test
    void listIsNewestFirst() throws Exception {
        long first = api.placeOrder(owner, noodles, 1);
        long second = api.placeOrder(owner, noodles, 1);
        mvc.perform(get("/api/orders").header("Authorization", staff.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value((int) second))
                .andExpect(jsonPath("$.content[1].id").value((int) first));
    }

    @Test
    void cancelReturnsStockOnceEvenForArchivedProducts() throws Exception {
        long orderId = api.placeOrder(owner, soap, 2);
        mvc.perform(delete("/api/products/" + soap).header("Authorization", owner.bearer())).andExpect(status().isNoContent());
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString());
        assertThat(count("SELECT stock_qty FROM product WHERE id = " + soap)).isEqualTo(2);
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", owner.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
        assertThat(count("SELECT stock_qty FROM product WHERE id = " + soap)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM stock_movement WHERE reason = 'CANCEL'")).isEqualTo(1);
    }

    @Test
    void staffCannotCancel() throws Exception {
        long orderId = api.placeOrder(owner, noodles, 1);
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", staff.bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownOrderIs404() throws Exception {
        mvc.perform(get("/api/orders/999").header("Authorization", owner.bearer())).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/999/cancel").header("Authorization", owner.bearer())).andExpect(status().isNotFound());
    }

    private ResultActions order(TestApi.Session s, String body) throws Exception {
        return mvc.perform(post("/api/orders").header("Authorization", s.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}

package dev.wrenz.stockroom.product;

import dev.wrenz.stockroom.stock.StockRepository;
import dev.wrenz.stockroom.support.IntegrationTest;
import dev.wrenz.stockroom.support.TestApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProductIntegrationTest extends IntegrationTest {

    TestApi.Session owner;
    TestApi.Session staff;

    @BeforeEach
    void shop() throws Exception {
        owner = api.registerShop("Shop", "owner@shop.test");
        staff = api.createStaff(owner, "staff@shop.test");
    }

    @Test
    void createRecordsOpeningStockInTheLedger() throws Exception {
        mvc.perform(post("/api/products").header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"  RICE-KG \",\"name\":\" Rice \",\"price\":52.50,\"initialStock\":20}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("RICE-KG"))
                .andExpect(jsonPath("$.name").value("Rice"))
                .andExpect(jsonPath("$.price").value(52.5))
                .andExpect(jsonPath("$.stockQty").value(20))
                .andExpect(jsonPath("$.lowStockThreshold").value(5))
                .andExpect(jsonPath("$.lowStock").value(false))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(jdbc.queryForObject("SELECT change FROM stock_movement WHERE reason = 'OPENING_STOCK'", Integer.class)).isEqualTo(20);
    }

    @Test
    void zeroOpeningStockWritesNoMovement() throws Exception {
        api.createProduct(owner, "EMPTY", "1.00", 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Integer.class)).isZero();
    }

    @Test
    void skuIsUniqueWithinAShopButNotAcrossShops() throws Exception {
        api.createProduct(owner, "SKU-1", "1.00", 0);
        create(owner, "{\"sku\":\"SKU-1\",\"name\":\"Dup\",\"price\":1.00}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
        TestApi.Session other = api.registerShop("Other", "other@shop.test");
        api.createProduct(other, "SKU-1", "1.00", 0);
    }

    @Test
    void invalidPricesAre400() throws Exception {
        create(owner, "{\"sku\":\"A\",\"name\":\"A\",\"price\":12.345}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].field").value("price"));
        create(owner, "{\"sku\":\"B\",\"name\":\"B\",\"price\":-1}").andExpect(status().isBadRequest());
        create(owner, "{\"sku\":\"C\",\"name\":\"C\",\"price\":1e15}").andExpect(status().isBadRequest());
        create(owner, "{\"sku\":\"E\",\"name\":\"E\",\"price\":1000000.00}").andExpect(status().isBadRequest());
        create(owner, "{\"sku\":\"F\",\"name\":\"F\",\"price\":999999.99}").andExpect(status().isCreated());
        jdbc.update("DELETE FROM stock_movement"); jdbc.update("DELETE FROM product");
        create(owner, "{\"sku\":\"D\",\"name\":\"   \",\"price\":1.00}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product", Integer.class)).isZero();
    }

    @Test
    void fractionalWholeNumbersAre400NotTruncated() throws Exception {
        create(owner, "{\"sku\":\"A\",\"name\":\"A\",\"price\":1.00,\"initialStock\":1.5}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create(owner, "{\"sku\":\"B\",\"name\":\"B\",\"price\":1.00,\"lowStockThreshold\":2.5}")
                .andExpect(status().isBadRequest());
        long id = api.createProduct(owner, "C", "1.00", 5);
        mvc.perform(post("/api/products/" + id + "/restock").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1.5}"))
                .andExpect(status().isBadRequest());
        assertThat(api.stockOf(owner, id)).isEqualTo(5);
    }

    @Test
    void restockBeyondTheStockLimitIs409() throws Exception {
        long id = api.createProduct(owner, "BIG", "1.00", 0);
        jdbc.update("UPDATE product SET stock_qty = ? WHERE id = ?", StockRepository.MAX_STOCK, id);
        mvc.perform(post("/api/products/" + id + "/restock").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STOCK_LIMIT_EXCEEDED"));
    }

    @Test
    void updateNeverChangesStockEvenIfTheBodyTriesTo() throws Exception {
        long id = api.createProduct(owner, "SKU-1", "10.00", 5);
        mvc.perform(put("/api/products/" + id).header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1\",\"name\":\"New name\",\"price\":11.00,\"lowStockThreshold\":2,\"stockQty\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New name"))
                .andExpect(jsonPath("$.stockQty").value(5));
    }

    @Test
    void updateToAnExistingSkuIs409() throws Exception {
        api.createProduct(owner, "SKU-1", "1.00", 0);
        long second = api.createProduct(owner, "SKU-2", "1.00", 0);
        mvc.perform(put("/api/products/" + second).header("Authorization", owner.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1\",\"name\":\"x\",\"price\":1.00,\"lowStockThreshold\":5}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    }

    @Test
    void restockAddsStockAndShowsInHistoryNewestFirst() throws Exception {
        long id = api.createProduct(owner, "SKU-1", "10.00", 5);
        mvc.perform(post("/api/products/" + id + "/restock").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":10,\"note\":\"Delivery\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQty").value(15));
        mvc.perform(get("/api/products/" + id + "/movements").header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].reason").value("RESTOCK"))
                .andExpect(jsonPath("$.content[0].note").value("Delivery"))
                .andExpect(jsonPath("$.content[1].reason").value("OPENING_STOCK"));
        mvc.perform(post("/api/products/" + id + "/restock").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void archivedProductsAreHiddenAndCannotBeRestocked() throws Exception {
        long id = api.createProduct(owner, "SKU-1", "10.00", 5);
        mvc.perform(delete("/api/products/" + id).header("Authorization", owner.bearer())).andExpect(status().isNoContent());
        list(owner, "").andExpect(jsonPath("$.totalElements").value(0));
        list(owner, "?includeArchived=true").andExpect(jsonPath("$.content[0].active").value(false));
        list(staff, "?includeArchived=true").andExpect(status().isForbidden());
        mvc.perform(post("/api/products/" + id + "/restock").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void lowStockFilterAndSearch() throws Exception {
        api.createProduct(owner, "LOW-1", "1.00", 2);
        api.createProduct(owner, "FULL-1", "1.00", 50);
        list(staff, "?lowStock=true").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("LOW-1"))
                .andExpect(jsonPath("$.content[0].lowStock").value(true));
        list(staff, "?q=full").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchTreatsWildcardsLiterally() throws Exception {
        api.createProduct(owner, "A-1", "1.00", 1);
        list(owner, "?q=%25").andExpect(jsonPath("$.totalElements").value(0));   // "%"
        list(owner, "?q=_").andExpect(jsonPath("$.totalElements").value(0));
        api.createProduct(owner, "B_1", "1.00", 1);
        list(owner, "?q=_").andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void pageSizeIsCappedAt100() throws Exception {
        list(owner, "?size=500").andExpect(jsonPath("$.size").value(100));
        list(owner, "?size=0&page=-3").andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void nonNumericIdIs400() throws Exception {
        mvc.perform(get("/api/products/abc").header("Authorization", owner.bearer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void staffCanReadButNotChangeProducts() throws Exception {
        long id = api.createProduct(owner, "SKU-1", "10.00", 5);
        mvc.perform(get("/api/products/" + id).header("Authorization", staff.bearer())).andExpect(status().isOk());
        create(staff, "{\"sku\":\"X\",\"name\":\"X\",\"price\":1.00}").andExpect(status().isForbidden());
        mvc.perform(delete("/api/products/" + id).header("Authorization", staff.bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/products/" + id + "/movements").header("Authorization", staff.bearer())).andExpect(status().isForbidden());
    }

    @Test
    void unknownProductIs404() throws Exception {
        mvc.perform(get("/api/products/999").header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private ResultActions create(TestApi.Session s, String body) throws Exception {
        return mvc.perform(post("/api/products").header("Authorization", s.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions list(TestApi.Session s, String query) throws Exception {
        return mvc.perform(get("/api/products" + query).header("Authorization", s.bearer()));
    }
}

package dev.wrenz.stockroom.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    record Body(@NotBlank String name) {}

    @RestController
    static class ThrowingController {
        @GetMapping("/not-found") void notFound() { throw new NotFoundException("Product", 7); }
        @GetMapping("/stock") void stock() { throw new InsufficientStockException(7, 3, 1); }
        @GetMapping("/validation") void validation() { throw new ValidationException("items", "product 7 appears more than once"); }
        @GetMapping("/dup-sku") void dupSku() { throw integrity("uq_product_shop_sku"); }
        @GetMapping("/dup-email") void dupEmail() { throw integrity("uq_app_user_email"); }
        @GetMapping("/other-integrity") void otherIntegrity() { throw integrity("fk_order_item_product"); }
        @GetMapping("/integrity-from-jdbc") void integrityFromJdbc() {
            // JdbcTemplate (used by StockRepository) raises this with no Hibernate cause, so no constraint name.
            throw new DataIntegrityViolationException("fk violation", new SQLException("violates foreign key"));
        }
        @GetMapping("/deadlock") void deadlock() { throw new CannotAcquireLockException("deadlock detected"); }
        @GetMapping("/boom") void boom() { throw new IllegalStateException("secret internal detail"); }
        @GetMapping("/denied") void denied() { throw new AccessDeniedException("nope"); }
        @GetMapping("/unauth") void unauth() { throw new BadCredentialsException("bad"); }
        @GetMapping("/typed/{id}") void typed(@PathVariable long id) {}
        @PostMapping("/body") void body(@Valid @RequestBody Body body) {}

        private static DataIntegrityViolationException integrity(String constraint) {
            return new DataIntegrityViolationException("could not execute statement",
                    new ConstraintViolationException("violation", new SQLException("violation"), constraint));
        }
    }

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void notFound() throws Exception {
        mvc.perform(get("/not-found")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Product 7 not found"));
    }

    @Test
    void insufficientStockCarriesNumbers() throws Exception {
        mvc.perform(get("/stock")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.productId").value(7))
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.available").value(1));
    }

    @Test
    void validationExceptionListsTheField() throws Exception {
        mvc.perform(get("/validation")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("items"));
    }

    @Test
    void uniqueConstraintsAreMappedByName() throws Exception {
        mvc.perform(get("/dup-sku")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
        mvc.perform(get("/dup-email")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void unknownIntegrityViolationIsA500NotAMisleading409() throws Exception {
        mvc.perform(get("/other-integrity")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void integrityViolationWithoutAConstraintNameIsStillAProblemDetail() throws Exception {
        mvc.perform(get("/integrity-from-jdbc")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void deadlockIsARetryable409() throws Exception {
        mvc.perform(get("/deadlock")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        mvc.perform(get("/boom")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret internal detail"))));
    }

    @Test
    void securityExceptionsThrownInControllers() throws Exception {
        mvc.perform(get("/denied")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/unauth")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void frameworkErrorsGetCodesToo() throws Exception {
        mvc.perform(get("/typed/abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }
}

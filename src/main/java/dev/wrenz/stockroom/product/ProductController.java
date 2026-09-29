package dev.wrenz.stockroom.product;

import dev.wrenz.stockroom.common.PageResponse;
import dev.wrenz.stockroom.security.AuthUser;
import dev.wrenz.stockroom.stock.MovementResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    public PageResponse<ProductResponse> list(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(defaultValue = "false") boolean lowStock,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "false") boolean includeArchived,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return products.list(AuthUser.from(jwt), lowStock, q, includeArchived, page, size);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return products.get(AuthUser.from(jwt), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    public ProductResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProductRequest request) {
        return products.create(AuthUser.from(jwt), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ProductResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                  @Valid @RequestBody ProductUpdateRequest request) {
        return products.update(AuthUser.from(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    public void archive(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        products.archive(AuthUser.from(jwt), id);
    }

    @PostMapping("/{id}/restock")
    @PreAuthorize("hasRole('OWNER')")
    public ProductResponse restock(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                   @Valid @RequestBody RestockRequest request) {
        return products.restock(AuthUser.from(jwt), id, request);
    }

    @GetMapping("/{id}/movements")
    @PreAuthorize("hasRole('OWNER')")
    public PageResponse<MovementResponse> movements(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return products.movements(AuthUser.from(jwt), id, page, size);
    }
}

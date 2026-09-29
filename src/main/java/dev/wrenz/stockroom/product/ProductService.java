package dev.wrenz.stockroom.product;

import dev.wrenz.stockroom.common.ConflictException;
import dev.wrenz.stockroom.common.NotFoundException;
import dev.wrenz.stockroom.common.PageResponse;
import dev.wrenz.stockroom.security.AuthUser;
import dev.wrenz.stockroom.stock.MovementResponse;
import dev.wrenz.stockroom.stock.StockMovementRepository;
import dev.wrenz.stockroom.stock.StockRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.Locale;

@Service
public class ProductService {

    private final ProductRepository products;
    private final StockRepository stock;
    private final StockMovementRepository movements;

    public ProductService(ProductRepository products, StockRepository stock, StockMovementRepository movements) {
        this.products = products;
        this.stock = stock;
        this.movements = movements;
    }

    @Transactional
    public ProductResponse create(AuthUser caller, ProductRequest request) {
        int opening = request.initialStockOrZero();
        Product product = products.saveAndFlush(new Product(caller.shopId(), request.sku(), request.name(),
                request.price().setScale(2, RoundingMode.UNNECESSARY), request.thresholdOrDefault(), opening));
        stock.recordOpeningStock(caller.shopId(), product.getId(), opening, caller.userId());
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(AuthUser caller, long id) {
        return ProductResponse.from(find(caller, id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(AuthUser caller, boolean lowStock, String q, boolean includeArchived,
                                              int page, int size) {
        if (includeArchived && !caller.isOwner()) {
            throw new AccessDeniedException("includeArchived is owner-only");
        }
        return PageResponse.of(products.search(caller.shopId(), includeArchived, lowStock, likePattern(q),
                PageResponse.pageable(page, size)).map(ProductResponse::from));
    }

    @Transactional
    public ProductResponse update(AuthUser caller, long id, ProductUpdateRequest request) {
        Product product = find(caller, id);
        product.update(request.sku(), request.name(), request.price().setScale(2, RoundingMode.UNNECESSARY),
                request.lowStockThreshold());
        return ProductResponse.from(products.saveAndFlush(product));
    }

    /** Archive, never delete: old orders and the stock history still point at this product. */
    @Transactional
    public void archive(AuthUser caller, long id) {
        Product product = find(caller, id);
        product.archive();
        products.saveAndFlush(product);
    }

    /** The guarded UPDATE matched nothing: missing/archived is 404; otherwise the stock limit was hit (409). */
    @Transactional
    public ProductResponse restock(AuthUser caller, long id, RestockRequest request) {
        if (!stock.restock(caller.shopId(), id, request.quantity(), request.note(), caller.userId())) {
            Product product = find(caller, id);
            if (!product.isActive()) {
                throw new NotFoundException("Product", id);
            }
            throw new ConflictException("STOCK_LIMIT_EXCEEDED",
                    "Restocking would take product " + id + " above " + StockRepository.MAX_STOCK + " units");
        }
        return ProductResponse.from(find(caller, id));
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementResponse> movements(AuthUser caller, long id, int page, int size) {
        find(caller, id);
        return PageResponse.of(movements.findByShopIdAndProductIdOrderByCreatedAtDescIdDesc(
                caller.shopId(), id, PageResponse.pageable(page, size)).map(MovementResponse::from));
    }

    private Product find(AuthUser caller, long id) {
        return products.findByIdAndShopId(id, caller.shopId()).orElseThrow(() -> new NotFoundException("Product", id));
    }

    /** Lowercase "contains" pattern; %, _ and \ in the user's text match literally. */
    static String likePattern(String q) {
        String text = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}

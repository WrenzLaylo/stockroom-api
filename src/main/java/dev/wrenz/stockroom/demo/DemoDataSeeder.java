package dev.wrenz.stockroom.demo;

import dev.wrenz.stockroom.product.Product;
import dev.wrenz.stockroom.product.ProductRepository;
import dev.wrenz.stockroom.shop.Shop;
import dev.wrenz.stockroom.shop.ShopRepository;
import dev.wrenz.stockroom.stock.StockRepository;
import dev.wrenz.stockroom.user.AppUser;
import dev.wrenz.stockroom.user.AppUserRepository;
import dev.wrenz.stockroom.user.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds the public demo shop ONCE (spec section 8). If a shop flagged is_demo already exists it does
 * nothing: no reset, no password change, so a redeploy never disrupts someone using the demo.
 * To start over, run ops/reset-demo.sql by hand, then restart the app.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String SHOP_NAME = "Demo Sari-Sari Store";
    public static final String OWNER_EMAIL = "owner@demo.stockroom.dev";
    public static final String OWNER_PASSWORD = "demo-owner-2026";
    public static final String STAFF_EMAIL = "staff@demo.stockroom.dev";
    public static final String STAFF_PASSWORD = "demo-staff-2026";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private record Item(String sku, String name, String price, int stock, int threshold) {}

    private static final List<Item> ITEMS = List.of(
            new Item("PANCIT-CANTON", "Instant Pancit Canton 60g", "16.00", 120, 24),
            new Item("COFFEE-3IN1", "3-in-1 Coffee Sachet 28g", "9.00", 200, 40),
            new Item("MILK-33G", "Powdered Milk 33g", "14.00", 80, 20),
            new Item("SARDINES-155", "Sardines in Tomato Sauce 155g", "28.00", 60, 12),
            new Item("CORNED-BEEF-150", "Corned Beef 150g", "42.00", 40, 10),
            new Item("RICE-KG", "Well-milled Rice (per kilo)", "52.00", 150, 30),
            new Item("SUGAR-KG", "Brown Sugar (per kilo)", "85.00", 30, 8),
            new Item("COOKING-OIL-1L", "Cooking Oil 1L", "110.00", 25, 6),
            new Item("EGG-PC", "Egg (per piece)", "9.00", 180, 36),
            new Item("BAR-SOAP-60", "Bar Soap 60g", "25.00", 48, 12),
            new Item("SHAMPOO-SACHET", "Shampoo Sachet 12ml", "7.00", 3, 20),
            new Item("CANDLE-PC", "Candle (per piece)", "6.00", 50, 10),
            new Item("FEEDS-GROWER-50KG", "Hog Grower Feeds 50kg sack", "1650.00", 12, 4),
            new Item("FEEDS-STARTER-KG", "Hog Starter Feeds (per kilo)", "38.00", 90, 25),
            new Item("FEEDS-BOOSTER-KG", "Piglet Booster Feeds (per kilo)", "45.00", 5, 10));

    private final ShopRepository shops;
    private final AppUserRepository users;
    private final ProductRepository products;
    private final StockRepository stock;
    private final PasswordEncoder encoder;

    public DemoDataSeeder(ShopRepository shops, AppUserRepository users, ProductRepository products,
                          StockRepository stock, PasswordEncoder encoder) {
        this.shops = shops;
        this.users = users;
        this.products = products;
        this.stock = stock;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (shops.existsByDemoTrue()) {
            log.info("Demo data already present; leaving it untouched");
            return;
        }
        // Fail closed: if a demo email exists without a demo shop, something is wrong. Stop the app
        // rather than hand the demo logins to whoever holds that account.
        for (String email : List.of(OWNER_EMAIL, STAFF_EMAIL)) {
            if (users.existsByEmail(email)) {
                throw new IllegalStateException("Demo email " + email + " belongs to a non-demo account; refusing to seed");
            }
        }
        Shop shop = shops.save(Shop.demo(SHOP_NAME));
        AppUser owner = users.save(new AppUser(shop.getId(), OWNER_EMAIL, encoder.encode(OWNER_PASSWORD), Role.OWNER));
        users.save(new AppUser(shop.getId(), STAFF_EMAIL, encoder.encode(STAFF_PASSWORD), Role.STAFF));
        for (Item item : ITEMS) {
            Product product = products.saveAndFlush(new Product(shop.getId(), item.sku(), item.name(),
                    new BigDecimal(item.price()), item.threshold(), item.stock()));
            stock.recordOpeningStock(shop.getId(), product.getId(), item.stock(), owner.getId());
        }
        log.info("Seeded demo shop {} with {} products", shop.getId(), ITEMS.size());
    }
}

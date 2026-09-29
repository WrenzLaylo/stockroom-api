package dev.wrenz.stockroom.shop;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "shop")
public class Shop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Only DemoDataSeeder creates demo shops; no request can set this. */
    @Column(name = "is_demo", nullable = false, updatable = false)
    private boolean demo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Shop() {}

    public Shop(String name) {
        this.name = name;
        this.createdAt = Instant.now();
    }

    public static Shop demo(String name) {
        Shop shop = new Shop(name);
        shop.demo = true;
        return shop;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isDemo() { return demo; }
    public Instant getCreatedAt() { return createdAt; }
}

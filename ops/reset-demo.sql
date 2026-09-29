-- Manually resets the public demo shop. NEVER run by CI.
-- Usage (Azure): psql "<connection string for stockroom_app>" -f ops/reset-demo.sql, then restart the app to re-seed.
-- Deletes ONLY the shop flagged is_demo = true (only DemoDataSeeder can set that flag; no API can).
-- Names and emails are NOT trusted: a customer can pick any shop name. No flagged shop = error, nothing deleted.
DO $$
DECLARE
    demo_shop_id BIGINT;
BEGIN
    SELECT id INTO demo_shop_id FROM shop WHERE is_demo;

    IF demo_shop_id IS NULL THEN
        RAISE EXCEPTION 'Demo shop not found (no shop with is_demo = true). Nothing was deleted.';
    END IF;

    DELETE FROM stock_movement WHERE shop_id = demo_shop_id;
    DELETE FROM order_item WHERE shop_id = demo_shop_id;
    DELETE FROM customer_order WHERE shop_id = demo_shop_id;
    DELETE FROM product WHERE shop_id = demo_shop_id;
    DELETE FROM app_user WHERE shop_id = demo_shop_id;
    DELETE FROM shop WHERE id = demo_shop_id;

    RAISE NOTICE 'Demo shop % deleted. Restart the app to re-seed it.', demo_shop_id;
END $$;

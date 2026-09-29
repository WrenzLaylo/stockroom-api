#!/usr/bin/env bash
cd /c/Users/OASIS/Documents/GitHub/stockroom-api
. .superpowers/env.sh
W=.superpowers/sdd/2026-09-28-stockroom-api-phase1
S=src/main/java/dev/wrenz/stockroom
T=CrossShopIsolationTest

run() { # name test
  ./mvnw -B test -Dtest="$T#$2" > "$W/mut9-$1.log" 2>&1
  echo "== $1 -> $2: $(grep -E 'Tests run: [0-9]+, Failures' "$W/mut9-$1.log" | tail -1)"
  grep -m3 -E "Status expected|Range for response status|expected:|but was|fk_movement|fk_order_item" "$W/mut9-$1.log" | cut -c1-170
}

# baseline
./mvnw -B test -Dtest=$T > "$W/t9.log" 2>&1
echo "baseline: $(grep -E 'Tests run: [0-9]+, Failures' "$W/t9.log" | tail -1)"

# read path
sed -i 's/products.findByIdAndShopId(id, caller.shopId()).orElseThrow(() -> new NotFoundException("Product", id));/products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));/' $S/product/ProductService.java
run read ownerBCannotReadAProduct
git checkout -q -- $S/product/ProductService.java

# write path (restock without the shop filter)
sed -i 's/WHERE id = ? AND shop_id = ? AND active = true AND stock_qty <= ? - ?/WHERE id = ? AND active = true AND stock_qty <= ? - ?/; s/""", quantity, productId, shopId, MAX_STOCK, quantity);/""", quantity, productId, MAX_STOCK, quantity);/' $S/stock/StockRepository.java
run write ownerBCannotRestockAProduct
git checkout -q -- $S/stock/StockRepository.java

# order path (deduct without the shop filter)
sed -i 's/WHERE id = ? AND shop_id = ? AND active = true AND stock_qty >= ?/WHERE id = ? AND active = true AND stock_qty >= ?/; s/""", quantity, productId, shopId, quantity);/""", quantity, productId, quantity);/' $S/stock/StockRepository.java
run order ownerBCannotOrderAnotherShopsProduct
git checkout -q -- $S/stock/StockRepository.java

git status --short src

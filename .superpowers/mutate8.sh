#!/usr/bin/env bash
# Break one protection at a time, run the test that should catch it, then restore.
cd /c/Users/OASIS/Documents/GitHub/stockroom-api
. .superpowers/env.sh
W=.superpowers/sdd/2026-09-28-stockroom-api-phase1
S=src/main/java/dev/wrenz/stockroom

run() { # name test
  ./mvnw -B test -Dtest="ConcurrencyTest#$2" > "$W/mut-$1.log" 2>&1
  echo "== $1 -> $2: $(grep -E 'Tests run: [0-9]+, Failures' "$W/mut-$1.log" | tail -1)"
  grep -m2 -E "expected|Expecting|but was|ConstraintViolation|ck_product" "$W/mut-$1.log" | cut -c1-160
}

# 1. deduct without the stock guard
sed -i 's/WHERE id = ? AND shop_id = ? AND active = true AND stock_qty >= ?/WHERE id = ? AND shop_id = ? AND active = true/; s/""", quantity, productId, shopId, quantity);/""", quantity, productId, shopId);/' $S/stock/StockRepository.java
run guard twentyBuyersForFiveUnitsSellExactlyFive
git checkout -q -- $S/stock/StockRepository.java

# 2. JPA allowed to write stock
sed -i 's/insertable = true, updatable = false)/insertable = true, updatable = true)/' $S/product/Product.java
run updatable productEditRacingAnOrderKeepsTheSale
git checkout -q -- $S/product/Product.java

# 3. full-row updates again
sed -i 's/^@DynamicUpdate$//' $S/product/Product.java
run dynamic1 editRacingAnArchiveKeepsTheProductArchived
run dynamic2 archiveRacingAnEditKeepsTheNewName
git checkout -q -- $S/product/Product.java

# 4. no row lock on cancel (race: up to 3 runs)
sed -i 's/^    @Lock(LockModeType.PESSIMISTIC_WRITE)$//' $S/order/CustomerOrderRepository.java
for i in 1 2 3; do
  run nolock$i twoSimultaneousCancelsReturnStockExactlyOnce
  grep -q "Failures: [1-9]\|Errors: [1-9]" "$W/mut-nolock$i.log" && break
done
git checkout -q -- $S/order/CustomerOrderRepository.java

git status --short src

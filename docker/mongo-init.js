// MongoDB index bootstrap - catalogue.
//
// Runs once, on a fresh data volume, via /docker-entrypoint-initdb.d.
// Companion: mongo-init-reviews.js. PostgreSQL equivalent: postgres-init.sh.
//
// WHY THIS EXISTS
//
// product-catalog-service sets spring.data.mongodb.auto-index-creation: false,
// so the @Indexed / @CompoundIndex annotations on Product are declarative
// documentation rather than something Spring acts on. Without this script the
// unique index was never created anywhere, so duplicate SKUs were insertable at
// the database level.
//
// auto-index-creation is left off deliberately rather than flipped to true:
// building indexes at boot means an implicit schema migration every time a pod
// starts, and with several replicas starting at once, a race. Index management
// belongs with the data store, not with the application lifecycle.
//
// DATABASE SELECTION
//
// The official mongo image runs init scripts against MONGO_INITDB_DATABASE,
// which defaults to "test". A bare db.products here would therefore create the
// indexes in the wrong database. getSiblingDB is required, not stylistic.
//
// USAGE
//
//   Fresh volume:  docker compose up -d
//   Existing volume (dev):
//     docker compose exec mongodb mongosh catalog_db \
//       /docker-entrypoint-initdb.d/mongo-init.js

const catalog = db.getSiblingDB('catalog_db');

// Idempotent; implicitly creates the collection if it does not exist yet.
catalog.products.createIndex({ sku: 1 }, { name: "sku_1" });
catalog.products.createIndex({ tenantId: 1 }, { name: "tenantId_1" });
catalog.products.createIndex(
    { tenantId: 1, active: 1, category: 1 },
    { name: "tenant_active_category" }
);

// SKU uniqueness is PER TENANT. The previous index was a bare unique {sku: 1},
// which meant two independent tenant catalogues could not both stock
// SKU-1001 and whichever tenant claimed it first locked every other one out.
// This matches Product.ux_product_tenant_sku and the mesh-wide rule that the
// tenant is part of every unique index.
catalog.products.createIndex(
    { tenantId: 1, sku: 1 },
    { name: "ux_product_tenant_sku", unique: true }
);

// Drop the superseded globally-unique index. Guarded because dropIndex throws
// if the index is absent, and this script must be safe to re-run.
if (catalog.products.getIndexes().some(i => i.name === "sku_1" && i.unique)) {
    catalog.products.dropIndex("sku_1");
    print("mongo-init: dropped superseded unique sku_1 index");
}

print("mongo-init: catalog_db.products indexes -> " +
      catalog.products.getIndexes().map(i => i.name).join(", "));
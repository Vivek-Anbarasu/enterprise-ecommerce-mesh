// MongoDB index bootstrap - reviews.
//
// Runs once, on a fresh data volume, via /docker-entrypoint-initdb.d.
// Companion: mongo-init.js. PostgreSQL equivalent: postgres-init.sh.
//
// WHY THIS EXISTS
//
// review-rating-service DOES set auto-index-creation: true, so Spring creates
// the declared indexes at boot. What Spring never does is DROP indexes that are
// no longer declared. Both indexes on Review were previously defined without
// tenantId, so after the fix they would linger alongside their replacements -
// and the stale unique index would keep rejecting cross-tenant writes.
//
// This script exists to drop them. On a fresh volume they are absent and the
// drops are no-ops.
//
// USAGE
//
//   Fresh volume:  docker compose up -d
//   Existing volume (dev):
//     docker compose exec mongodb mongosh reviews_db \
//       /docker-entrypoint-initdb.d/mongo-init-reviews.js
//
//   Existing volume (an existing deployment with data):
//     Same command. Before running it, confirm no two rows collide under the
//     new key, or the replacement unique index cannot be built:
//
//       db.reviews.aggregate([
//         { $group: { _id: { tenantId: "$tenantId", productId: "$productId",
//                             customerId: "$customerId" }, n: { $sum: 1 } } },
//         { $match: { n: { $gt: 1 } } },
//       ])
//
//     An empty result means the migration is safe.

const reviews = db.getSiblingDB('reviews_db');

const superseded = ['ux_review_product_customer', 'idx_review_product_visible'];
const present = reviews.reviews.getIndexes().map(i => i.name);

superseded.forEach(name => {
    if (present.includes(name)) {
        reviews.reviews.dropIndex(name);
        print("mongo-init-reviews: dropped superseded index " + name);
    }
});

// Matches Review.java. Spring creates these at boot via auto-index-creation;
// creating them here too keeps a fresh volume correct before the service has
// ever started, and is idempotent either way.
reviews.reviews.createIndex(
    { tenantId: 1, productId: 1, customerId: 1 },
    { name: "ux_review_tenant_product_customer", unique: true }
);
reviews.reviews.createIndex(
    { tenantId: 1, productId: 1, moderationStatus: 1, createdAt: -1 },
    { name: "idx_review_tenant_product_visible" }
);

print("mongo-init-reviews: reviews_db.reviews indexes -> " +
      reviews.reviews.getIndexes().map(i => i.name).join(", "));
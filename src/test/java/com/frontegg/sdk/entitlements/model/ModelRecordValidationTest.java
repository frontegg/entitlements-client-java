package com.frontegg.sdk.entitlements.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive unit tests for all model record constructors and validation logic.
 *
 * <p>Tests verify defensive copying, immutability constraints, null-checks, blank-checks,
 * and range validation across all record types.
 */
class ModelRecordValidationTest {

    @Nested
    class UserSubjectContextTests {

        @Test
        void nullUserId_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new UserSubjectContext(null, "tenant-1"));
        }

        @Test
        void blankUserId_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserSubjectContext("", "tenant-1"));
            assertThrows(IllegalArgumentException.class,
                    () -> new UserSubjectContext("   ", "tenant-1"));
        }

        @Test
        void nullTenantId_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new UserSubjectContext("user-1", null));
        }

        @Test
        void blankTenantId_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new UserSubjectContext("user-1", ""));
            assertThrows(IllegalArgumentException.class,
                    () -> new UserSubjectContext("user-1", "   "));
        }

        @Test
        void validConstruction_threeArgConstructor() {
            UserSubjectContext ctx = new UserSubjectContext("user-1", "tenant-1",
                    Map.of("plan", "enterprise", "active", true));

            assertEquals("user-1", ctx.userId());
            assertEquals("tenant-1", ctx.tenantId());
            assertEquals(2, ctx.attributes().size());
            assertEquals("enterprise", ctx.attributes().get("plan"));
            assertTrue((Boolean) ctx.attributes().get("active"));
        }

        @Test
        void validConstruction_twoArgConvenience() {
            UserSubjectContext ctx = new UserSubjectContext("user-1", "tenant-1");

            assertEquals("user-1", ctx.userId());
            assertEquals("tenant-1", ctx.tenantId());
            assertEquals(0, ctx.attributes().size());
        }

        @Test
        void attributesDefensivelyCopied_originalMapModificationDoesNotAffectRecord() {
            Map<String, Object> originalAttrs = new HashMap<>();
            originalAttrs.put("plan", "free");

            UserSubjectContext ctx = new UserSubjectContext("user-1", "tenant-1", originalAttrs);

            // Modify original map
            originalAttrs.put("plan", "enterprise");
            originalAttrs.put("newKey", "newValue");

            // Record's attributes should not change
            assertEquals("free", ctx.attributes().get("plan"));
            assertFalse(ctx.attributes().containsKey("newKey"));
        }

        @Test
        void attributesAreImmutable_attemptToModifyThrowsException() {
            UserSubjectContext ctx = new UserSubjectContext("user-1", "tenant-1",
                    Map.of("key", "value"));

            assertThrows(UnsupportedOperationException.class,
                    () -> ctx.attributes().put("newKey", "newValue"));
        }

        @Test
        void emptyAttributesConstructor() {
            UserSubjectContext ctx = new UserSubjectContext("user-1", "tenant-1", Map.of());

            assertEquals(0, ctx.attributes().size());
        }

        @Test
        void nullAttributesMapInThreeArgConstructor_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new UserSubjectContext("user-1", "tenant-1", null));
        }
    }

    @Nested
    class EntitySubjectContextTests {

        @Test
        void nullEntityType_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new EntitySubjectContext(null, "entity-1"));
        }

        @Test
        void blankEntityType_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new EntitySubjectContext("", "entity-1"));
            assertThrows(IllegalArgumentException.class,
                    () -> new EntitySubjectContext("   ", "entity-1"));
        }

        @Test
        void nullEntityId_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new EntitySubjectContext("service_account", null));
        }

        @Test
        void blankEntityId_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new EntitySubjectContext("service_account", ""));
            assertThrows(IllegalArgumentException.class,
                    () -> new EntitySubjectContext("service_account", "   "));
        }

        @Test
        void validConstruction() {
            EntitySubjectContext ctx = new EntitySubjectContext("service_account", "svc-deployer-01");

            assertEquals("service_account", ctx.entityType());
            assertEquals("svc-deployer-01", ctx.entityId());
        }

        @Test
        void validConstruction_customEntityTypes() {
            EntitySubjectContext ctx1 = new EntitySubjectContext("device", "device-123");
            EntitySubjectContext ctx2 = new EntitySubjectContext("api_key", "key-xyz");

            assertEquals("device", ctx1.entityType());
            assertEquals("api_key", ctx2.entityType());
        }
    }

    @Nested
    class FeatureRequestContextTests {

        @Test
        void nullFeatureKey_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new FeatureRequestContext(null));
        }

        @Test
        void blankFeatureKey_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new FeatureRequestContext(""));
            assertThrows(IllegalArgumentException.class,
                    () -> new FeatureRequestContext("   "));
        }

        @Test
        void validConstruction() {
            FeatureRequestContext ctx = new FeatureRequestContext("advanced-reporting");

            assertEquals("advanced-reporting", ctx.featureKey());
        }

        @Test
        void validConstruction_variousFeatureKeys() {
            String[] keys = {
                    "feature-1",
                    "UPPERCASE_FEATURE",
                    "feature.with.dots",
                    "feature_with_underscore",
                    "feature-with-hyphens"
            };

            for (String key : keys) {
                FeatureRequestContext ctx = new FeatureRequestContext(key);
                assertEquals(key, ctx.featureKey());
            }
        }

        @Test
        void convenientConstructor_atDefaultsToNull() {
            FeatureRequestContext ctx = new FeatureRequestContext("feature-key");
            assertNull(ctx.at(), "convenience constructor must set at=null");
        }

        @Test
        void twoArgConstructor_withAt_storesInstant() {
            Instant at = Instant.parse("2026-01-01T00:00:00Z");
            FeatureRequestContext ctx = new FeatureRequestContext("feature-key", at);
            assertEquals(at, ctx.at(), "at field must match the provided Instant");
        }

        @Test
        void twoArgConstructor_withNullAt_isAllowed() {
            FeatureRequestContext ctx = new FeatureRequestContext("feature-key", null);
            assertNull(ctx.at(), "null at must be accepted");
        }
    }

    @Nested
    class PermissionRequestContextTests {

        @Test
        void nullPermissionKeys_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new PermissionRequestContext((List<String>) null));
        }

        @Test
        void emptyPermissionKeys_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PermissionRequestContext(List.of()));
        }

        @Test
        void nullEntryInList_throwsIllegalArgumentException() {
            List<String> keys = new ArrayList<>();
            keys.add("permission1");
            keys.add(null);

            // The implementation checks for null or blank entries and throws IllegalArgumentException
            assertThrows(IllegalArgumentException.class,
                    () -> new PermissionRequestContext(keys));
        }

        @Test
        void blankEntryInList_throwsIllegalArgumentException() {
            // The implementation validates that individual entries are not blank
            List<String> keys = new ArrayList<>();
            keys.add("permission1");
            keys.add("");

            assertThrows(IllegalArgumentException.class,
                    () -> new PermissionRequestContext(keys),
                    "blank entries in permission keys must throw IllegalArgumentException");
        }

        @Test
        void validConstruction_singlePermission_threeArgConstructor() {
            PermissionRequestContext ctx = new PermissionRequestContext("reports:read");

            assertEquals(1, ctx.permissionKeys().size());
            assertEquals("reports:read", ctx.permissionKeys().get(0));
        }

        @Test
        void validConstruction_singlePermission_convenience() {
            PermissionRequestContext ctx = new PermissionRequestContext("reports:read");

            assertEquals(1, ctx.permissionKeys().size());
            assertEquals("reports:read", ctx.permissionKeys().get(0));
        }

        @Test
        void validConstruction_multiplePermissions() {
            PermissionRequestContext ctx = new PermissionRequestContext(
                    List.of("reports:read", "reports:export", "reports:delete"));

            assertEquals(3, ctx.permissionKeys().size());
            assertEquals("reports:read", ctx.permissionKeys().get(0));
            assertEquals("reports:export", ctx.permissionKeys().get(1));
            assertEquals("reports:delete", ctx.permissionKeys().get(2));
        }

        @Test
        void permissionKeysDefensivelyCopied_originalListModificationDoesNotAffectRecord() {
            List<String> originalKeys = new ArrayList<>();
            originalKeys.add("permission1");
            originalKeys.add("permission2");

            PermissionRequestContext ctx = new PermissionRequestContext(originalKeys);

            // Modify original list
            originalKeys.add("permission3");
            originalKeys.set(0, "modified");

            // Record's keys should not change
            assertEquals(2, ctx.permissionKeys().size());
            assertEquals("permission1", ctx.permissionKeys().get(0));
        }

        @Test
        void permissionKeysAreImmutable_attemptToModifyThrowsException() {
            PermissionRequestContext ctx = new PermissionRequestContext(
                    List.of("permission1"));

            assertThrows(UnsupportedOperationException.class,
                    () -> ctx.permissionKeys().add("permission2"));
            assertThrows(UnsupportedOperationException.class,
                    () -> ctx.permissionKeys().set(0, "modified"));
        }

        @Test
        void validConstruction_largePermissionList() {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                keys.add("permission_" + i);
            }

            PermissionRequestContext ctx = new PermissionRequestContext(keys);
            assertEquals(100, ctx.permissionKeys().size());
        }

        @Test
        void convenientStringConstructor_atDefaultsToNull() {
            PermissionRequestContext ctx = new PermissionRequestContext("reports:read");
            assertNull(ctx.at(), "string convenience constructor must set at=null");
        }

        @Test
        void convenientListConstructor_atDefaultsToNull() {
            PermissionRequestContext ctx = new PermissionRequestContext(List.of("reports:read"));
            assertNull(ctx.at(), "list convenience constructor must set at=null");
        }

        @Test
        void fullConstructor_withAt_storesInstant() {
            Instant at = Instant.parse("2026-06-01T10:00:00Z");
            PermissionRequestContext ctx = new PermissionRequestContext(List.of("reports:read"), at);
            assertEquals(at, ctx.at(), "at field must match the provided Instant");
        }

        @Test
        void fullConstructor_withNullAt_isAllowed() {
            PermissionRequestContext ctx = new PermissionRequestContext(List.of("reports:read"), null);
            assertNull(ctx.at(), "null at must be accepted");
        }
    }

    @Nested
    class EntityRequestContextTests {

        @Test
        void nullResourceType_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new EntityRequestContext(null, "resource-1", "viewer"));
        }

        @Test
        void blankResourceType_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new EntityRequestContext("", "resource-1", "viewer"));
            assertThrows(IllegalArgumentException.class,
                    () -> new EntityRequestContext("   ", "resource-1", "viewer"));
        }

        @Test
        void nullResourceId_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new EntityRequestContext("document", null, "viewer"));
        }

        @Test
        void blankResourceId_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new EntityRequestContext("document", "", "viewer"));
            assertThrows(IllegalArgumentException.class,
                    () -> new EntityRequestContext("document", "   ", "viewer"));
        }

        @Test
        void nullRelation_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new EntityRequestContext("document", "doc-789", null));
        }

        @Test
        void blankRelation_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new EntityRequestContext("document", "doc-789", ""));
            assertThrows(IllegalArgumentException.class,
                    () -> new EntityRequestContext("document", "doc-789", "   "));
        }

        @Test
        void validConstruction() {
            EntityRequestContext ctx = new EntityRequestContext("document", "doc-789", "viewer");

            assertEquals("document", ctx.resourceType());
            assertEquals("doc-789", ctx.resourceId());
            assertEquals("viewer", ctx.relation());
        }

        @Test
        void validConstruction_variousResourcesAndRelations() {
            EntityRequestContext ctx1 = new EntityRequestContext("project", "proj-123", "owner");
            EntityRequestContext ctx2 = new EntityRequestContext("workspace", "ws-456", "member");
            EntityRequestContext ctx3 = new EntityRequestContext("team", "team-789", "admin");

            assertEquals("project", ctx1.resourceType());
            assertEquals("owner", ctx1.relation());

            assertEquals("workspace", ctx2.resourceType());
            assertEquals("member", ctx2.relation());

            assertEquals("team", ctx3.resourceType());
            assertEquals("admin", ctx3.relation());
        }
    }

    @Nested
    class RouteRequestContextTests {

        @Test
        void nullMethod_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new RouteRequestContext(null, "/api/v1/reports"));
        }

        @Test
        void blankMethod_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RouteRequestContext("", "/api/v1/reports"));
            assertThrows(IllegalArgumentException.class,
                    () -> new RouteRequestContext("   ", "/api/v1/reports"));
        }

        @Test
        void nullPath_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new RouteRequestContext("GET", null));
        }

        @Test
        void blankPath_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RouteRequestContext("GET", ""));
            assertThrows(IllegalArgumentException.class,
                    () -> new RouteRequestContext("GET", "   "));
        }

        @Test
        void validConstruction_twoArgConvenience() {
            RouteRequestContext ctx = new RouteRequestContext("GET", "/api/v1/reports");
            assertEquals("GET", ctx.method());
            assertEquals("/api/v1/reports", ctx.path());
            assertNull(ctx.at(), "two-arg convenience constructor must set at=null");
        }

        @Test
        void threeArgConstructor_withAt_storesInstant() {
            Instant at = Instant.parse("2026-03-01T09:00:00Z");
            RouteRequestContext ctx = new RouteRequestContext("POST", "/api/v1/data", at);
            assertEquals("POST", ctx.method());
            assertEquals("/api/v1/data", ctx.path());
            assertEquals(at, ctx.at(), "at field must match the provided Instant");
        }

        @Test
        void threeArgConstructor_withNullAt_isAllowed() {
            RouteRequestContext ctx = new RouteRequestContext("DELETE", "/api/v1/items/1", null);
            assertNull(ctx.at(), "null at must be accepted");
        }
    }

    @Nested
    class EntitlementsResultTests {

        @Test
        void allowed_factory_returnsTrueResult() {
            EntitlementsResult result = EntitlementsResult.allowed();

            assertTrue(result.result(), "allowed() must set result=true");
            assertFalse(result.monitoring(), "allowed() must set monitoring=false");
        }

        @Test
        void denied_factory_returnsFalseResult() {
            EntitlementsResult result = EntitlementsResult.denied();

            assertFalse(result.result(), "denied() must set result=false");
            assertFalse(result.monitoring(), "denied() must set monitoring=false");
        }

        @Test
        void monitoring_constructor_trueResult_trueLearning() {
            EntitlementsResult result = new EntitlementsResult(true, true);

            assertTrue(result.result());
            assertTrue(result.monitoring());
        }

        @Test
        void monitoring_constructor_falseResult_trueLearning() {
            EntitlementsResult result = new EntitlementsResult(false, true);

            assertFalse(result.result());
            assertTrue(result.monitoring());
        }

        @Test
        void monitoring_constructor_falseResult_falseMonitoring() {
            EntitlementsResult result = new EntitlementsResult(false, false);

            assertFalse(result.result());
            assertFalse(result.monitoring());
        }

        @Test
        void factories_areIndependent_allowedAndDeniedAreDifferent() {
            EntitlementsResult allowed = EntitlementsResult.allowed();
            EntitlementsResult denied = EntitlementsResult.denied();

            assertFalse(allowed.equals(denied), "allowed and denied must not be equal");
            assertTrue(allowed.result());
            assertFalse(denied.result());
        }

        @Test
        void factories_repeatInvocations_produceEqualResults() {
            EntitlementsResult result1 = EntitlementsResult.allowed();
            EntitlementsResult result2 = EntitlementsResult.allowed();

            assertEquals(result1, result2, "multiple calls to allowed() must produce equal results");
        }

        @Test
        void result_isRecord_hashCodeAndEqualsConsistent() {
            EntitlementsResult a = new EntitlementsResult(true, false);
            EntitlementsResult b = new EntitlementsResult(true, false);
            EntitlementsResult c = new EntitlementsResult(false, false);

            assertEquals(a, b, "records with same values must be equal");
            assertEquals(a.hashCode(), b.hashCode(), "equal records must have same hashCode");
            assertFalse(a.equals(c), "records with different values must not be equal");
        }
    }
}

package com.frontegg.sdk.entitlements.model;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
        void nullUserId_isAllowed() {
            // userId is optional — null means tenant-only evaluation (JS SDK parity)
            UserSubjectContext ctx = new UserSubjectContext(null, "tenant-1");
            assertNull(ctx.userId());
        }

        @Test
        void blankUserId_isAllowed() {
            // blank userId is treated the same as null — user item skipped in SpiceDB request
            UserSubjectContext ctx = new UserSubjectContext("", "tenant-1");
            assertEquals("", ctx.userId());
            UserSubjectContext ctxSpaces = new UserSubjectContext("   ", "tenant-1");
            assertEquals("   ", ctxSpaces.userId());
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

        @Test
        void userSubjectContext_nullPermissions_normalizedToEmptyList() {
            // Four-arg constructor with null permissions
            UserSubjectContext ctx = new UserSubjectContext("user-1", "tenant-1", null, Map.of());

            assertNotNull(ctx.permissions(), "permissions() must not return null");
            assertTrue(ctx.permissions().isEmpty(), "null permissions must be normalized to empty list");
        }

        @Test
        void userSubjectContext_permissionsDefensiveCopy_mutationNotReflected() {
            List<String> originalPermissions = new ArrayList<>();
            originalPermissions.add("perm1");
            originalPermissions.add("perm2");

            UserSubjectContext ctx = new UserSubjectContext("user-1", "tenant-1", originalPermissions, Map.of());

            // Modify original list
            originalPermissions.add("perm3");
            originalPermissions.set(0, "modified");

            // Context's permissions should not change
            assertEquals(2, ctx.permissions().size(), "permissions must be defensively copied");
            assertEquals("perm1", ctx.permissions().get(0), "original modification must not be reflected");
            assertEquals("perm2", ctx.permissions().get(1));
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

    }

    @Nested
    class PermissionRequestContextTests {

        @Test
        void nullPermissionKey_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> new PermissionRequestContext(null));
        }

        @Test
        void blankPermissionKey_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PermissionRequestContext(""));
            assertThrows(IllegalArgumentException.class,
                    () -> new PermissionRequestContext("   "));
        }

        @Test
        void validConstruction() {
            PermissionRequestContext ctx = new PermissionRequestContext("reports:read");
            assertEquals("reports:read", ctx.permissionKey());
        }

        @Test
        void validConstruction_variousPermissionKeys() {
            String[] keys = {
                    "reports:read",
                    "reports:export",
                    "admin:*",
                    "fe.billing.read"
            };
            for (String key : keys) {
                PermissionRequestContext ctx = new PermissionRequestContext(key);
                assertEquals(key, ctx.permissionKey());
            }
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

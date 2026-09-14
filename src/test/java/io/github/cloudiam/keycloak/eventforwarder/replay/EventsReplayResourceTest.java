package io.github.cloudiam.keycloak.eventforwarder.replay;

import io.github.cloudiam.keycloak.eventforwarder.EventForwarderProviderFactory;
import io.github.cloudiam.keycloak.eventforwarder.EventPublisher;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resources.admin.fgap.AdminPermissions;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class EventsReplayResourceTest {

    private KeycloakSession session;
    private KeycloakSessionFactory sessionFactory;
    private EventsReplayResource resource;

    private MockedStatic<AdminPermissions> adminPermissions;
    private MockedConstruction<AppAuthManager.BearerTokenAuthenticator> authenticator;

    @BeforeEach
    void setUp() {
        this.session = mock(KeycloakSession.class, RETURNS_DEEP_STUBS);
        this.sessionFactory = mock(KeycloakSessionFactory.class);
        KeycloakContext context = mock(KeycloakContext.class);
        when(this.session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(mock(RealmModel.class));
        when(this.session.getKeycloakSessionFactory()).thenReturn(this.sessionFactory);

        this.adminPermissions = mockStatic(AdminPermissions.class);
        this.adminPermissions.when(() -> AdminPermissions.evaluator(any(), any(), any()))
                .thenReturn(mock(org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator.class, RETURNS_DEEP_STUBS));

        this.resource = new EventsReplayResource(this.session);
    }

    @AfterEach
    void tearDown() {
        if (this.authenticator != null) {
            this.authenticator.close();
        }
        this.adminPermissions.close();
    }

    @Test
    void refusesACallWithoutABearerToken() {
        authenticateAs(null);

        assertThrows(NotAuthorizedException.class, () -> this.resource.replay("2026-06-01", null, 1000));
    }

    @Test
    void rejectsAMissingFrom() {
        authenticateAsAdmin();

        assertEquals(400, this.resource.replay(null, null, 1000).getStatus());
        assertEquals(400, this.resource.replay("  ", null, 1000).getStatus());
    }

    @Test
    void rejectsAnUnparseableFrom() {
        authenticateAsAdmin();

        Response response = this.resource.replay("last-tuesday", null, 1000);

        assertEquals(400, response.getStatus());
        assertEquals("invalid 'from' query parameter (ISO-8601 date or instant)", errorOf(response));
    }

    @Test
    void rejectsAnUnparseableTo() {
        authenticateAsAdmin();

        Response response = this.resource.replay("2026-06-01", "not-a-date", 1000);

        assertEquals(400, response.getStatus());
        assertEquals("invalid 'to' query parameter (ISO-8601 date or instant)", errorOf(response));
    }

    @Test
    void reportsAConflictWhenTheListenerIsNotInitialized() {
        authenticateAsAdmin();
        when(this.sessionFactory.getProviderFactory(EventListenerProvider.class, "event-forwarder")).thenReturn(null);

        Response response = this.resource.replay("2026-06-01", null, 1000);

        assertEquals(409, response.getStatus());
    }

    @Test
    void acceptsBothAPlainDateAndAnInstant() {
        authenticateAsAdmin();
        givenAnInitializedListener();

        try (MockedConstruction<EventsReplayService> service = mockConstruction(EventsReplayService.class,
                (m, ctx) -> when(m.replay(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                        .thenReturn(new EventsReplayService.ReplayResult(42, 7)))) {

            assertEquals(200, this.resource.replay("2026-06-01", "2026-06-12T00:00:00Z", 1000).getStatus());
            assertEquals(200, this.resource.replay("2026-06-01T00:00:00Z", null, 1000).getStatus());
        }
    }

    @Test
    void returnsTheEnqueuedCounts() {
        authenticateAsAdmin();
        givenAnInitializedListener();

        try (MockedConstruction<EventsReplayService> service = mockConstruction(EventsReplayService.class,
                (m, ctx) -> when(m.replay(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                        .thenReturn(new EventsReplayService.ReplayResult(42, 7)))) {

            Response response = this.resource.replay("2026-06-01", null, 1000);

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getEntity();
            assertEquals(42L, ((Number) body.get("user_events")).longValue());
            assertEquals(7L, ((Number) body.get("admin_events")).longValue());
        }
    }

    private void givenAnInitializedListener() {
        EventForwarderProviderFactory factory = mock(EventForwarderProviderFactory.class);
        when(factory.getPublisher()).thenReturn(mock(EventPublisher.class));
        when(this.sessionFactory.getProviderFactory(EventListenerProvider.class, "event-forwarder")).thenReturn(factory);
    }

    private void authenticateAsAdmin() {
        AuthenticationManager.AuthResult result = mock(AuthenticationManager.AuthResult.class, RETURNS_DEEP_STUBS);
        when(result.getToken()).thenReturn(mock(AccessToken.class, RETURNS_DEEP_STUBS));
        authenticateAs(result);
    }

    private void authenticateAs(AuthenticationManager.AuthResult result) {
        this.authenticator = mockConstruction(AppAuthManager.BearerTokenAuthenticator.class,
                (m, ctx) -> when(m.authenticate()).thenReturn(result));
    }

    private static String errorOf(Response response) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        return (String) body.get("error");
    }
}

package io.github.cloudiam.keycloak.eventforwarder.replay;

import io.github.cloudiam.keycloak.eventforwarder.EventPublisher;
import io.github.cloudiam.keycloak.eventforwarder.EventForwarderProviderFactory;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.resources.admin.AdminAuth;
import org.keycloak.services.resources.admin.fgap.AdminPermissions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Map;

public class EventsReplayResource {

    private final KeycloakSession session;

    public EventsReplayResource(KeycloakSession session) {
        this.session = session;
    }

    @POST
    @Path("events-replay")
    @Produces(MediaType.APPLICATION_JSON)
    public Response replay(@QueryParam("from") String from,
                           @QueryParam("to") String to,
                           @QueryParam("max") @DefaultValue("1000") int max) {
        RealmModel realm = this.session.getContext().getRealm();
        this.requireManageEvents(realm);

        if (from == null || from.isBlank()) {
            return error(Response.Status.BAD_REQUEST, "missing 'from' query parameter (ISO-8601 date or instant)");
        }
        Date fromDate = parseDate(from);
        if (fromDate == null) {
            return error(Response.Status.BAD_REQUEST, "invalid 'from' query parameter (ISO-8601 date or instant)");
        }
        Date toDate = to == null || to.isBlank() ? new Date() : parseDate(to);
        if (toDate == null) {
            return error(Response.Status.BAD_REQUEST, "invalid 'to' query parameter (ISO-8601 date or instant)");
        }

        EventPublisher publisher = this.lookupPublisher();
        if (publisher == null) {
            return error(Response.Status.CONFLICT, "the event-forwarder event listener is not initialized");
        }

        try {
            EventsReplayService.ReplayResult result = new EventsReplayService(publisher).replay(this.session, realm, fromDate, toDate, max);
            return Response.ok(Map.of(
                    "user_events", result.userEvents(),
                    "admin_events", result.adminEvents()
            )).build();
        } catch (EventsReplayService.ReplayUnavailableException e) {
            return error(Response.Status.CONFLICT, e.getMessage());
        }
    }

    private void requireManageEvents(RealmModel realm) {
        AuthenticationManager.AuthResult auth = new AppAuthManager.BearerTokenAuthenticator(this.session).authenticate();
        if (auth == null) {
            throw new NotAuthorizedException("Bearer");
        }
        ClientModel client = realm.getClientByClientId(auth.getToken().getIssuedFor());
        AdminAuth adminAuth = new AdminAuth(realm, auth.getToken(), auth.getUser(), client);
        // throws ForbiddenException (403) when the manage-events role is missing
        AdminPermissions.evaluator(this.session, realm, adminAuth).realm().requireManageEvents();
    }

    private EventPublisher lookupPublisher() {
        if (this.session.getKeycloakSessionFactory().getProviderFactory(EventListenerProvider.class, "event-forwarder")
                instanceof EventForwarderProviderFactory factory) {
            return factory.getPublisher();
        }
        return null;
    }

    private static Date parseDate(String value) {
        try {
            return Date.from(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            // not an instant, maybe a plain date
        }
        try {
            return Date.from(LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status).entity(Map.of("error", message)).build();
    }

}

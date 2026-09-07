package io.github.cloudiam.keycloak.eventforwarder.sender;

import io.github.cloudiam.keycloak.eventforwarder.InternalEvent;
import io.github.cloudiam.keycloak.eventforwarder.EventForwarderConfiguration;
import io.github.cloudiam.keycloak.eventforwarder.serializer.JsonEventSerializer;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventSenderToRabbitMqTest {

    private final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final Channel channel = mock(Channel.class);
    private EventForwarderConfiguration configuration;
    private EventSenderToRabbitMq sender;

    @BeforeEach
    void setUp() throws Exception {
        org.keycloak.Config.Scope scope = mock(org.keycloak.Config.Scope.class);
        when(scope.get("amqp_exchange")).thenReturn("test.exchange");
        this.configuration = EventForwarderConfiguration.createFromScope(scope);
        when(this.connectionFactory.newConnection()).thenReturn(this.connection);
        when(this.connection.isOpen()).thenReturn(true);
        when(this.connection.createChannel()).thenReturn(this.channel);
        when(this.channel.isOpen()).thenReturn(true);
        this.sender = new EventSenderToRabbitMq(this.configuration, this.connectionFactory, new JsonEventSerializer());
    }

    @Test
    void publishesUserEventWithClientRoutingKeyAndProps() throws Exception {
        Event event = new Event();
        event.setType(EventType.LOGIN);
        event.setClientId("clientId");
        this.sender.send(new InternalEvent<>("evt-1", InternalEvent.Type.USER, "realm-name", event));

        ArgumentCaptor<BasicProperties> props = ArgumentCaptor.forClass(BasicProperties.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(this.channel).basicPublish(eq("test.exchange"), eq("KK.EVENT.CLIENT.realm-name.SUCCESS.clientId.LOGIN"), props.capture(), body.capture());

        assertEquals("org.keycloak.events.Event", props.getValue().getHeaders().get("__TypeId__").toString());
        assertEquals("application/json", props.getValue().getContentType());
        assertEquals("Keycloak", props.getValue().getAppId());
        assertNull(props.getValue().getHeaders().get("x-replayed"));
        assertArrayEquals("{\"time\":0,\"type\":\"LOGIN\",\"clientId\":\"clientId\"}".getBytes(StandardCharsets.UTF_8), body.getValue());
    }

    @Test
    void marksReplayedEventsWithAHeader() throws Exception {
        Event event = new Event();
        event.setType(EventType.LOGIN);
        event.setClientId("clientId");
        this.sender.send(new InternalEvent<>("evt-5", InternalEvent.Type.USER, "realm-name", event, true));

        ArgumentCaptor<BasicProperties> props = ArgumentCaptor.forClass(BasicProperties.class);
        verify(this.channel).basicPublish(eq("test.exchange"), eq("KK.EVENT.CLIENT.realm-name.SUCCESS.clientId.LOGIN"), props.capture(), any(byte[].class));

        assertEquals("true", props.getValue().getHeaders().get("x-replayed").toString());
        assertEquals("org.keycloak.events.Event", props.getValue().getHeaders().get("__TypeId__").toString());
    }

    @Test
    void publishesAdminEventWithAdminRoutingKeyAndProps() throws Exception {
        AdminEvent event = new AdminEvent();
        event.setOperationType(OperationType.CREATE);
        event.setResourceType(ResourceType.USER);
        this.sender.send(new InternalEvent<>("evt-2", InternalEvent.Type.ADMIN, "realm-name", event));

        ArgumentCaptor<BasicProperties> props = ArgumentCaptor.forClass(BasicProperties.class);
        verify(this.channel).basicPublish(eq("test.exchange"), eq("KK.EVENT.ADMIN.realm-name.SUCCESS.USER.CREATE"), props.capture(), any(byte[].class));

        assertEquals("org.keycloak.events.admin.AdminEvent", props.getValue().getHeaders().get("__TypeId__").toString());
    }

    @Test
    void reusesOpenConnectionAndChannel() throws Exception {
        Event event1 = new Event();
        event1.setId("1");
        Event event2 = new Event();
        event2.setId("2");

        this.sender.send(new InternalEvent<>("evt-3", InternalEvent.Type.USER, "realm-name", event1));
        this.sender.send(new InternalEvent<>("evt-4", InternalEvent.Type.USER, "realm-name", event2));

        verify(this.connectionFactory, times(1)).newConnection();
        verify(this.connection, times(1)).createChannel();
        verify(this.channel, times(2)).basicPublish(anyString(), anyString(), any(), any(byte[].class));
    }

    @Test
    void interpolatesMustacheVariablesFromTheMap() {
        String result = EventSenderToRabbitMq.interpolate("kk.{{varname}}.{{another}}.ok",
                java.util.Map.of("varname", "events", "another", "admin"));

        assertEquals("kk.events.admin.ok", result);
    }

    @Test
    void interpolateToleratesSpacesAndRepeatedVariables() {
        String result = EventSenderToRabbitMq.interpolate("{{ realm }}.{{type}}.{{type}}",
                java.util.Map.of("realm", "master", "type", "LOGIN"));

        assertEquals("master.LOGIN.LOGIN", result);
    }

    @Test
    void interpolateKeepsUnknownVariablesVisible() {
        String result = EventSenderToRabbitMq.interpolate("kk.{{unknown}}.ok", java.util.Map.of());

        assertEquals("kk.{{unknown}}.ok", result);
    }

    @Test
    void throwsUncheckedWhenPublishingFails() throws Exception {
        doThrow(new IOException("broker gone")).when(this.channel)
                .basicPublish(anyString(), anyString(), any(), any(byte[].class));

        assertThrows(IllegalStateException.class,
                () -> this.sender.send(new InternalEvent<>("evt-id", InternalEvent.Type.USER, "realm-name", new Event())));
    }

}

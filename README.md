# Keycloak Event Forwarder Extension

##### A Keycloak SPI plugin that publishes events to a RabbitMQ server.

The `event-forwarder` event listener is a buffered pipeline: events are serialized to JSON,
queued in memory and published asynchronously, with an infinite retry on failure
(configurable backoff, default 1s, 3s, 5s, 10s, then every 30s) and a configurable rate limit (default 20 messages per second).

For example, an administrator updating a user in the realm `customers` produces this message:

* routing key: `KK.EVENT.ADMIN.customers.SUCCESS.USER.UPDATE`
* published to exchange: `amq.topic`
* content:

```json
{
  "id": "6f1c9c34-2b70-4a2e-9f2a-6d1a0b7c8e11",
  "time": 1780315200408,
  "realmId": "customers",
  "authDetails": {
    "realmId": "customers",
    "clientId": "security-admin-console",
    "userId": "0a3f5d21-9b64-4c8f-bb0e-7c2d4e6f8a90",
    "ipAddress": "10.42.0.7"
  },
  "resourceType": "USER",
  "resourceTypeAsString": "USER",
  "operationType": "UPDATE",
  "resourceId": "2e7b1f48-5c03-4d9a-8e61-3f0a9c5d2b74",
  "resourcePath": "users/2e7b1f48-5c03-4d9a-8e61-3f0a9c5d2b74",
  "representation": "{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"enabled\":true}",
  "error": null,
  "details": null
}
```

The payload is Keycloak's own event object serialized as-is: the fields above are what
`AdminEvent` exposes on Keycloak 26.7.0, and user events carry the fields of `Event` instead. Which
fields appear therefore follows your Keycloak version, not this extension. The extension adds nothing
to the body: what it controls is the routing key, the message properties and the delivery pipeline.

### Routing keys

Each event is published with a topic routing key built from a configurable
mustache-style pattern. Two patterns are used, one per event family:

| Event family | Config variable | Default pattern |
|--------------|-----------------|-----------------|
| User / client | `EVENT_FORWARDER_AMQP_USER_ROUTING_KEY_PATTERN`  | `KK.EVENT.CLIENT.{{realm}}.{{result}}.{{client}}.{{type}}` |
| Admin         | `EVENT_FORWARDER_AMQP_ADMIN_ROUTING_KEY_PATTERN` | `KK.EVENT.ADMIN.{{realm}}.{{result}}.{{resource_type}}.{{operation}}` |

**Variable mapping, user / client events:**

| Variable          | Source                                                       | Example   |
|-------------------|--------------------------------------------------------------|-----------|
| `{{realm}}`       | realm name (dots stripped)                                   | `master`  |
| `{{result}}`      | `ERROR` if the event carries an error, otherwise `SUCCESS`   | `SUCCESS` |
| `{{client}}`      | OIDC client id (dots stripped)                               | `account` |
| `{{type}}`        | Keycloak event type                                          | `LOGIN`   |

**Variable mapping, admin events:**

| Variable           | Source                                                      | Example   |
|--------------------|-------------------------------------------------------------|-----------|
| `{{realm}}`        | realm name (dots stripped)                                  | `customers` |
| `{{result}}`       | `ERROR` if the event carries an error, otherwise `SUCCESS`  | `SUCCESS` |
| `{{resource_type}}`| affected resource type                                      | `USER`    |
| `{{operation}}`    | admin operation type                                        | `UPDATE`  |

So a successful login on client `account` in realm `master` produces
`KK.EVENT.CLIENT.master.SUCCESS.account.LOGIN`, and an admin user update in realm `customers`
produces `KK.EVENT.ADMIN.customers.SUCCESS.USER.UPDATE`.

**Rules:**
- Variables use `{{ name }}` syntax; surrounding spaces are tolerated. An unknown variable is
  left in the key verbatim, so a typo stays visible rather than silently disappearing.
- After interpolation the key is normalized: any character outside `* # a-z A-Z 0-9 _ . -` is
  dropped and spaces become `_`. Dots inside `{{realm}}` and `{{client}}` are stripped *before*
  interpolation so a dotted value cannot inject extra topic segments.
- Only the variables listed above are available to each family; referencing an admin variable in
  the user pattern (or vice versa) leaves it un-substituted.

Because the recommended exchange is a **topic exchange** (default `amq.topic`), consumers can bind
queues to selective combinations of the segments above, e.g.:

| Binding key                       | Matches |
|-----------------------------------|---------|
| `KK.EVENT.#`                      | all events |
| `KK.EVENT.ADMIN.#`                | admin events only |
| `KK.EVENT.CLIENT.#`               | client events only |
| `KK.EVENT.*.master.#`            | every event in realm `master` |
| `KK.EVENT.CLIENT.*.ERROR.*.LOGIN` | failed logins on any client, any realm |


## Compatibility

| | |
|---|---|
| Keycloak | **26.3 and later** (verified on 26.3, 26.4, 26.5, 26.6, 26.7) |
| Java | 21 |

Keycloak 26.2 and earlier are **not supported**: the event replay endpoint authorizes callers through
Keycloak's fine-grained admin permissions API, which does not exist before 26.3. Every supported
minor is compiled and unit-tested in CI, so a breaking change in the Keycloak SPI shows up as a
failed build rather than a broken deployment. The build targets the newest supported version by
default; build against another with `mvn package -Dkeycloak.version=26.3.5`.

## USAGE:
1. Build from source: ``mvn clean package``
2. Copy `target/keycloak-event-forwarder.jar` into your Keycloak: `/opt/keycloak/providers/`
3. Configure through environment variables (see below)
4. Restart the Keycloak server
5. Enable the listener in the Keycloak UI by adding **event-forwarder**  
 `Manage > Events > Config > Events Config > Event Listeners`

#### Try it locally

A docker compose playground starts a Keycloak with the plugin and a RabbitMQ, linked together:

```sh
mvn package
docker compose up
```

* Keycloak UI: http://localhost:8080 (admin/admin)
* RabbitMQ UI: http://localhost:15672 (admin/admin)

Enable the listener (step 5 above), bind a queue to `amq.topic` with the routing key `KK.EVENT.#`
in the RabbitMQ UI, then log in or out of Keycloak and watch the events arrive.

#### Configuration

Every variable can be set as an environment variable, or as an SPI provider property
(the lowercase name without the `EVENT_FORWARDER_` prefix, e.g. `--spi-events-listener-event-forwarder-amqp-url=...`).

Transport:
  - `EVENT_FORWARDER_TRANSPORT` - default: *AMQP* - target the events are forwarded to.
    Only `AMQP` is implemented today; the variable is the extension point for future transports.
    The `EVENT_FORWARDER_AMQP_*` settings below only apply when this is `AMQP`.

Connection (AMQP):
  - `EVENT_FORWARDER_AMQP_URL` - default: *localhost*
  - `EVENT_FORWARDER_AMQP_PORT` - default: *5672*
  - `EVENT_FORWARDER_AMQP_VHOST` - default: *empty*
  - `EVENT_FORWARDER_AMQP_EXCHANGE` - default: *amq.topic*
  - `EVENT_FORWARDER_AMQP_USERNAME` - default: *admin*
  - `EVENT_FORWARDER_AMQP_PASSWORD` - default: *admin*
  - `EVENT_FORWARDER_AMQP_CONNECTION_TIMEOUT` - default: *60000*
  - `EVENT_FORWARDER_AMQP_HANDSHAKE_TIMEOUT` - default: *10000*
  - `EVENT_FORWARDER_AMQP_USE_TLS` - default: *false* - connect over TLS. The broker certificate is
    verified against the JVM trust store and must match the host you connected to. A TLS setup that
    cannot be honoured fails the startup rather than falling back to an unverified connection.
  - `EVENT_FORWARDER_AMQP_TRUST_STORE` - default: *empty* - path to a trust store holding your private
    CA, when the broker certificate is not signed by a publicly trusted one. PKCS12 and JKS are both
    accepted; the format follows the file extension, `.jks` for JKS and anything else for PKCS12.
  - `EVENT_FORWARDER_AMQP_TRUST_STORE_PASS` - default: *empty*
  - `EVENT_FORWARDER_AMQP_KEY_STORE` - default: *empty* - client key store for mutual TLS, same format
    rule as the trust store
  - `EVENT_FORWARDER_AMQP_KEY_STORE_PASS` - default: *empty*
  - `EVENT_FORWARDER_AMQP_TLS_INSECURE` - default: *false* - accept any broker certificate without
    verification. Encrypted but open to interception, so it exists only to reach a test broker with a
    self-signed certificate. It logs a warning on every startup.

Event selection and delivery:
  - `EVENT_FORWARDER_INCLUDED_USER_EVENTS` - default: `*` - comma-separated list of
    [event types](https://www.keycloak.org/docs-api/latest/javadocs/org/keycloak/events/EventType.html)
    (e.g. `LOGIN,LOGOUT`), `*` or `all` for all of them, `none` for none of them
  - `EVENT_FORWARDER_INCLUDE_ADMIN_EVENTS` - default: *true*
  - `EVENT_FORWARDER_ASYNC_RECEIVER_QUEUE_MAX_SIZE` - default: *65536* - capacity of the in-memory buffer,
    `0` disables the buffering and events are published synchronously
  - `EVENT_FORWARDER_ASYNC_RECEIVER_WHEN_FULL` - default: *DROP* - what to do when the buffer is full:
    `BLOCK` the caller until room is available, or `DROP` the event
  - `EVENT_FORWARDER_SERIALIZER_JSON_IS_PRETTY` - default: *false* - pretty-print the JSON payload
  - `EVENT_FORWARDER_MAX_SEND_PER_SECOND` - default: *20* - maximum number of messages published per second,
    `0` disables the rate limiting
  - `EVENT_FORWARDER_RETRY_BACKOFF_MS` - default: *1000,3000,5000,10000,30000* - comma-separated delays in
    milliseconds between send retries; the last value repeats for every further attempt (retries are infinite)

Routing and message format:
  - `EVENT_FORWARDER_AMQP_USER_ROUTING_KEY_PATTERN` - default: `KK.EVENT.CLIENT.{{realm}}.{{result}}.{{client}}.{{type}}`
    - routing key pattern for user/client events (see [Routing keys](#routing-keys))
  - `EVENT_FORWARDER_AMQP_ADMIN_ROUTING_KEY_PATTERN` - default: `KK.EVENT.ADMIN.{{realm}}.{{result}}.{{resource_type}}.{{operation}}`
    - routing key pattern for admin events (see [Routing keys](#routing-keys))
  - `EVENT_FORWARDER_AMQP_USER_TYPE_ID` - default: `org.keycloak.events.Event` - value of the
    `__TypeId__` AMQP header on user-event messages (consumed by Spring AMQP type mapping)
  - `EVENT_FORWARDER_AMQP_ADMIN_TYPE_ID` - default: `org.keycloak.events.admin.AdminEvent` - value of the
    `__TypeId__` AMQP header on admin-event messages

#### Replaying events

Previously saved events can be replayed to RabbitMQ, e.g. after an outage longer than the buffer
could absorb. Prerequisite: event storage must be enabled for the realm (**Realm settings > Events**:
*Save events*, and *Save admin events* for admin events): the replay reads from the Keycloak event store.

```sh
TOKEN=$(curl -s -d 'client_id=admin-cli' -d 'username=admin' -d 'password=admin' \
  -d 'grant_type=password' http://localhost:8080/realms/master/protocol/openid-connect/token | jq -r .access_token)

curl -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/realms/master/event-forwarder/events-replay?from=2026-06-01&to=2026-06-12T00:00:00Z&max=1000"
# {"user_events":42,"admin_events":7}
```

* `from` (required) and `to` (default: now) accept an ISO-8601 date or instant; `max` caps each query (default 1000)
* the token must be issued by the target realm and carry the **manage-events** realm-management role
* replayed events go through the regular pipeline (filtering, buffering, rate limiting, retry) and are
  published with an `x-replayed: true` AMQP header so consumers can tell them apart; the original event
  id is kept in the payload
* the returned counts are events enqueued. With the `DROP` policy some may still be dropped if the
  buffer fills up (watch `keycloak_extensions_event_forwarder_dropped_total` in Prometheus)

#### Monitoring

The plugin exports the following metrics to Micrometer (global registry):

| Metric                                                        | Type | Description |
|---------------------------------------------------------------|------|-------------|
| `keycloak_extensions_event_forwarder_serialization_duration` | timer | time to serialize an event to JSON |
| `keycloak_extensions_event_forwarder_time_to_queue_duration` | timer | time to store an event in the buffer (only relevant with the `BLOCK` policy) |
| `keycloak_extensions_event_forwarder_queued_duration`        | timer | time an event spent in the buffer before being picked up |
| `keycloak_extensions_event_forwarder_total_sent_duration`    | timer | time to send an event, including retries and rate limiting |
| `keycloak_extensions_event_forwarder_transport_sent_duration` | timer | time of a single send attempt at the transport layer (tag `transport=rabbitmq`) |
| `keycloak_extensions_event_forwarder_throttled_delay_duration` | timer | time lost waiting for a rate limit slot |
| `keycloak_extensions_event_forwarder_send_retries`           | counter | number of send retries since startup |
| `keycloak_extensions_event_forwarder_dropped`                | counter | number of events dropped because the buffer was full (`DROP` policy) |
| `keycloak_extensions_event_forwarder_queue_size`             | gauge | current number of events in the buffer queue |
| `keycloak_extensions_event_forwarder_queue_capacity`         | gauge | configured capacity of the buffer queue (constant) |
| `keycloak_extensions_event_forwarder_payload_size`           | distribution summary | payload size in bytes (average = `_sum / _count`) |

Note: these are the Micrometer names. In the Prometheus exposition counters get a `_total` suffix
(e.g. `keycloak_extensions_event_forwarder_dropped_total`) and timers/summaries are split into
`_count`/`_sum`/`_max` series plus one gauge per published percentile.

## Prior art

The `KK.EVENT.CLIENT.*` / `KK.EVENT.ADMIN.*` routing key scheme is the de-facto convention
established by [aznamier/keycloak-event-listener-rabbitmq](https://github.com/aznamier/keycloak-event-listener-rabbitmq),
the original RabbitMQ event listener for Keycloak. This extension keeps the same key structure on
purpose: a consumer already bound to those keys keeps working, with no change to its queue bindings.
This project is released under the same license, the Apache License 2.0, and credits that project for
the convention in its [NOTICE](NOTICE).

What this extension adds on top is the delivery pipeline: a bounded in-memory buffer with a
background worker so publication never runs on the login thread, infinite retry with a configurable
backoff, an optional rate limit, configurable key templates, and a REST endpoint to replay stored
events.

## Who maintains this

The extension is developed by [Cloud-IAM](https://www.cloud-iam.com) and also ships on the Cloud-IAM
managed Keycloak, where it is installed from the marketplace in one click. That changes nothing for a
self-hosted deployment: the jar built from this repository is the whole extension, with no hook into
any platform and no phone-home.

[MARKETPLACE.md](MARKETPLACE.md) is the marketplace description of the extension. It documents the
same behavior and the same settings as this README, but its installation flow and support channels
apply to Cloud-IAM managed deployments only.

## License

This project is released under the [Apache License, Version 2.0](LICENSE), the same license as
Keycloak itself and as the RabbitMQ event listener this extension descends from. Attribution and
copyright are in [NOTICE](NOTICE).

The provider jar is an uber jar: it bundles the compiled classes of the RabbitMQ Java client
(`com.rabbitmq:amqp-client`), redistributed under that same license. See
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md); the license, the notice and the third-party notices
all ship inside the jar under `META-INF/`. Every other dependency (Keycloak, SLF4J, Micrometer) is
supplied by the Keycloak runtime and is not redistributed here.

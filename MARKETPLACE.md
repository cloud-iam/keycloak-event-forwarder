# 📡 Cloud-IAM marketplace sheet - Event Forwarder

> **This is the Cloud-IAM marketplace description of the extension**, kept in the repository so the
> public documentation and the commercial one cannot drift apart. It is written for customers of the
> [Cloud-IAM](https://www.cloud-iam.com) managed Keycloak, where the extension is installed from the
> marketplace in one click.
>
> If you run your own Keycloak and use the jar from this repository, everything about the extension's
> behavior and configuration below applies to you, but the marketplace installation flow and the
> support channels at the end of this page do not: they cover Cloud-IAM managed deployments only.
> Start from the [README](README.md), and open a GitHub issue for questions or bugs.

## 📋 Description

### Summary

Forwards Keycloak user and admin events to a message broker (RabbitMQ / AMQP) as JSON, through a buffered, non-blocking pipeline.
Events are published to a topic exchange with a configurable routing key, so downstream consumers can subscribe to exactly the slices they care about.
A REST endpoint lets you replay stored events after an outage.

### Details

Keycloak emits a rich stream of events (logins, logouts, token refreshes, admin changes) but keeps them inside its own database.
Getting that activity into the rest of your platform (automation, analytics, notifications, downstream services) normally means polling the admin API or scraping logs.

`Event Forwarder` runs as a Keycloak **event listener** (a passive observer that never gates authentication) and pushes each event to a broker the moment it happens.
Delivery is **asynchronous**: events are queued in memory and published off the login thread by a background worker, with automatic retries and an optional rate limit, so a slow or briefly unavailable broker never slows down, or blocks, your users signing in.

Because events are published to a **topic exchange** with a structured routing key, each consumer binds a queue to just the events it wants (a single realm, only admin changes, only failed logins, …) without the forwarder needing to know about them.

> **Audience**: platform/DevOps engineers and integrators who want Keycloak activity available on their message bus for automation, analytics, or downstream processing.

---

## ✨ Features

- 🐰 **Forwards to RabbitMQ/AMQP**: every user and admin event published as JSON to a topic exchange.
- ⚡ **Non-blocking delivery**: a bounded in-memory queue + background worker sends events off the Keycloak event thread, so logins are never slowed down.
- 🔁 **Automatic retry**: failed sends are retried until the broker is back, with a configurable backoff (default 1s, 3s, 5s, 10s, then every 30s).
- 🚦 **Rate limiting**: cap the number of messages published per second to protect a sensitive broker.
- 🧭 **Configurable routing keys**: mustache-style templates build the topic routing key from event attributes (realm, result, client, type, …).
- 🎚️ **Event selection**: choose exactly which user event types to forward, and whether to include admin events.
- ⏪ **Event replay**: a REST endpoint re-publishes previously stored events for a date range, to recover from an outage longer than the buffer could absorb.
- 🔐 **TLS support**: connect to the broker over TLS with optional client key store and custom trust store.
- 🧩 **Pluggable transport**: the target transport is selectable (AMQP today), designed to grow to other targets.
- 🪶 **Zero login impact**: a passive event listener. It observes, it never blocks authentication.

---

## 🎯 Use Cases

### Use Case 1: React to user activity in real time

**Context**: You want to trigger downstream actions when users do things in Keycloak: send a welcome workflow on first login, notify a CRM on profile updates, revoke downstream sessions on logout.

**Solution**: Enable `Event Forwarder`, point it at your RabbitMQ.
Each consumer service binds a queue to the routing keys it cares about and reacts as events arrive, with no polling and no Keycloak API calls.

**Example**:
```
1. A user logs in to realm "customers"
2. Keycloak emits a LOGIN event
3. Event Forwarder publishes it to amq.topic with routing key
   KK.EVENT.CLIENT.customers.SUCCESS.webapp.LOGIN
4. Your "onboarding" service (bound to KK.EVENT.CLIENT.customers.*.*.LOGIN)
   receives the JSON and starts the welcome workflow
```

**Sequence**:
```
End user            Keycloak + Event Forwarder           RabbitMQ            Consumer
   │  login                 │                               │                   │
   ├───────────────────────►│  emit LOGIN                   │                   │
   │                        ├─ enqueue ─┐                   │                   │
   │◄── logged in ──────────┤           │ (off-thread)      │                   │
   │                        │           └─ publish ────────►│  routing key      │
   │                        │                               ├─ deliver ────────►│ welcome workflow
```

### Use Case 2: Stream audit activity to analytics

**Context**: Your data/analytics team wants admin and authentication activity in their warehouse or stream processor, without direct access to the Keycloak database.

**Solution**: Bind a single durable queue to `KK.EVENT.#` (all events).
A connector drains the queue into your pipeline (Kafka Connect, Logstash, a custom sink).
Admin events carry the resource type and operation in the routing key, so you can also split admin and user streams into separate queues.

**Example**:
```
1. An admin updates a user in realm "corp"
2. Event Forwarder publishes to amq.topic with routing key
   KK.EVENT.ADMIN.corp.SUCCESS.USER.UPDATE
3. The analytics queue (bound to KK.EVENT.ADMIN.#) receives the JSON
4. The sink loads it into the warehouse for reporting
```

### Use Case 3: Recover events after a broker outage

**Context**: Your broker was down (or the buffer overflowed under a burst) and some events were never delivered.
You need to backfill them.

**Solution**: With realm event storage enabled, call the **replay** endpoint for the affected time range.
Stored user and admin events are re-published through the same pipeline, tagged with an `x-replayed: true` AMQP header so consumers can distinguish a replay from a live event and stay idempotent.

**Example**:
```
1. Broker outage from 02:00 to 03:30; buffer filled and dropped events
2. Operator calls POST /realms/master/event-forwarder/events-replay
   ?from=2026-06-15T02:00:00Z&to=2026-06-15T03:30:00Z
3. Event Forwarder reads stored events and re-publishes them with x-replayed: true
4. Response: {"user_events":318,"admin_events":12}
```

---

## 🚀 Quick Start

This extension is a **passive event listener**, so there is no authentication flow to build.
Once it is added to your deployment from the marketplace, you only need to point it at a broker and enable it on a realm:

1. Set the broker connection variables on your deployment (at minimum `EVENT_FORWARDER_AMQP_URL`, and the credentials, see the Configuration section below).
2. In the Keycloak admin console, open **Realm settings → Events → Event listeners**.
3. Add **`event-forwarder`** to the list and **Save**. *(An "event listener" is a Keycloak plugin that is notified of every event.)*
4. On your broker, create a queue and bind it to the `amq.topic` exchange with the routing key `KK.EVENT.#` (everything).
5. Log in and out of Keycloak a few times, then watch the events arrive on your queue.

> **Tip**: Start with the broad binding `KK.EVENT.#` to confirm events flow, then narrow each consumer's binding to the slices it needs (see [Routing keys](#-routing-keys-amqp)).

---

## ⚙️ Configuration

Every setting is an **environment variable** on your deployment, e.g. `EVENT_FORWARDER_AMQP_URL=broker.internal`.
When a variable is not set, its default applies.

### Transport selection

| Variable | Description | Default | Values |
|----------|-------------|---------|--------|
| `EVENT_FORWARDER_TRANSPORT` | Target the events are forwarded to. The `AMQP_*` settings apply only when this is `AMQP`. | `AMQP` | `AMQP` |

> Only `AMQP` (RabbitMQ) is implemented today.
> The variable exists so future transports can be added without changing how you configure the extension.

### Connection: AMQP / RabbitMQ

| Variable | Description | Default |
|----------|-------------|---------|
| `EVENT_FORWARDER_AMQP_URL` | Broker host name or IP | `localhost` |
| `EVENT_FORWARDER_AMQP_PORT` | Broker port | `5672` |
| `EVENT_FORWARDER_AMQP_VHOST` | RabbitMQ virtual host | *empty* |
| `EVENT_FORWARDER_AMQP_EXCHANGE` | Topic exchange to publish to | `amq.topic` |
| `EVENT_FORWARDER_AMQP_USERNAME` | Broker username | `admin` |
| `EVENT_FORWARDER_AMQP_PASSWORD` | Broker password (never written to logs) | `admin` |
| `EVENT_FORWARDER_AMQP_CONNECTION_TIMEOUT` | TCP connection timeout (ms) | `60000` |
| `EVENT_FORWARDER_AMQP_HANDSHAKE_TIMEOUT` | AMQP handshake timeout (ms) | `10000` |
| `EVENT_FORWARDER_AMQP_USE_TLS` | Connect over TLS | `false` |
| `EVENT_FORWARDER_AMQP_KEY_STORE` | Path to a client key store (mutual TLS) | *empty* |
| `EVENT_FORWARDER_AMQP_KEY_STORE_PASS` | Key store password | *empty* |
| `EVENT_FORWARDER_AMQP_TRUST_STORE` | Path to a trust store (custom CA) | *empty* |
| `EVENT_FORWARDER_AMQP_TRUST_STORE_PASS` | Trust store password | *empty* |

### Event selection & delivery

| Variable | Description | Default |
|----------|-------------|---------|
| `EVENT_FORWARDER_INCLUDED_USER_EVENTS` | Comma-separated list of [user event types](https://www.keycloak.org/docs-api/latest/javadocs/org/keycloak/events/EventType.html) to forward (e.g. `LOGIN,LOGOUT`). `*` or `all` for all, `none` for none. | `*` |
| `EVENT_FORWARDER_INCLUDE_ADMIN_EVENTS` | Forward admin events | `true` |
| `EVENT_FORWARDER_ASYNC_RECEIVER_QUEUE_MAX_SIZE` | Capacity of the in-memory buffer. `0` disables buffering (events sent synchronously). | `65536` |
| `EVENT_FORWARDER_ASYNC_RECEIVER_WHEN_FULL` | What to do when the buffer is full: `DROP` the event, or `BLOCK` the caller until room is free. | `DROP` |
| `EVENT_FORWARDER_SERIALIZER_JSON_IS_PRETTY` | Pretty-print the JSON payload | `false` |
| `EVENT_FORWARDER_MAX_SEND_PER_SECOND` | Maximum messages published per second. `0` disables the rate limit. | `20` |
| `EVENT_FORWARDER_RETRY_BACKOFF_MS` | Comma-separated delays (ms) between send retries; the last value repeats for every further attempt (retries are infinite). | `1000,3000,5000,10000,30000` |

### Routing & message format (AMQP)

| Variable | Description | Default |
|----------|-------------|---------|
| `EVENT_FORWARDER_AMQP_USER_ROUTING_KEY_PATTERN` | Routing key template for user/client events (see [Routing keys](#-routing-keys-amqp)) | `KK.EVENT.CLIENT.{{realm}}.{{result}}.{{client}}.{{type}}` |
| `EVENT_FORWARDER_AMQP_ADMIN_ROUTING_KEY_PATTERN` | Routing key template for admin events | `KK.EVENT.ADMIN.{{realm}}.{{result}}.{{resource_type}}.{{operation}}` |
| `EVENT_FORWARDER_AMQP_USER_TYPE_ID` | Value of the `__TypeId__` AMQP header on user-event messages (used by Spring AMQP type mapping) | `org.keycloak.events.Event` |
| `EVENT_FORWARDER_AMQP_ADMIN_TYPE_ID` | Value of the `__TypeId__` AMQP header on admin-event messages | `org.keycloak.events.admin.AdminEvent` |

---

## 📚 Configuration Examples

### Standard configuration (production)

Connect to an internal broker, forward everything, keep the safe non-blocking defaults:

```bash
EVENT_FORWARDER_AMQP_URL=rabbitmq.internal
EVENT_FORWARDER_AMQP_USERNAME=keycloak
EVENT_FORWARDER_AMQP_PASSWORD=<secret>
EVENT_FORWARDER_AMQP_EXCHANGE=amq.topic
# defaults: forward all events, buffer 65536, DROP when full, 20 msg/s
```

### Only authentication events, never block logins

Forward just logins/logouts, skip admin events, and explicitly drop under pressure:

```bash
EVENT_FORWARDER_INCLUDED_USER_EVENTS=LOGIN,LOGOUT,LOGIN_ERROR
EVENT_FORWARDER_INCLUDE_ADMIN_EVENTS=false
EVENT_FORWARDER_ASYNC_RECEIVER_WHEN_FULL=DROP
```

### Guaranteed delivery over TLS (no event loss)

Connect over TLS and **block** rather than drop, so the buffer back-pressures instead of losing events (logins can slow down if the broker is unavailable, so use it deliberately):

```bash
EVENT_FORWARDER_AMQP_URL=broker.example.com
EVENT_FORWARDER_AMQP_PORT=5671
EVENT_FORWARDER_AMQP_USE_TLS=true
EVENT_FORWARDER_AMQP_TRUST_STORE=/opt/keycloak/conf/broker-ca.p12
EVENT_FORWARDER_AMQP_TRUST_STORE_PASS=<secret>
EVENT_FORWARDER_ASYNC_RECEIVER_WHEN_FULL=BLOCK
EVENT_FORWARDER_MAX_SEND_PER_SECOND=0
```

---

## 🧭 Routing Keys (AMQP)

Each event is published with a **topic routing key** built from a configurable mustache-style pattern.
Two patterns are used, one per event family:

| Event family | Variable | Default pattern |
|--------------|----------|-----------------|
| User / client | `EVENT_FORWARDER_AMQP_USER_ROUTING_KEY_PATTERN` | `KK.EVENT.CLIENT.{{realm}}.{{result}}.{{client}}.{{type}}` |
| Admin | `EVENT_FORWARDER_AMQP_ADMIN_ROUTING_KEY_PATTERN` | `KK.EVENT.ADMIN.{{realm}}.{{result}}.{{resource_type}}.{{operation}}` |

**Variables, user / client events:**

| Variable | Source | Example |
|----------|--------|---------|
| `{{realm}}` | realm name (dots stripped) | `master` |
| `{{result}}` | `ERROR` if the event carries an error, otherwise `SUCCESS` | `SUCCESS` |
| `{{client}}` | OIDC client id (dots stripped) | `account` |
| `{{type}}` | Keycloak event type | `LOGIN` |

**Variables, admin events:**

| Variable | Source | Example |
|----------|--------|---------|
| `{{realm}}` | realm name (dots stripped) | `corp` |
| `{{result}}` | `ERROR` if the event carries an error, otherwise `SUCCESS` | `SUCCESS` |
| `{{resource_type}}` | affected resource type | `USER` |
| `{{operation}}` | admin operation type | `UPDATE` |

So a successful login on client `account` in realm `master` produces `KK.EVENT.CLIENT.master.SUCCESS.account.LOGIN`, and an admin user update in realm `corp` produces `KK.EVENT.ADMIN.corp.SUCCESS.USER.UPDATE`.

**Rules:**
- Variables use `{{ name }}` syntax; surrounding spaces are tolerated.
  An unknown variable is left in the key verbatim, so a typo stays visible.
- After interpolation the key is normalized: any character outside `* # a-z A-Z 0-9 _ . -` is dropped and spaces become `_`.
  Dots inside `{{realm}}` and `{{client}}` are stripped **before** interpolation so a dotted value cannot inject extra topic segments.
- Only the variables listed above are available to each family.

**Subscribing** (topic exchange, default `amq.topic`):

| Binding key | Matches |
|-------------|---------|
| `KK.EVENT.#` | all events |
| `KK.EVENT.ADMIN.#` | admin events only |
| `KK.EVENT.CLIENT.#` | client events only |
| `KK.EVENT.*.master.#` | every event in realm `master` |
| `KK.EVENT.CLIENT.*.ERROR.*.LOGIN` | failed logins on any client, any realm |

**Message properties:** each message is published as `application/json`, app id `Keycloak`, with a `__TypeId__` header (see the type-id variables above).
Replayed events additionally carry `x-replayed: true`.

---

## ⏪ Event Replay (REST API)

Re-publish previously stored events to the broker. Useful after an outage longer than the buffer could absorb, or to backfill a new consumer.

**Prerequisite:** event storage must be enabled for the realm.
In **Realm settings → Events**, turn on *Save events* (user events) and *Save admin events* (admin events).
The replay reads from the Keycloak event store.

**Endpoint:**

```
POST /realms/{realm}/event-forwarder/events-replay?from=<date>&to=<date>&max=<n>
```

| Query parameter | Required | Description |
|-----------------|----------|-------------|
| `from` | ✅ | Start of the range: ISO-8601 date (`2026-06-01`) or instant (`2026-06-01T00:00:00Z`) |
| `to` | ❌ (default: now) | End of the range, same formats as `from` |
| `max` | ❌ (default: 1000) | Caps the number of events read per query |

**Authorization:** a Bearer token issued by the **target realm**, carrying the **`manage-events`** realm-management role.

**Example:**

```bash
TOKEN=$(curl -s -d 'client_id=admin-cli' -d 'username=admin' -d 'password=admin' \
  -d 'grant_type=password' \
  http://localhost:8080/realms/master/protocol/openid-connect/token | jq -r .access_token)

curl -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/realms/master/event-forwarder/events-replay?from=2026-06-01&to=2026-06-12T00:00:00Z&max=1000"
# {"user_events":42,"admin_events":7}
```

**Behavior:**
- The response counts are events **enqueued** for delivery.
  With the `DROP` buffer policy, some may still be dropped if the buffer fills up during the replay.
- Replayed events go through the **same pipeline** as live events (selection, buffering, rate limiting, retry) and are published with the `x-replayed: true` AMQP header.
  The original event id is preserved in the payload, so consumers can de-duplicate and stay idempotent.

---

## 🔒 Security

- **No login impact**: a passive event listener. It observes events and never gates authentication.
- **Credentials & secrets**: the broker password is read from configuration and is **never written to logs** (it is the one config value excluded from the startup config dump).
  Provide it via an environment variable sourced from your secret manager.
- **TLS in transit**: set `EVENT_FORWARDER_AMQP_USE_TLS=true` and, if your broker uses a private CA, point `EVENT_FORWARDER_AMQP_TRUST_STORE` at it.
  Mutual TLS is supported via the client key store settings.
- **Replay endpoint is protected**: the `events-replay` API requires a Bearer token with the `manage-events` realm-management role, issued by the target realm.
- **GDPR / data minimization**: Keycloak events can contain personal data (usernames, IP addresses, email in some events).
  Forward only the event types you need (`EVENT_FORWARDER_INCLUDED_USER_EVENTS`), and ensure your broker, queues and downstream consumers apply appropriate retention and access controls.
  The extension does not persist events itself: it forwards from Keycloak's own store.

---

## 🔍 Troubleshooting

### Issue 1: No events arrive on my queue

**Cause**: The listener isn't enabled, or the queue isn't bound to the right exchange/routing key.
**Solution**: Confirm **`event-forwarder`** is listed under **Realm settings → Events → Event listeners**.
In RabbitMQ, verify your queue is bound to the configured exchange (default `amq.topic`) with a matching binding key. Start with `KK.EVENT.#` to catch everything.
**Prevention**: Test with the broad binding `KK.EVENT.#` first, then narrow it down.

### Issue 2: User events come through but admin events don't (or vice versa)

**Cause**: Event selection is filtering them out.
**Solution**: Check `EVENT_FORWARDER_INCLUDE_ADMIN_EVENTS` (must be `true` for admin events) and `EVENT_FORWARDER_INCLUDED_USER_EVENTS` (must include the user event types you expect, or be `*`).
**Prevention**: Remember admin events also require **Save admin events** only for *replay*: live forwarding does not need event storage, just the include flag.

### Issue 3: Some events are missing under heavy load

**Cause**: The in-memory buffer filled up and the `DROP` policy discarded events.
**Solution**: Increase `EVENT_FORWARDER_ASYNC_RECEIVER_QUEUE_MAX_SIZE`, raise or disable the rate limit (`EVENT_FORWARDER_MAX_SEND_PER_SECOND=0`), or switch to `BLOCK` if you cannot tolerate loss (accepting possible login slowdown).
**Prevention**: Size the buffer for your peak event rate, and use the **replay** endpoint to backfill any events lost during a burst.

### Issue 4: Keycloak can't connect to the broker

**Cause**: Wrong host/port/credentials, vhost mismatch, or a TLS trust problem.
**Solution**: Verify `EVENT_FORWARDER_AMQP_URL`, `_PORT`, `_VHOST`, `_USERNAME`, `_PASSWORD`.
For TLS, ensure `_USE_TLS=true`, the port is the TLS port (often `5671`), and the broker's CA is in `_TRUST_STORE`.
The forwarder retries automatically (backoff up to every 30s), so connectivity is restored without a restart once the broker is reachable.
**Prevention**: Validate broker connectivity from the Keycloak host before enabling the listener.

### Issue 5: Routing keys don't look like I expect

**Cause**: A custom pattern references an unavailable variable, or values contained dots/special characters.
**Solution**: Unknown variables are left verbatim (`{{typo}}` stays in the key), so check the spelling against the [variable tables](#-routing-keys-amqp).
Remember realm and client values have dots stripped and disallowed characters removed during normalization.
**Prevention**: Only use the documented variables for each event family; keep custom patterns aligned with your consumers' binding keys.

### Issue 6: Replay returns 409 "event listener is not initialized"

**Cause**: The replay endpoint runs but the `event-forwarder` listener isn't active on the realm.
**Solution**: Enable **`event-forwarder`** under the realm's Event listeners and retry.
**Prevention**: Enable the listener before relying on replay.

---

## ❓ FAQ

**Does this slow down logins?**
No.
Events are queued in memory and sent by a background worker.
With the default `DROP` policy, a slow or unavailable broker never blocks a login.
Only the explicit `BLOCK` policy can introduce back-pressure (by design, when you cannot tolerate event loss).

**What happens if the broker is down?**
New events are buffered and the worker retries with a configurable backoff (default 1s, 3s, 5s, 10s, then every 30s, see `EVENT_FORWARDER_RETRY_BACKOFF_MS`).
If the outage outlasts the buffer, excess events are dropped (or block, per policy), so use the **replay** endpoint afterwards to backfill from Keycloak's event store.

**Which broker do you support?**
RabbitMQ over AMQP today.
The `EVENT_FORWARDER_TRANSPORT` setting is the extension point for additional transports in the future; its only value today is `AMQP`.

**Do I need to enable event storage?**
Not for live forwarding.
You only need **Save events** / **Save admin events** if you want to use the **replay** endpoint, which reads from Keycloak's stored events.

**How do consumers tell a replayed event from a live one?**
Replayed messages carry the AMQP header `x-replayed: true`; live messages do not.
The original event id is preserved in the payload for de-duplication.

**Can I forward only specific events?**
Yes, set `EVENT_FORWARDER_INCLUDED_USER_EVENTS` to a comma-separated list (e.g. `LOGIN,LOGOUT`) and toggle `EVENT_FORWARDER_INCLUDE_ADMIN_EVENTS`.

---

## 💬 Support

**For Cloud-IAM managed deployments.** These channels are part of the Cloud-IAM offering and cover
the extension when it runs on a Cloud-IAM deployment:

- Cloud-IAM Dashboard: https://console.cloud-iam.com
- Documentation: https://documentation.cloud-iam.com
- Email: support@cloud-iam.com

**For the open-source jar.** If you build the extension from this repository and run it on your own
Keycloak, open a GitHub issue. There is no commercial support commitment attached to the jar, and a
suspected vulnerability goes through the private channel in the security policy rather than a public
issue.

---

> **Compatibility**: Keycloak **26.3 and later** (verified on 26.3 through 26.7), Java 21.
> Keycloak 26.2 and earlier are not supported: the replay endpoint relies on the fine-grained admin
> permissions API introduced in 26.3.

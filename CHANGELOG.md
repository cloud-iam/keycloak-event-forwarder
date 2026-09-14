# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The public contract covered by versioning is the provider id (`event-forwarder`), the
`EVENT_FORWARDER_*` configuration variables, the routing key templates and the exported metric names.

## [Unreleased]

Everything below will ship as `1.0.0`, the first public release. The extension itself is not new: it
has been running on the Cloud-IAM managed Keycloak, and this is its extraction into a standalone
open-source project.

### Added

- Forwards Keycloak user and admin events to a RabbitMQ topic exchange as JSON.
- Non-blocking delivery through a bounded in-memory buffer and a background worker, so publication
  never runs on the login thread and a slow broker cannot delay a login.
- Infinite retry with a configurable backoff (`EVENT_FORWARDER_RETRY_BACKOFF_MS`) and an optional
  rate limit (`EVENT_FORWARDER_MAX_SEND_PER_SECOND`).
- Configurable mustache-style routing key templates, one per event family, keeping the
  `KK.EVENT.CLIENT.*` and `KK.EVENT.ADMIN.*` convention so existing consumers work unchanged.
- Event selection by user event type, and a switch for admin events.
- A REST endpoint replaying stored events for a date range, protected by the `manage-events` role and
  tagging replayed messages with an `x-replayed` header.
- TLS towards the broker, verifying the broker certificate against the JVM trust store or a custom
  one, and checking the certificate belongs to the host that was connected to. Mutual TLS is
  supported through a client key store. Trust stores and key stores are accepted in PKCS12 and JKS,
  the format following the file extension.
- `EVENT_FORWARDER_AMQP_TLS_INSECURE` (default `false`) accepts any broker certificate without
  verifying it. It exists to reach a test broker with a self-signed certificate and logs a warning on
  every startup.
- Metrics exported to Micrometer for the queue size and capacity, the pipeline timings, the payload
  size, retries and drops.
- Compatible with Keycloak 26.3 and later, with every supported minor compiled and unit-tested in CI.

### Notes for Cloud-IAM marketplace users

The build published on the Cloud-IAM marketplace and this open-source jar are two separate artifacts.
Two differences matter if you move from one to the other:

- **TLS is now verified.** The marketplace build accepted any broker certificate when TLS was enabled
  without a trust store. Here, an untrusted certificate fails the connection. Point
  `EVENT_FORWARDER_AMQP_TRUST_STORE` at your CA, or set `EVENT_FORWARDER_AMQP_TLS_INSECURE=true` to
  keep the old behavior knowingly.
- **No platform inventory hook.** This jar does not register with the Cloud-IAM extension inventory,
  so it does not appear under the platform's extension listing.

[Unreleased]: https://github.com/cloud-iam/keycloak-event-forwarder/commits/main

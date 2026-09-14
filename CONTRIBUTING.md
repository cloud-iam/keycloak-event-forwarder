# Contributing

Thanks for considering a contribution. This is a Keycloak service provider (an event listener plus a
small REST resource), so the build is a plain Maven build and the feedback loop is fast.

## Build and test

You need **JDK 21** and Maven. Nothing else: every other dependency is fetched from Maven Central.

```sh
mvn verify
```

That compiles, runs the unit tests and assembles the provider jar at
`target/keycloak-event-forwarder.jar`. The jar is an uber jar: it contains the RabbitMQ client, and
everything else it needs (Keycloak, SLF4J, Micrometer) is supplied by the Keycloak runtime.

## Try your change on a real Keycloak

A docker compose playground starts a Keycloak with the plugin already mounted, next to a RabbitMQ:

```sh
mvn package && docker compose up
```

Keycloak is on http://localhost:8080 and RabbitMQ on http://localhost:15672, both with
`admin`/`admin`. Enable the `event-forwarder` listener under **Realm settings > Events > Event
listeners**, bind a queue to the `amq.topic` exchange with the routing key `KK.EVENT.#`, then log in
and out and watch the events arrive. The README covers the configuration variables.

## What we look for in a pull request

- **One focused change per pull request.** Small, reviewable diffs get merged; a pull request that
  mixes a fix, a refactor and a rename does not.
- **Tests for the behavior you change.** The pipeline stages (serialization, buffering, rate
  limiting, retry, replay) are covered by unit tests, and new behavior is expected to come with its
  own. Do not lower an existing assertion to make a change pass.
- **No new runtime dependency without a reason.** Everything bundled in the jar is loaded into the
  Keycloak server, so an added dependency has to be justified by what it enables.
- **Never slow down or block a login.** This extension is a passive observer. Anything on the event
  thread must stay non-blocking, which is why publication is buffered and sent by a background
  worker. A change that can make Keycloak wait on the broker will be refused.
- **No secret in the logs.** The broker password is deliberately excluded from the startup
  configuration dump. Keep it that way for anything else you read from configuration.
- **Comments explain why, not what.** Prefer a short one-line comment on a non-obvious decision over
  a paragraph restating the code.

## Commit messages

One line, no body, in the conventional commit format `type(scope): subject`. Allowed types are
`build`, `chore`, `ci`, `doc`, `feat`, `fix`, `perf`, `refactor`, `style` and `test`. For example:

```
fix(sender): keep retrying after a broker handshake timeout
```

Please avoid em dashes and en dashes in commit messages, code comments and documentation: a colon, a
comma, parentheses or two sentences read better.

## Releasing (maintainers)

The git tag is the version. Tag the commit you want to ship as `vX.Y.Z` and push the tag: CI sets the
project version from the tag, builds it, and publishes a GitHub release with the provider jar
attached. The POM stays on `-SNAPSHOT` on the branch, so a release never needs a version-bump commit.

Before tagging, move the `Unreleased` section of [CHANGELOG.md](CHANGELOG.md) under the version you
are about to ship.

```sh
git tag v1.0.0 && git push origin v1.0.0
```

## Reporting a bug

Open a GitHub issue with your Keycloak version, the extension version, the relevant configuration
variables (**without** the broker credentials) and the server log around the failure.

If you think you found a **vulnerability**, do not open an issue: follow
[SECURITY.md](SECURITY.md).

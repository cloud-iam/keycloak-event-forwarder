# Security policy

This extension runs inside Keycloak, an authentication server. A flaw here can affect every
deployment that installs it, so please report suspected vulnerabilities privately and give us time to
ship a fix before disclosing them.

## Reporting a vulnerability

**Do not open a public issue, a pull request or a discussion for a suspected vulnerability.**

Email **support@cloud-iam.com** with `event-forwarder security` in the subject. Say that you are
reporting a security issue and we will put you in touch with the security team on a private channel.
This works whatever the state of the repository, so use it if you are unsure.

If the repository's **Security** tab offers **Report a vulnerability**, you can use that instead: it
opens a private advisory visible only to you and the maintainers. That route depends on a GitHub
setting being enabled, so treat the email above as the channel that always works.

Helpful in a report: the affected version, the Keycloak version, the configuration needed to reach
the issue, what an attacker gains, and a reproduction if you have one.

## What to expect

- We acknowledge your report within **5 working days**.
- We tell you whether we consider it a vulnerability, and our assessment of the impact, within
  **15 working days**.
- We agree a disclosure date with you, and credit you in the advisory unless you prefer otherwise.

Please do not test against systems you do not own. In particular, do not probe the Cloud-IAM managed
platform: report the flaw and let us verify it on our own deployments.

## Supported versions

The extension is maintained on its default branch, and fixes ship in a new release from there. There
are no long-lived maintenance branches, so please confirm an issue against the latest release before
reporting it.

## Scope

In scope: the extension's own code, its handling of configuration and credentials, the replay REST
endpoint and its authorization, and the dependency it bundles into the provider jar.

Out of scope: vulnerabilities in Keycloak itself (report those to the
[Keycloak project](https://www.keycloak.org/security)), in RabbitMQ, and anything that requires an
attacker to already be a realm administrator with the permission the feature legitimately grants.

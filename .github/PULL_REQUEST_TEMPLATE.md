## What this changes

<!-- The functional outcome, and why. One or two sentences. -->

## How it was verified

<!-- The tests you added or ran. If it touches the broker connection or TLS, say whether you tried it
     against a real broker (docker compose up) and what you observed. -->

## Checklist

- [ ] One focused change: no fix, refactor and rename mixed together
- [ ] Tests cover the behavior that changed, and no existing assertion was weakened to pass
- [ ] `mvn verify` is green
- [ ] The commit message is one line in the `type(scope): subject` form
- [ ] Documentation updated if a configuration variable, a default or a routing key changed
- [ ] `CHANGELOG.md` updated under `Unreleased` if a user would notice this change
- [ ] No new runtime dependency, or the pull request explains what it enables
- [ ] Nothing can block or slow down a login: publication stays off the event thread

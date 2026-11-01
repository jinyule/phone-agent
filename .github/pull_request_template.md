## Motivation

<!-- Why this change; link the issue. -->

## Changes

<!-- What changed, at the level a reviewer needs. -->

## Testing

<!-- One entry per verification method, each with evidence: command output, replay diff, device recording. -->

## Checklist

- [ ] Changed the agent loop (`core/.../agent`) → updated `docs/design/phase1-architecture.md` §3.8
- [ ] Changed anything model-visible (prompt, tool schema, feedback text) → updated the replay fixtures
- [ ] Changed `IPrivilegedCore` → bumped `PrivilegedProtocol.VERSION`
- [ ] No secrets, real personal data or non-test-account screenshots in code, fixtures or logs

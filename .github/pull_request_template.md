## Summary

<!-- What does this change do, and why? -->

## Related

<!-- Issue / RFC / matrix row / PR link, if any -->

## Checklist

- [ ] Commits are signed-off (`-s` / DCO) — required by CI
- [ ] `./gradlew :app:assembleDebug` builds cleanly
- [ ] No new hardcoded parameter keys (settings are manifest-driven; resolve keys via `nativeFindParameterKey`)
- [ ] No typed-text / clipboard / PII added to analytics (per `analytics-events.json`)
- [ ] DasherCore changes (if any) are PR'd upstream at `dasher-project/DasherCore`, not edited in the submodule

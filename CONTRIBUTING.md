# Contributing to OmniTAK-Android

Thanks for your interest in contributing.

## Before you start

- For small fixes (typos, obvious bugs), open a PR directly.
- For new features or larger changes, open an issue first so we can align on scope.
- Security issues — see [SECURITY.md](SECURITY.md), do not file public issues.

## Development setup

1. Install Android Studio Ladybug or later, with JDK 17
2. `git clone https://github.com/engindearing-projects/OmniTAK-Android.git`
3. Open the project root in Android Studio and let Gradle sync
4. Run on a connected device or emulator (debug builds work without signing config)

## Code style

- Kotlin 2.0.x, Jetpack Compose for all new UI
- Follow existing module layout: `data/` (models, persistence), `domain/` (state stores), `ui/` (screens, components, theme)
- Compose: keep screens stateless where reasonable, hoist state to a store
- Coroutines + Flow over RxJava or callbacks
- No `!!` non-null assertions in production code
- 4-space indent, no wildcard imports

## Tests

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest    # requires connected device/emulator
```

New networking or CoT-parsing logic needs a unit test.

## Commit style

- One logical change per commit
- Imperative subject line, ≤ 72 chars
- Body explains the *why*, not the *what*

Example:

```
Cache TAK Server certificate chains across reconnects

Reconnect cycles were re-validating the full chain on every attempt,
adding ~250ms per connect on slow networks. Cache the validated chain
in memory keyed by server hostname; invalidate on cert rotation events.
```

## Pull request checklist

- [ ] `./gradlew assembleDebug` succeeds
- [ ] Unit tests pass
- [ ] Lint clean (`./gradlew lintDebug`)
- [ ] No new `// TODO` markers without an associated issue
- [ ] No secrets, hardcoded URLs, or test-only credentials added
- [ ] If touching `AndroidManifest.xml` permissions, explain why in the PR
- [ ] If adding a dependency, note the license in the PR

## License

By submitting a contribution, you agree that your work is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)).

## Code of conduct

Be respectful. Disagree on technical merits, not on people. Harassment, discrimination, or personal attacks will result in removal from the project.

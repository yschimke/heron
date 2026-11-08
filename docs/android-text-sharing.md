# Android text sharing

Heron accepts Android `ACTION_SEND` with `text/plain`. Shared text and an optional subject
open an editable normal post composer after navigation restoration and sign-in. The receiver
never posts automatically. The existing link resolver supplies website cards and AT record
embeds, including for initial text before the user types.

The launcher uses a single app task. `onNewIntent` handles warm shares and existing VIEW/OAuth
links. A pending share is retained in Activity saved state while login is required; receiving
another share replaces that pending request. Leaving authentication or backing out cancels it.
Each delivery gets a stable ID so restoration cannot replace an already opened/edited composer.
Once opened, composer state uses Heron's normal state/draft handling.

## Local checks

```sh
./gradlew :ui:scaffold:desktopTest :feature:compose:desktopTest
./gradlew :androidApp:assembleDebug
./gradlew spotlessApply spotlessCheck
```

Validation in the implementation checkout: `spotlessApply` and `spotlessCheck` passed;
all 36 scaffold tests and 2 composer tests passed on desktop. Android compilation and
`assembleDebug -Pandroid.injected.build.abi=arm64-v8a` passed, producing an arm64 debug APK.
The full all-ABI build reached packaging but exhausted the workspace disk; that packaging
configuration remains unverified. No Android device/emulator was available for the checks below.

After installing the debug build, this opens a draft only:

```sh
adb shell am start -a android.intent.action.SEND -t text/plain \
  -n com.tunjid.heron.debug/com.tunjid.heron.MainActivity \
  --es android.intent.extra.TEXT 'https://preview.coo.ee/'
```

Also exercise the browser share sheet rather than only an explicit adb component launch:

- Cold start and already-running app; Unicode, multiline text, URL query/fragment and long text.
- Signed-out flow through login, including OAuth returning to the existing task.
- Rotation/process recreation while waiting for login and after editing the composer.
- A second share while login is pending; only the latest pending request opens.
- Cancel or navigate away from login, then sign in later; the cancelled share must stay closed.
- Dismiss the automatically resolved link card, background/resume, and verify it stays dismissed.
- Existing profile/post VIEW deep links, action-less transfer/notification intents with data,
  and the ordinary empty composer remain functional.

These are device validation steps, not a claim that they were all exercised in this checkout.
No public test post is needed. Bluesky's `/intent/compose` URL is not added as a new route by
this change: use the OS text share target or paste into the normal composer.

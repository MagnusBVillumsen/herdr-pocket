# herdr-pocket app

The phone side of herdr-pocket: a list of every agent the hub knows about, a chat view
for one agent, and push notifications for chats you star. It talks only to the hub, over
the HTTP API in [`../docs/protocol.md`](../docs/protocol.md).

Kotlin Multiplatform + Compose Multiplatform. Android is the only target for now.

```
app/
├── shared/       commonMain: API client (Ktor + kotlinx.serialization, SSE), state, all UI
│                 commonTest: JSON shapes, ordering/grouping, deep links, push text, client
│                 androidHostTest: the real client against tools/fakehub.py
├── androidApp/   Activity, SharedPreferences, UnifiedPush service, notifications
└── tools/fakehub.py   a fake hub with canned data
```

Platform code sits behind two small interfaces in `shared/…/platform/Platform.kt`
(`ConfigStore`, `PushRegistrar`), so an iOS app only has to implement those and call
`App(AppModel(…))`.

## Build

On NixOS (or anywhere with Nix), the flake provides JDK 21, the Android SDK (platform 37.0,
build tools 36.0.0) and python3 for the tests:

```sh
cd app && nix develop -c ./gradlew assembleDebug
# or from the repo root
nix develop ./app -c app/gradlew -p app assembleDebug
```

Nix only sees files git knows about, so new files under `app/` must be at least `git add`ed
before `nix develop ./app` picks them up (`nix develop path:./app` works regardless).

The APK lands in `app/androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

Without Nix: set `ANDROID_HOME` to an SDK with platform `android-37.0` and build tools
36.0.0, use JDK 17 or newer, and run `./gradlew assembleDebug`.

Tests (all of commonTest plus the fake-hub integration test, which needs `python3`):

```sh
./gradlew :shared:testAndroidHostTest
```

Versions: Kotlin 2.4.21, Compose Multiplatform 1.12.2 (Material 3 1.9.0), AGP 9.5.0,
Gradle 9.8.2, Ktor 3.6.1, UnifiedPush connector 3.3.5. `compileSdk` 37, `targetSdk` 36,
`minSdk` 26 (Android 8).

## Install

Sideload the debug APK: copy it to the phone and open it (allow "install unknown apps" for
the file manager or browser you use), or `adb install androidApp-debug.apk` from a computer.
Debug builds are signed with the local debug key, so a build from another machine has to
replace the app with an uninstall first.

## Pair

On the hub host:

```sh
herdr-pocket pair            # or: herdr-pocket pair --url http://heimdall:8790
```

Scan the QR code with the phone's camera (or Google Lens) and open the
`herdr-pocket://pair?url=…&token=…` link; the app saves the hub URL and token and connects.
Alternatively open Settings (⚙) and type the hub URL and token, then Test and Save.

The hub is meant to be reachable over a private network such as a tailnet, so the app allows
plain `http://`. Keep the phone on that network (Tailscale app) when using it.

## Notifications (UnifiedPush via ntfy)

Only chats you star (★) notify: when one finishes, or when it needs you.

1. Install **ntfy** from F-Droid or Google Play. It is the UnifiedPush *distributor*:
   it holds the one connection to a push server and hands messages to apps.
2. Optional: in ntfy's settings, set the default server to your own ntfy instance (for
   example one on the tailnet). The default is ntfy.sh. Either way the hub must be able to
   reach that server over HTTPS/HTTP, since the hub POSTs notifications to it.
3. In herdr-pocket, Settings → "Notify for important chats". On Android 13+ the app asks
   for the notification permission; then ntfy hands it an endpoint, which the app sends to
   the hub (`POST /api/push`). Turning the switch off unregisters and removes it from the
   hub (`DELETE /api/push`).
4. In ntfy, the herdr-pocket subscription shows up automatically. Leave it there.

Tapping a notification opens that chat.

Note: the hub sends the payload as plain JSON, not RFC 8291 encrypted Web Push, so the
ntfy server can read machine, agent and title. Use your own ntfy server if that matters.

## Trying it without Herdr

```sh
python3 tools/fakehub.py 8790 tok
```

serves three canned agents on two machines. From the Android emulator the host is
`http://10.0.2.2:8790`, token `tok`. Each event stream closes after about a second, so the
app's reconnect loop is exercised all the time.

## TODO: iOS

The iOS targets are not declared yet, because they cannot be built or checked on Linux.
On a Mac:

1. In `shared/build.gradle.kts`, add `iosArm64()` and `iosSimulatorArm64()` with a
   `binaries.framework { baseName = "Shared"; isStatic = true }`, and
   `iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:<ktor>") }`.
2. Implement `ConfigStore` (NSUserDefaults, or the Keychain for the token) and
   `PushRegistrar` (APNs needs a hub-side sender; UnifiedPush does not exist on iOS) in
   `iosMain`, and expose `fun MainViewController() = ComposeUIViewController { App(model) }`.
3. Add an Xcode app project (`iosApp/`) that embeds the framework, registers the
   `herdr-pocket` URL scheme (pass the URL to `AppModel.handlePairLink`) and allows
   insecure HTTP loads for the tailnet (`NSAllowsArbitraryLoads` or per-domain exceptions).
4. `kotlin.time.Clock` and everything else in commonMain is already multiplatform.

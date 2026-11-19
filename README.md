# Shkolo Web

An Android app that wraps [Shkolo](https://app.shkolo.bg) — the Bulgarian school
platform — in a clean WebView, and fixes the two things that annoy you daily:
the site's broken "remember me", and logging out properly.

*Unofficial. Not affiliated with Shkolo. Reads your own account, for personal use.*

## Features

- **Stay signed in.** The app remembers your login and replays it through the
  real login form on every launch. No more typing it every three hours.
- **Auto-login that actually works.** Shkolo's login needs eight browser
  fingerprint fields (`fp.js`) to be populated first; submitting early gets a
  valid password rejected. The app waits for all eight, then submits.
- **Logout is detected, not buttoned.** When you log out inside Shkolo — or land
  on `/auth/logout` — the saved credentials are wiped automatically. There is no
  extra UI for it.
- **Pinch to zoom** works (the site ships `user-scalable=no`).
- **File upload / download**, camera capture, geolocation and microphone
  permissions are all proxied to the page, because Shkolo's web app expects them.
- **Remembers nothing else.** No analytics, no background services, no network
  calls of its own.

## How the login works

Shkolo posts to `/auth/login` with:

```
_token                        CSRF token from the page
fingerprint[visitor_id]       fingerprint[user_agent]    fingerprint[timezone]
fingerprint[webgl_vendor]     fingerprint[fonts]         fingerprint[canvas_hash]
fingerprint[audio_hash]       fingerprint[browser_hash]
login  password  remember
```

The fingerprint fields are filled **asynchronously** by the site's `fp.js`
(canvas/audio/WebGL hashes computed through `crypto.subtle`). Submitting before
they arrive makes the server reject a correct password.

So the app:

1. waits until every `input[name="fingerprint[...]"]` has a value (polling every
   500 ms, giving up after 15 s),
2. fills `input[name=login]` and `input[name=password]`,
3. calls `form.login-form.submit()` — deliberately bypassing Cloudflare Rocket
   Loader's `onsubmit` guard,
4. treats "left the login page" as success.

Credentials are stored in plain `SharedPreferences`: this is a personal device,
and they only unlock your own school account. The JS bridge that exposes them is
gated on the page origin (`app.shkolo.bg` only), so a redirect to an SSO provider
cannot read them.

## Build

```bash
./gradlew assembleDebug
```

Requires JDK 17 and an Android SDK with platform 34. The debug APK lands in
`app/build/outputs/apk/debug/`.

To install a signed build:

```bash
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out ShkoloWeb.apk app/build/outputs/apk/debug/app-debug.apk
adb install -r ShkoloWeb.apk
```

## Download

A ready-to-install debug-signed build is in [`dist/ShkoloWeb-1.1.apk`](dist/ShkoloWeb-1.1.apk).

Because it is self-signed, Android will not update it over a Play-signed install:
uninstall any previous copy first.

## Layout

```
app/src/main/java/bg/web/shkolo/
  MainActivity.kt   WebView shell, file/upload plumbing, login + logout logic
  CredStore.kt      saved credentials (SharedPreferences)
```

## Licence

MIT
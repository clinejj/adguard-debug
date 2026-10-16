# AdGuard Debug

A small Android app for working out why apps think they're offline while AdGuard (local VPN + HTTPS filtering) is running, especially on captive-portal Wi-Fi.

## Install

Every push builds a debug APK in GitHub Actions: **Actions → Build APK → latest run → Artifacts → `adguard-debug-apk`**. Unzip it and sideload the `.apk`. All builds share one signing key (`app/debug.keystore`), so new builds install over old ones and keep the log.

Grant location (Android needs it to show the Wi-Fi name) and notifications when asked.

## What it shows

- **Banner:** what a typical app's "am I online?" check sees right now. That's the default network having `INTERNET` + `VALIDATED` and not being blocked. With AdGuard on, the default network is AdGuard's VPN.
- **Networks:** every network (VPN, Wi-Fi, cellular) with its flags, DNS, Private DNS, MTU, routes and Wi-Fi details.
- **Probes:** active tests, each one isolating a single layer:
  - Android's own connectivity check (`connectivitycheck.gstatic.com/generate_204`), plain and HTTPS
  - DNS through the system resolver, raw UDP to 8.8.8.8 / 1.1.1.1 / the VPN's own DNS server, and DNS-over-HTTPS
  - TCP straight to an IP address (no DNS), over IPv4 and IPv6
  - The TLS certificate chain, showing whether AdGuard or a portal is intercepting
  - The same check run directly on each underlying network, bypassing the VPN (if AdGuard allows it)
  - Plain-English **hints** that interpret the results
- **Timeline:** every network change (flags gained or lost, default network switches, DNS changes, "apps now see OFFLINE") plus probe runs and your marks.

## Using it

1. Turn on **Background** to keep a foreground service logging and probing every 60s, and again after every network change.
2. When an app says it's offline, tap **Mark**. It's in the app, in the notification ("Mark issue now"), and in the **Mark net issue** Quick Settings tile (add it from the tile editor). A mark saves a full snapshot and runs the probes immediately.
3. **Share icon** → exports a text report (current state + last probes + full timeline).

Tip: add this app to AdGuard's exclusions for a comparison run. The difference between the two runs is AdGuard's effect.

Note: while the phone is in deep sleep, the 60s timer pauses. Network-change callbacks still arrive and trigger probes.

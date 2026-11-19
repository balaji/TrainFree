<link rel="stylesheet" href=".github/github.css">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/balaji/TrainFree@main/.github/github.css">

# Train Free

*iOS & Android · Free & Open Source · 100% Offline*

A gym workout logger that gets out of your way. Pick the exercise, punch in weight and reps between sets, and get back to the bar. No account, no sign-in, no cloud lock-in, and no subscription wall between you and your lifting history.

`SET 1` **Barbell Bench Press · 100 kg × 5** `✓ Saved to local SQLite`

> **In Development · Not on the App Store or Google Play yet**
>
> Train Free is currently in active development and will be released for both **iOS** and **Android**. Follow progress, read the architecture, or build it yourself on [GitHub](https://github.com/balaji/TrainFree).

## Built around four guarantees

No compromises for monetization, growth hacks, or server dependencies.

- **Native on iOS & Android**

  Built natively for both platforms — SwiftUI with iOS 26 Liquid Glass on iPhone, and Jetpack Compose with Material 3 Expressive on Android — backed by a shared Kotlin Multiplatform engine.

- **Always Free & Open Source**

  No subscriptions to unlock your own history, no paywalled exercise catalog, no ads, and no telemetry. Every line of code is open source on [GitHub](https://github.com/balaji/TrainFree).

- **100% Offline & Local-First**

  The app doesn't even request the `INTERNET` permission. Your profile, routines, workout sessions, and body metrics live in a plain on-device SQLite (Room) database that you own.

- **On-Device AI (When Available)**

  Uses your phone's built-in language models — **Apple Intelligence** (`FoundationModels`) on iOS and **Gemini Nano** (ML Kit GenAI) on Android — to narrate progress trends and answer questions over your training log. If your device doesn't support on-device AI, every logging and analytics feature works completely without it.

## What we refuse to compromise on

Opinionated rules that keep the app fast, honest, and respectful of your time.

1. **Logging speed beats features**

   Anything on the log-a-set path must work one-handed, in seconds, mid-workout. If a feature slows down logging a set, it doesn't ship.

2. **Deterministic math, local narration**

   1RM estimates (Epley, Brzycki, Lombardi, O'Conner), volume trends, and streaks are calculated deterministically in Kotlin. The on-device model never invents statistics — it only explains the verified numbers on your phone.

3. **Native feel over cross-platform shortcuts**

   An iPhone user gets a real iOS app (Liquid Glass chrome, system navigation transitions, swipe-back) and an Android user gets a real Material 3 Expressive app that follows their wallpaper and dark mode. Sharing business logic across platforms is an engineering strategy, never an excuse for a lowest-common-denominator UI.

4. **Your data outlives the app**

   Everything sits in a clean, documented SQLite schema on your device — never trapped in a proprietary store or locked behind someone else's server.

## Contributing & architecture

Architecture, build commands, tests, and conventions live in [AGENTS.md](https://github.com/balaji/TrainFree/blob/main/AGENTS.md) — it's written for coding agents and serves as the engineering README and local data model reference.

---

Built with Kotlin Multiplatform, SwiftUI, and Jetpack Compose · [GitHub](https://github.com/balaji/TrainFree) · [balaji.dev](https://www.balaji.dev) · [Privacy Policy](https://trainfree.balaji.dev/privacy)

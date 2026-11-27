---
title: Privacy Policy — Train Free
permalink: /privacy/
---

# Privacy Policy

**Effective date:** November 27, 2026  
**App:** Train Free (Android & iOS)  
**Developer:** Balaji Damodaran  

Train Free is a free, open-source, local-first workout logger. Your training data belongs to you and stays on your device unless you choose to back it up.

## 1. Summary

- **No developer accounts or servers.** Train Free does not operate any backend server, does not require a Train Free account, and does not use third-party analytics, crash reporting, or advertising SDKs.
- **Offline by default.** Network permissions (`INTERNET` and `ACCESS_NETWORK_STATE`) on Android are used solely for optional user-initiated or user-enabled Google Drive backups; ML Kit Clearcut telemetry components remain removed from the Android build.
- **No data selling or sharing.** Because we never collect your data, we never sell, rent, or share it with third parties.

## 2. Data Stored Locally on Your Device

All information you enter in Train Free is stored exclusively in a local SQLite database on your device:

- Profile information you choose to enter (such as display name, height, and weight units)
- Workout programs, routines, exercises, sets, reps, weights, workout history, and notes
- Body weight logs
- App preferences (theme, units, rest timer settings, and health sync toggles)

The developer does not have access to this database.

## 3. Health Connect (Android) & Apple Health (iOS)

Health platform integration is **optional** and disabled by default until you turn it on in Settings and grant permission through your operating system.

### Android — Health Connect

When enabled, Train Free requests only the following Health Connect permissions:

- **Write Exercise (`WRITE_EXERCISE`)**: Saves completed strength-training workout sessions logged in Train Free to Health Connect (and removes corresponding Train Free records if you delete them in Train Free).
- **Read Weight (`READ_WEIGHT`)**: Imports body weight measurements logged in other Health Connect apps (such as smart scales) into your local Train Free log.
- **Write Weight (`WRITE_WEIGHT`)**: Saves body weight entries you log in Train Free to Health Connect.

Data accessed via Health Connect is processed strictly on your device to provide these fitness-tracking features. It is never transmitted off your device, never transferred to third parties, and never used for advertising, marketing, or profiling. Train Free's use of information received from Health Connect adheres to the [Health Connect Permissions Policy](https://support.google.com/googleplay/android-developer/answer/9888170), including the Limited Use requirements.

### iOS — Apple Health (HealthKit)

When enabled, Train Free requests permission to save completed strength workouts (`HKWorkout`) and read/write body weight (`bodyMass`) via Apple HealthKit. HealthKit data stays on your device, is used only for workout and body-weight tracking inside Train Free, and is never shared with third parties or used for advertising or data brokering.

## 4. On-Device AI Features

On supported devices, Train Free uses your operating system's built-in on-device language models—**Gemini Nano** via Android AICore (ML Kit GenAI Prompt) on Android, and **Apple Intelligence** (`FoundationModels`) on iOS—to summarize workout trends and answer questions about your local training log.

- Prompts, workout context, and generated responses are processed locally on your device. Train Free does not send prompts or AI outputs to any developer or third-party cloud server.
- If you use the in-app **Report / Flag** option on an AI-generated response, the report is recorded locally on your device (excluded from manual backups) and is never transmitted over the network.

## 5. Backups & Exports

- **OS Backups**: On Android, if you have Android Auto Backup or device-to-device transfer enabled in your system settings, Android may back up Train Free's local database file (`trainfree.db`) under your Google account according to Google's backup privacy policies. On iOS, backups are governed by your device and iCloud backup settings. The developer cannot access your OS backups.
- **Manual Export / Import**: If you use Train Free's manual export feature, you choose where to save or share the exported file.
- **Optional Google Drive Backup & Restore**: If you enable **Auto-backup to Google Drive** or choose **Restore from Google Drive**, Train Free authenticates directly with Google using the limited `https://www.googleapis.com/auth/drive.file` scope (which grants access only to the `TrainFree/trainfree-backup.json` file created by Train Free, never to your other Drive files). Your workout, program, body-weight, profile, and journal backup JSON is sent directly between your device and your own Google Drive account—no developer server sits in between. Local AI content-report flags and Google Drive connection metadata are excluded from exported backups. You can turn off auto-backup at any time in Settings to disconnect your Google account from the device.

## 6. Device Permissions (Notifications & Rest Timer)

If you enable workout rest-timer alerts, Train Free may request permission to post local notifications (`POST_NOTIFICATIONS`) and schedule exact rest-timer alarms (`SCHEDULE_EXACT_ALARM`) solely to alert you when a rest period ends.

## 7. Data Retention & Deletion

Because all data lives solely on your device, you have complete control over retention and deletion:

- **In-app**: Delete individual workouts, routines, programs, or body-weight entries at any time.
- **Full removal**: Clear the app's storage in your device settings or uninstall Train Free to permanently delete the local database.
- **Health data**: Revoke Health Connect or Apple Health permissions—and delete any synced records—at any time in Android Health Connect settings or the Apple Health app.

Because Train Free does not store any data on external servers, no remote data exists to delete.

## 8. Children's Privacy

Train Free is not directed to children under 13 (or the applicable age of digital consent in your region) and does not knowingly collect personal information from anyone.

## 9. Changes to This Policy

If Train Free's privacy practices change (for example, if an optional cloud sync feature is introduced in the future), this policy will be updated at `https://trainfree.balaji.dev/privacy` with a new effective date before any such change takes effect.

## 10. Contact

If you have questions about this Privacy Policy, you can contact the developer at:

- **Email:** [hello@balaji.dev](mailto:hello@balaji.dev)
- **Source code & issues:** [github.com/balaji/TrainFree](https://github.com/balaji/TrainFree)

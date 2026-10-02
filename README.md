# Claude Agent — Native Android
**Lock Jaw Disc Golf · Waugs**

Full native Kotlin Android app giving Claude deep system-level access to your Samsung Galaxy Z Flip.

---

## What This Gives You

| Layer | Capability |
|---|---|
| **Communication** | Send/receive SMS, make calls, send email, reply to notifications inline |
| **System Controls** | Brightness, volume, WiFi, Bluetooth, flashlight, DND |
| **Accessibility** | Read any screen, click any UI element, input text in any app, scroll, navigate |
| **Notification Listener** | See ALL app notifications, dismiss them, reply inline |
| **Always-On Service** | Survives app close, restarts on boot, runs in background |
| **Floating Overlay** | Claude button floats over every app |
| **Voice I/O** | Speak to Claude, hear responses (TTS) |
| **Calendar** | Create events, check schedule |
| **Contacts** | Search, look up numbers |
| **App Usage** | Know which apps you've used |
| **Screen Reading** | Claude can see what's on screen at any moment |
| **Good Lock** | Open Good Lock + step-by-step refresh instructions |

---

## Build Requirements

- **Android Studio Hedgehog** (2023.1.1) or newer
- **JDK 17** (bundled with Android Studio)
- Android SDK 34
- A physical Android device (Galaxy Z Flip) or emulator API 26+

---

## Build Steps

```bash
# 1. Clone / unzip the project
# 2. Open in Android Studio
# 3. Let Gradle sync (first run downloads dependencies — may take 2-3 min)
# 4. Connect your Z Flip via USB (enable USB debugging in Developer Options)
# 5. Run > Run 'app'  OR  Build > Build Bundle(s)/APK(s) > Build APK(s)
```

APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

To install via ADB:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## First Launch Setup

### 1. Enter API Key
Settings → enter your Anthropic API key (console.anthropic.com)

### 2. Grant Critical Permissions (one-time, manual)

**Accessibility Service** ← most important
```
Settings → Accessibility → Installed Services → Claude Agent → Enable
```

**Notification Listener**
```
Settings → Notifications → Notification Access → Claude Agent → Enable
```

**Display Over Other Apps** (for floating overlay)
```
Settings → Apps → Claude Agent → Display over other apps → Allow
```

**Usage Access** (for app usage stats)
```
Settings → Apps → Special App Access → Usage Access → Claude Agent → Enable
```

**Do Not Disturb Access** (for DND toggle)
```
Settings → Notifications → Do Not Disturb Access → Claude Agent → Enable
```

**Write Settings** (for brightness control via API)
```
Settings → Apps → Claude Agent → Modify System Settings → Allow
```

---

## Architecture

```
MainActivity (Compose UI)
    ↕
AgentViewModel (state, Room DB)
    ↕
ClaudeAgentService (foreground, always-on)
    ├── ClaudeApiClient (Anthropic API)
    ├── PhoneActionExecutor (executes actions)
    ├── ContextCollector (phone state)
    ├── AgentAccessibilityService (screen read/interact)
    ├── AgentNotificationService (notification listener)
    └── VoiceManager (TTS + STT)

Receivers:
    ├── SmsReceiver (incoming SMS)
    ├── CallReceiver (call state)
    ├── BootReceiver (restart on boot)
    └── ReminderReceiver (alarm triggers)
```

---

## Adding New Actions

1. Add the action type and parameters to `data/Models.kt` → `PhoneAction`
2. Add to the system prompt in `claude/ClaudeApiClient.kt`
3. Add a handler in `phone/PhoneActionExecutor.kt` → `execute()`

---

## Package
`com.lockjaw.claudeagent`

---

## Note on Samsung-Specific Features

Good Lock doesn't have a public API. The app opens it and provides step-by-step UI instructions. For deeper Good Lock automation, you could use the Accessibility Service to click through its UI — add that as a future action type.

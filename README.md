# PrivacyPlus

A client-side privacy mod for Minecraft Java Edition. It cuts what Microsoft, servers and your own computer learn about you, adds end-to-end encrypted chat, and is built so that servers can't tell it's installed.

PrivacyPlus runs only on your game. It works on any server, and servers don't need it.

## Requirements

| | |
|---|---|
| Minecraft | Java Edition 26.3 |
| Java | 25 or newer |
| Loader | Fabric Loader 0.19.5+ (Fabric API isn't needed) **or** Forge 66.1+ |

## Features

### Privacy
- **Telemetry blocker**: game telemetry is dropped instead of being sent to Microsoft. The title screen shows how many events were blocked.
- **Incognito mode**: leaves no local traces of your session:
  - chat isn't written to the log files;
  - commands aren't saved to `command_history.txt`;
  - the Direct Connect address isn't remembered;
  - the log doesn't record which server you join.
- **Language spoof**: servers are told your language is US English, the most common one, so it can't single you out.
- **Private DNS**: server addresses are looked up over encrypted DNS (Cloudflare, Quad9 or a DNS-over-HTTPS service you choose), so your router, internet provider or Wi-Fi can't see which servers you look up. Off by default.
- **Log and crash-report cleaner**:
  - **What it removes:** your computer's user name and home folder, account names and UUIDs, session tokens and public IP addresses, from logs and crash reports.
  - **When:** new lines are cleaned as they're written; old logs are cleaned at startup.
  - **Optional:** remove chat from old logs, and delete logs after a number of days.

### Servers and network
- **Ping on request**: the multiplayer list doesn't ping every server when it opens; a server is pinged when you select it or press Refresh.
- **Resource-pack protection**: when a server's pack is hosted on another site, your username and UUID aren't sent with the download, and you're asked before it downloads.
- **Transfer guard**: asks before a server sends you to another server (Ask / Allow / Block).
- **Cookie guard**: cookies a server stores on your game are passed on only within the same site (or never), so separate servers can't use them to recognize you.
- **Link guard**: before a link from chat, a book or a dialog opens, it shows where the link really goes. It warns about IP-logger sites, link shorteners, bare IP addresses and lookalike letters.
- **Clipboard guard**: asks before a server copies text to your clipboard, and warns when the text looks like a command or a link.
- **Command suggestions on Tab**: what you type into a command is sent to the server only when you press Tab, instead of on every keystroke.

### Stealth
- **Brand spoof**: reports the client as plain `vanilla` instead of `fabric` or `forge`.
- **Hidden mod channels**: mod network channels and mod handshakes aren't shown to servers. On Forge, the join marker is removed too.
- **Translation-probe shield**: servers can't detect mods by making signs or anvils translate a mod's text. Only Minecraft's own texts and the server's own resource-pack texts are translated there.
- **Probe alarm**: a quiet popup tells you when a server tries to fingerprint or track you (sign and anvil probes, cookies, transfers, packs from other sites, mod queries). `/pp probes` lists everything noticed this session.

### Encrypted chat
- **The lock**: a lock button next to the chat box turns encryption on and off. Encrypted messages look like random text to the server and to anyone without the key.
- **Channels**: share a passphrase with your friends, or use a *friend channel* whose key is managed for you. Removing someone from a friend channel gives everyone else a new key automatically.
- **Friends**: exchange personal keys with a friend in game. Your private messages to them are then encrypted automatically. A safety code lets you both check that nobody is listening in.
- **`/coords`**: shares your location, encrypted, with your channel. Friends see it with **[Copy]** and **[Track]**; Track shows the distance and direction at the top of the screen.
- **Unsigned chat per server**: optionally don't sign your chat on a chosen server, so it can't be reported. The server can tell, and some refuse unsigned chat.

## Installing

1. Install Fabric Loader or Forge for Minecraft 26.3.
2. Put `privacyplus-fabric-26.3-<version>.jar` or `privacyplus-forge-26.3-<version>.jar` into your `mods` folder.
3. Start the game. Every privacy feature is on by default, except incognito mode, Private DNS and the optional log settings.

## Using it

Everything can be changed in game, with no commands or file editing needed:
- **Settings**: click **PrivacyPlus** in the top-left corner of the title screen, the pause menu or the multiplayer screen, or type `/pp`.
- **Chat box buttons**:
  - **Lock**: click to turn encryption on or off, shift-click to switch channel, right-click for the encrypted-chat settings.
  - **Gear**: opens the encrypted-chat settings.
  - **📍**: shares your location (right-click to pick a channel).
- **Multiplayer screen**: the **Private DNS** switch.

### Commands

These commands are handled on your computer and never sent to the server.

| Command | What it does |
|---|---|
| `/pp` | Open the PrivacyPlus settings |
| `/pp status` | Show encrypted-chat status |
| `/pp settings` | Open the encrypted-chat settings |
| `/pp <channel>` | Encrypt your messages to a channel |
| `/pp <channel> <message>` | Send one encrypted message |
| `/pp off` | Turn encryption off |
| `/pp probes` | List the probes noticed this session |
| `/pp incognito [on\|off]` | Switch incognito mode |
| `/pp dns [off\|cloudflare\|quad9\|<https address>]` | Show or change Private DNS |
| `/pp reload` | Reload the chat settings file |
| `/coords` | Share your location with your channel |
| `/coords <channel> [label]` | Share it with a specific channel, optionally with a label |
| `/coords label <text>` | Share it with a label |
| `/coords stop` | Stop tracking a shared location |
| `/coords help` | Show help for `/coords` |

## What servers can still see

PrivacyPlus can't hide everything:
- **Your IP address.** Servers always see it, and Private DNS doesn't change that.
- **Encrypted text.** Servers see that a message contains encrypted-looking text, and who a private message goes to.
- **Transfers and cookies.** With the transfer guard on *Ask*, you arrive at the next server a few seconds late. With the cookie guard on, the next server gets no cookie. Two servers working together could notice either.
- **Turning things off.** Turning off a stealth feature (for example, to use voice chat or join a modded server) makes PrivacyPlus visible to servers again, the same as any other mod.

## Your files

Settings live in your game's `config` folder:

| File | Contents |
|---|---|
| `privacyplus.properties` | General settings |
| `privacyplus-chat.properties` | Channels and chat options (includes your channel passphrases) |
| `privacyplus-friends.properties` | Friends, friend channels and their keys |
| `privacyplus-identity.properties` | Your personal key |

The chat, friends and identity files are secret: anyone who has them can read your encrypted chat. Don't share your `config` folder, for example inside a modpack.

## Building from source

```bash
./gradlew build
```

The jars are written to `fabric/build/libs/` and `forge/build/libs/`. Gradle needs Java 25 to run; the project pins its build daemon to it.

The code is split into three modules:
- `common/`: almost all of the mod.
- `fabric/` and `forge/`: the loader entry points.

Unit tests run with `./gradlew build`. A dev-only in-game self-test for Fabric runs with `./gradlew :fabric:runSelftest`.

## License

All Rights Reserved. © KaiWasabi

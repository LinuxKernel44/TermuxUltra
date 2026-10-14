<p align="center">
  <img src="art/termuxultra_icon.png" width="128" alt="TermuxUltra icon">
</p>

# TermuxUltra

TermuxUltra is a fork of [termux/termux-app](https://github.com/termux/termux-app), the Android terminal emulator and Linux environment.

It is currently a lightly customized build of upstream Termux: new name, new launcher icon and its own signed releases. It is the base for further changes.

## What differs from upstream

- App name is **TermuxUltra** (launcher label, notifications, log tags).
- New launcher icon: adaptive, with a monochrome layer for Android 13+ themed icons. The source is [`art/termuxultra_icon.svg`](art/termuxultra_icon.svg).
- Release builds are signed with the TermuxUltra key (see [Signing](#signing)).
- SHIFT in the default extra keys, held like a real modifier key.
- **Tab bar** instead of the left drawer, see [Tab bar](#tab-bar).
- **Search** in the terminal history, see [Search](#search).
- An **About TermuxUltra** screen.
- An option to **acquire the wake lock whenever Termux is opened**, see [Wake lock](#wake-lock).

Everything else, including the terminal, bootstrap packages and `pkg`, behaves like upstream Termux.

## Tab bar

The sessions are tabs in a bar over the top of the terminal. The left drawer is gone.

- Swipe down from the top edge of the terminal to show the bar. Swipe up on it to hide it. It hides itself after a few seconds, the delay is set in Settings > Tab bar.
- It appears for a moment when you switch to another session.
- Tap a tab to open it, tap its cross to close it. Closing a tab with a running process asks for confirmation.
- Long press a tab for rename, kill and close. Long press then drag to reorder the tabs.
- A dot on a tab means that a background session printed something or rang the bell.
- `+` creates a session, and a long press on it creates a named or failsafe one. The menu has search, keyboard, extra keys, settings and about.

## Search

Open it from the tab bar menu or from the long press menu of the terminal.

- Searches the whole scrollback as you type, highlights every match and jumps with the arrows. The counter shows the position among the matches.
- `Aa` makes the search case sensitive, `.*` uses regular expressions.
- `All tabs` lists the matches of every session, grouped by tab. Tap one to go to that tab and line.
- `Shell history` searches `~/.bash_history` and `~/.zsh_history`. Tap a command to put it at the prompt without running it, long press to copy it. Bash only writes its history when a shell exits, unless you set `PROMPT_COMMAND="history -a"`.

## Wake lock

Settings > TermuxUltra options > Acquire wake lock when opening Termux. When it is on, the CPU and Wi-Fi wake locks are taken every time Termux is opened, as if you pressed *Acquire wakelock* in the notification. The notification then reads "(wake lock held)" and its button becomes *Release wakelock*, which still releases the lock for the current run. Turning the option on takes effect immediately.

Unlike the notification button, this does not ask to disable battery optimization at every opening.

## Package name

The application ID is still `com.termux`, on purpose:

- Official Termux packages are built for the `/data/data/com.termux` prefix, so they keep working.
- The Termux plugins (Termux:API, Termux:Boot, Termux:Widget and so on) keep working.

The consequences:

- TermuxUltra **cannot be installed next to** the original Termux. Uninstalling it first erases its data.
- Updates only install over a build signed with the same key. A build from F-Droid, GitHub or Google Play must be uninstalled first.
- Plugins from other sources must be signed with the same key as the app. Plugins signed with upstream keys will not be accepted by a TermuxUltra install.

## Installation

Download the APK from the [latest release](https://github.com/LinuxKernel44/TermuxUltra/releases/latest) and install it. It is a universal APK that works on every supported ABI.

Requires Android 7.0 or later, like upstream.

Verify the signature before installing if you want to:

```sh
apksigner verify --print-certs TermuxUltra-*-release.apk
```

The SHA-256 digest of the signing certificate is:

```
6C:A5:86:C2:BE:CB:30:F6:C6:59:BD:42:08:56:2E:33:3A:20:7D:84:61:F7:4B:25:57:E9:E8:E2:90:92:C7:47
```

## Building

You need JDK 17 and the Android SDK (`ANDROID_HOME` set).

```sh
git clone https://github.com/LinuxKernel44/TermuxUltra.git
cd TermuxUltra
./gradlew assembleDebug     # signed with the public upstream test key
./gradlew assembleRelease   # signed with your own key, see below
```

Outputs are in `app/build/outputs/apk/`.

## Signing

`assembleRelease` reads its credentials from `~/.android-signing/keystore.properties`. Set the `TERMUXULTRA_KEYSTORE_PROPERTIES` environment variable to use another file. The file must contain:

```properties
storeFile=/absolute/path/to/keystore.jks
keyAlias=your-alias
storePassword=...
keyPassword=...
```

Without that file the release build is produced unsigned. Debug builds use the public test key from upstream, so they never update a release build, and a release build never updates them.

The official TermuxUltra keystore is private and is not in this repository.

## Staying up to date with upstream

```sh
git remote add upstream https://github.com/termux/termux-app.git
git fetch upstream
git merge upstream/master
```

The original upstream README, with the full Termux documentation, is kept in [`docs/UPSTREAM_README.md`](docs/UPSTREAM_README.md). The [Termux wiki](https://wiki.termux.com) covers usage.

## License

Released under [GPLv3 only](https://www.gnu.org/licenses/gpl-3.0.html), same as upstream. See [LICENSE.md](LICENSE.md) for the exceptions that apply to the terminal libraries.

All credit for Termux goes to the [Termux contributors](https://github.com/termux/termux-app/graphs/contributors).

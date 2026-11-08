# WoW UI Sync

Self-hosted backup, sync, and setup switching for your whole World of Warcraft UI: addons, account-wide settings, and per-character SavedVariables.

- **Clone** your UI to any PC: push from one machine, pull on another. Only what differs is transferred.
- **Back up** safely: every change the app makes to your WoW folder is preceded by a full local backup you can restore.
- **Switch setups**: pull another account's setup (a stream setup, a partner's setup) onto a PC, selectively, without losing your own.
- **Self-hosted**: one server jar on your own machine (a VPS or a home PC). One server can host many accounts.

Open source under the GNU GPL v3. **Not affiliated with or endorsed by Blizzard Entertainment.** World of Warcraft and WoW are trademarks of Blizzard Entertainment, Inc.

This version syncs the **Forever** game flavor (`_forever_`). Other flavors are detected and shown as "coming later".

---

## Contents

1. [Requirements](#requirements)
2. [Server: install](#server-install)
3. [Server: step-by-step on Ubuntu 24.04 (systemd service)](#server-step-by-step-on-ubuntu-2404-systemd-service)
4. [Server: running and stopping](#server-running-and-stopping)
5. [Server: upgrading](#server-upgrading)
6. [Server: resetting root](#server-resetting-root)
7. [Server: password reset emails (SMTP)](#server-password-reset-emails-smtp)
8. [Keys and security advice](#keys-and-security-advice)
9. [Client walkthrough](#client-walkthrough)
10. [Disk space](#disk-space)
11. [Building from source](#building-from-source)
12. [License and disclaimer](#license-and-disclaimer)

---

## Requirements

- **Java 21** or newer, on every machine that runs the client or the server. Nothing else: no FTP, no mail server, no database server.
  - Windows / macOS: install a Java 21 runtime, for example Eclipse Temurin from <https://adoptium.net>.
  - Ubuntu / Debian: `sudo apt install openjdk-21-jre-headless` (server) or `openjdk-21-jre` (client).
- The client and server run on Windows, Linux, and macOS (x64 and arm64), in any combination, including both on one machine.

Downloads (GitHub Releases):

- `wow-ui-sync-server-<version>.zip`: `wow-ui-sync-server.jar`, `scripts/`, this README, LICENSE.
- `wow-ui-sync-client-<version>.zip`: `wow-ui-sync-client.jar`, this README, LICENSE.

## Server install

The server runs headless and only writes a log. Setup is a one-time interactive command, usually over SSH.

1. Create an unprivileged user to run the server (recommended on Linux):

   ```sh
   sudo useradd --system --create-home --shell /usr/sbin/nologin wowuisync
   ```

2. Unzip the server zip somewhere that user can read, for example `/opt/wow-ui-sync`, so you have
   `/opt/wow-ui-sync/wow-ui-sync-server.jar` and `/opt/wow-ui-sync/scripts/`.

3. Run the installer as that user (for a complete Ubuntu + systemd walkthrough, see
   [the step-by-step guide](#server-step-by-step-on-ubuntu-2404-systemd-service)):

   ```sh
   sudo -u wowuisync /opt/wow-ui-sync/scripts/install.sh
   ```

   On Windows, double-click `scripts\install.bat`.

   The installer asks for:

   - the **data folder** (a full path; default `~/wow-ui-sync-data`). Everything the server stores goes there.
   - the **port** (default **2026**) and the **public host name or IP address** clients will use (it offers a guess).
   - root's **OpenSSH public key line** (recommended), or press Enter to set a **password** instead (typed twice, hidden, at least 8 characters).
   - root's **email** (optional, used only for password reset emails).

   It then writes `server.json` (next to the jar and in the data folder), creates the database and the TLS certificate,
   locks down permissions, and creates the **root** account. At the end it prints the host, port, username `root`, the login
   method, and the **TLS certificate SHA-256 fingerprint**. Keep that fingerprint: the client shows it on first connect so you
   can confirm you are talking to your server.

4. **Open the port.** On Ubuntu with ufw:

   ```sh
   sudo ufw allow 2026/tcp
   ```

   On Windows, allow `java.exe` (or TCP port 2026) in Windows Defender Firewall when asked.

### Home servers, LAN, and same-machine use

- **Same machine**: run the server and the client on one PC and connect to `localhost`.
- **LAN**: use the server PC's local address (for example `192.168.1.20`) as the host.
- **From the internet on a home connection**: forward TCP port 2026 on your router to the server PC, and use your public IP
  address or a dynamic-DNS name as the host. The public host given to `install` is what invite codes contain; to change it later,
  edit `publicHost` in `<data folder>/server.json` and restart the server.

## Server: step-by-step on Ubuntu 24.04 (systemd service)

A complete walkthrough for an Ubuntu 24.04 server running WoW UI Sync as a systemd service. It uses these example values;
replace them with your own everywhere below:

| What | Example | Notes |
|---|---|---|
| Program folder | `/opt/wow-ui-sync` | where `wow-ui-sync-server.jar` and `scripts/` live |
| Data folder | `/opt/wow-ui-sync-data` | database, TLS certificate, stored setups |
| Service user | `wowuisync` | any existing unprivileged user also works (for example the user that runs a game server) |

> **Important:** run the installer **as the service user** (`sudo -u wowuisync …`), not as root with plain `sudo`.
> The installer locks the data folder, keys, and database so only their owner can read them. If root runs it, root owns
> them and the service cannot start.

### 1. Install Java 21

```sh
java -version
```

If it is not 21 or newer:

```sh
sudo apt update
sudo apt install -y openjdk-21-jre-headless
```

If several Java versions are installed (for example for a game server that needs another one), find the Java 21 binary with
`ls /usr/lib/jvm/` (usually `/usr/lib/jvm/java-21-openjdk-amd64/bin/java`) and use that full path in step 7.

### 2. Create the service user (skip if you use an existing one)

```sh
sudo useradd --system --create-home --shell /usr/sbin/nologin wowuisync
```

### 3. Put the files in place

Upload the server zip to the program folder and unzip it:

```sh
sudo mkdir -p /opt/wow-ui-sync
cd /opt/wow-ui-sync
sudo apt install -y unzip
sudo unzip wow-ui-sync-server-<version>.zip
```

You should now have `/opt/wow-ui-sync/wow-ui-sync-server.jar` and `/opt/wow-ui-sync/scripts/`.

### 4. Give the service user ownership

The installer writes `server.json` next to the jar, so the service user must be able to write both folders:

```sh
sudo mkdir -p /opt/wow-ui-sync-data
sudo chown -R wowuisync:wowuisync /opt/wow-ui-sync /opt/wow-ui-sync-data
sudo chmod +x /opt/wow-ui-sync/scripts/*.sh
```

### 5. Run the installer as the service user

```sh
cd /opt/wow-ui-sync
sudo -u wowuisync java -jar /opt/wow-ui-sync/wow-ui-sync-server.jar install
```

Answers:

| Prompt | Answer |
|---|---|
| Data folder (full path) | `/opt/wow-ui-sync-data` |
| Port | Enter (keeps `2026`) |
| Public host name or IP address | your server's public IP or domain name (it goes into invite codes) |
| Public key line | paste your `.pub` line, **or** press Enter to use a password |
| Password (only if no key) | type it twice (hidden, at least 8 characters) |
| Email | optional, Enter to skip |
| Start the server now? | **N** (systemd runs it) |

**Copy the TLS certificate fingerprint it prints** (`AB:CD:…`). The client shows the same value on your first connection so you
can confirm you are talking to your server. See [Keys and security advice](#keys-and-security-advice) for making a key pair.

### 6. Open the port

```sh
sudo ufw allow 2026/tcp
sudo ufw status
```

If your hosting provider also has a firewall in its control panel, open TCP 2026 there as well.

### 7. Create the service

```sh
sudo nano /etc/systemd/system/wow-ui-sync.service
```

Paste the following (it is also in `scripts/wow-ui-sync.service`). Adjust the user, group, and paths, and use the full Java 21
path from step 1 in `ExecStart` if needed:

```ini
[Unit]
Description=WoW UI Sync server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=wowuisync
Group=wowuisync
WorkingDirectory=/opt/wow-ui-sync
ExecStart=/usr/bin/java -jar /opt/wow-ui-sync/wow-ui-sync-server.jar run
Restart=on-failure
RestartSec=10
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full

[Install]
WantedBy=multi-user.target
```

Save with Ctrl+O, Enter, and exit with Ctrl+X.

### 8. Start it

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now wow-ui-sync
sudo systemctl status wow-ui-sync
```

You want `active (running)`. Follow the log and look for `Listening on 0.0.0.0:2026 (TLS 1.3)`:

```sh
sudo journalctl -u wow-ui-sync -f
```

The same log is written to `/opt/wow-ui-sync-data/logs/server.log`.

### 9. Connect from your PC

Run `wow-ui-sync-client.jar`, choose your WoW folder in Settings, then **Connect → New**: host = your server's IP or name,
port `2026`, username `root`, and your password or private key file. Press **Connect**, check that the fingerprint matches the
one from step 5, and accept.

### Everyday commands

| Task | Command |
|---|---|
| Stop | `sudo systemctl stop wow-ui-sync` |
| Start / restart | `sudo systemctl start wow-ui-sync` / `sudo systemctl restart wow-ui-sync` |
| Follow the log | `sudo journalctl -u wow-ui-sync -f` |
| Reset root's password or key | `sudo -u wowuisync java -jar /opt/wow-ui-sync/wow-ui-sync-server.jar reset-root` |
| Upgrade | stop the service, replace the jar, `sudo chown wowuisync:wowuisync` it, start the service |

With systemd, stop the server with `systemctl` rather than `scripts/stop.sh`. `stop.sh` works too, but systemd will not restart
the server until you `systemctl start` it again.

### Troubleshooting

- **"Permission denied" in the log**: something was created as root. Run
  `sudo chown -R wowuisync:wowuisync /opt/wow-ui-sync /opt/wow-ui-sync-data`, then `sudo systemctl restart wow-ui-sync`.
- **"This data folder already has a root account"**: the install already finished. Use `reset-root` to change root's credentials.
- **The client cannot connect**: check that `sudo ss -tlnp | grep 2026` shows Java listening, then check ufw and your hosting
  provider's firewall.

## Server: running and stopping

- **Linux / macOS**: `scripts/start.sh` starts the server inside a `screen` session named `wowuisync`
  (attach with `screen -r wowuisync`, detach with Ctrl+A then D). If `screen` is not installed, it runs in the current terminal.
  If the server exits unexpectedly, the script prints the exit code, counts down 10 seconds, and restarts it.
- **Windows**: `scripts\start.bat` opens its own console window with the same restart loop.
- **Stop**: `scripts/stop.sh` or `scripts\stop.bat`. The server notices within about 2 seconds, ends transfers cleanly, and exits.
  The start script's loop then ends too.
- **systemd** (optional, Linux): `scripts/wow-ui-sync.service` is a ready unit that runs as the `wowuisync` user. Copy it to
  `/etc/systemd/system/`, adjust paths if needed, then `sudo systemctl daemon-reload && sudo systemctl enable --now wow-ui-sync`.
  Use `systemctl stop wow-ui-sync` to stop it.

Direct commands (the scripts are thin wrappers around these):

```sh
java -jar wow-ui-sync-server.jar run          # start in the foreground
java -jar wow-ui-sync-server.jar stop         # ask a running server to stop
java -jar wow-ui-sync-server.jar data-dir     # print the data folder
java -jar wow-ui-sync-server.jar version
# any command also accepts --data <path> to point at a data folder explicitly
```

The log is `<data folder>/logs/server.log` (rotating, 10 MB × 5).

## Server: upgrading

Stop the server, replace `wow-ui-sync-server.jar` (and `scripts/`), start it again. The data folder is untouched.
If the new version needs a newer database layout, it first copies the database to
`<data folder>/db-backups/wowuisync-v<old>-<timestamp>.db` and then migrates it. Clients that are too old for the server
(or the other way round) are told which version to update to.

## Server: resetting root

If you lose root's key or password, run on the server (it works while the server is running):

```sh
sudo -u wowuisync /opt/wow-ui-sync/scripts/reset-root.sh
```

Choose a new public key or a new password. Root's active sessions end.

## Server: password reset emails (SMTP)

Accounts that log in with a password and have an email can use **Forgot password** on the client's Connect screen: the server
emails a 6-digit code (valid 15 minutes, 5 attempts). This needs SMTP settings in `<data folder>/server.json`; restart the
server after editing.

Gmail (with an app password):

```json
"smtp" : {
  "enabled" : true,
  "host" : "smtp.gmail.com",
  "port" : 587,
  "username" : "you@gmail.com",
  "password" : "your-16-character-app-password",
  "from" : "WoW UI Sync <you@gmail.com>",
  "security" : "starttls"
}
```

A provider using implicit TLS on port 465:

```json
"smtp" : {
  "enabled" : true,
  "host" : "mail.example.com",
  "port" : 465,
  "username" : "sync@example.com",
  "password" : "secret",
  "from" : "sync@example.com",
  "security" : "ssl"
}
```

Key-only accounts are recovered by their manager (root or their superuser) setting a new key; root is recovered with `reset-root`.

## Keys and security advice

- All traffic uses **TLS 1.3** on one port. The certificate is self-signed; the client pins its fingerprint per host and port on
  first connect and blocks with a clear warning if it ever changes. Invite codes carry the fingerprint and pin it automatically.
- Each account uses **either a public key or a password**, never both. Supported keys: `ssh-ed25519`, `ssh-rsa` (2048 bits or more),
  `ecdsa-sha2-nistp256`, in OpenSSH format. Login is challenge-response: the private key never leaves your PC, and the client
  reads passphrase-protected key files (it asks for the passphrase).
- **Use a separate key pair for WoW UI Sync**, for example:

  ```sh
  ssh-keygen -t ed25519 -f ~/.ssh/wowuisync -C "wow-ui-sync"
  ```

  Paste the contents of `~/.ssh/wowuisync.pub` where a public key is asked for, and choose `~/.ssh/wowuisync` in the client.
  These keys are only used by WoW UI Sync; they never grant SSH or shell access to the server.
- **PuTTYgen users**: load or generate your key, then use **Conversions → Export OpenSSH key** to save the private key file the client
  can read. For the public key, copy the text from "Public key for pasting into OpenSSH authorized_keys file".
- Run the server as an **unprivileged OS user** (for example `wowuisync`) and open **only the sync port**.
- Logins lock an account for 15 minutes after 5 failures; more than 20 failures from one IP address in 10 minutes block that
  address for 15 minutes. Both limits are in `server.json` (`lockout`).

## Client walkthrough

Unzip the client anywhere and run `wow-ui-sync-client.jar` (double-click, or `java -jar wow-ui-sync-client.jar`). It keeps its
settings, database, logs, and resumable downloads in `wow-ui-sync-data/` next to the jar.

1. **Choose your WoW folder** in Settings (for example `C:\Games\World of Warcraft`; on macOS `/Applications/World of Warcraft` is
   suggested). The client detects the flavor folders inside it; Forever is enabled.
2. **Connect.** The Connect screen lists saved connections (like WinSCP's site manager): New, Edit, Delete, Connect. A connection
   has a name, host, port (2026), username, and either a password (saved on this PC in plain text only if you tick the box,
   otherwise asked each time) or a private key file. On the first connection you are shown the server's certificate fingerprint:
   compare it with the one `install` printed. Choosing a connection or an account never changes your files.
3. **Join with invite code**: if someone gave you a `wus1-…` code, use this button on the Connect screen. Choose a username, a key
   file (only its public key is sent) or a password, and optionally an email. A connection is saved for you.
4. **Status.** The main window shows, for the selected account and profile:
   - **Up to date**
   - **Local changes - push recommended**
   - **Server is newer (from Laptop, Nov 7, 9:42 PM) - pull recommended**
   - **Conflict**: both sides changed; **Show details** lists what differs.
   - **Different from the server**: this PC has not synced with that account yet.
5. **Push** makes the server copy an exact mirror of this PC: new and edited files are uploaded, files you deleted are removed.
   A review dialog lists New, Edited, and Removed files first. Pushing to an account other than your own requires typing its
   username. The first push uploads everything; later pushes only send changes. Transfers can be paused, resumed, and survive
   dropped connections; nothing already uploaded is sent again.
6. **Pull…** opens the selection window: every addon of the source account with **Addon** and **Include data** checkboxes, a
   **General settings** row (macros, key bindings, chat and layout, Blizzard settings), and **All addons / All data / None**.
   - **Replace** (default): your AddOns folder ends up with exactly the selected addons.
   - **Keep my other addons**: only the selection is copied; nothing else is removed.
   - Addons without "Include data" keep your own saved settings.

   WoW must be closed. After the review dialog the client makes a **full local backup**, downloads only differing files, and applies
   them. **If you use CurseForge, WowUp, or a similar addon manager, run a rescan or refresh afterwards** so it picks up the synced addons.
7. **Backups** lists the local backups of this flavor, newest first ("Girlfriend · Nov 7, 2026, 9:30:53 PM · Before pull from Azotorp").
   **Manual backup**, **Restore** (backs up the current state first, so a restore can itself be undone), and **Delete**.
   Backups are folders inside the flavor folder, `Interface_<account>_<date>` and `WTF_<account>_<date>`; WoW ignores them.
8. **Addons** is a local manager that works without a server: grouped addons with their sizes; **Delete addon** removes its folders,
   **Delete data** removes its SavedVariables across all characters. A backup is made first. WoW must be closed.
9. **Accounts** (root and superusers): create accounts (password or public key), set new credentials, edit emails, quotas and caps,
   delete accounts (type the username to confirm), and create, copy, and revoke invite codes. **My account** lets every user change
   their own email and password or key.

### Roles

| | root | superuser | user |
|---|---|---|---|
| Data access | every account | itself and the users it owns | itself |
| Can create | superusers, users, invites | users and user invites | nothing |
| Manages | every account | the users it owns | its own password/key and email |

Superusers are group owners, not server admins: they never see root, other superusers, or users they do not own. Root can cap
how many users a superuser may have and give the group a shared storage limit. Deleting a superuser moves its users to root.

### What is synced

`Interface/AddOns/<addon folders>` and `WTF/` of the flavor folder. Loose files directly in `Interface/AddOns` (such as `.zip`
backups) are ignored. Default exclusions (editable in Settings): `WTF/Config.wtf`, `WTF/gamecontrollerdb.txt`,
`WTF/GamePadConfig_*.json`, `*.bak` and `*.old` under WTF, `.git` folders in addons, `Thumbs.db`, `desktop.ini`, `.DS_Store`.

## Disk space

- **Server**: each account keeps one current copy per profile. Text and compressible files are stored zstd-compressed;
  already-compressed audio and images are stored as they are. A typical large UI (about 3 GB with many sound packs) needs a bit
  less than its own size. Objects no longer referenced are removed after each push.
- **Client**: every pull, restore, and addon delete first makes a **full copy** of `Interface` and `WTF` inside the flavor folder.
  Backups are kept until you delete them in Backups, so delete old ones from time to time. The client checks free space before a
  backup or pull and refuses with a clear message if it will not fit.
- Interrupted downloads are kept in `wow-ui-sync-data/partial/` and resumed on the next pull; partial uploads on the server are
  deleted after 7 days.

## Building from source

```sh
mvn -B clean package
```

builds `common`, `server`, and `client` and writes `dist/wow-ui-sync-server.jar` and `dist/wow-ui-sync-client.jar`.
`DESIGN.md` is the full specification; `DEVLOG.md` is the design history.

## License and disclaimer

WoW UI Sync is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as
published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is
distributed WITHOUT ANY WARRANTY. See `LICENSE`.

WoW UI Sync is not affiliated with or endorsed by Blizzard Entertainment. World of Warcraft and WoW are trademarks of Blizzard
Entertainment, Inc. This project contains no Blizzard logos, artwork, or fonts.

# Setting up Baseline

Baseline shows the data your Amazfit watch collects. It reads that data from your own server, never from a third party, so there are three pieces:

```
 Amazfit watch ─► Zepp app ─► Zepp cloud
                                  │  (ZeppBridge pulls your data on a timer)
                                  ▼
             your server:  ZeppBridge library (SQLite)
                                  │  (Baseline Bridge reads it, read-only)
                                  ▼
                           Baseline Bridge  ──HTTPS over Tailscale──►  Baseline app
```

- **[ZeppBridge](https://github.com/lingcang728/ZeppBridge)** is an open-source tool that copies everything from your Zepp account into a local SQLite library. That includes HRV, sleep stages, SpO₂, stress and readiness.
- **Baseline Bridge** (in [`bridge/`](../bridge)) is one small Python file. It reads that library through ZeppBridge's documented export and serves a compact JSON feed to the app. It needs a token and has no dependencies beyond the Python standard library. It only reads, except when you tap **Sync now** in the app: then it runs the same `zeppbridge-cli sync` your timer runs. Set `BASELINE_ALLOW_SYNC=0` to turn that off.
- **Baseline** is this Android app.

Allow about 30–45 minutes the first time. After that it runs on its own.

---

## What you need

| | |
|---|---|
| Watch | Any Amazfit or Zepp device that syncs to the Zepp app |
| A computer, once | macOS, Windows or Linux desktop, to sign in to Zepp through the ZeppBridge desktop app |
| An always-on Linux machine | A VPS, NAS, home server or Raspberry Pi, with Docker. ARM64 and x86-64 both work. |
| Tailscale (recommended) | Free. Gives you private HTTPS between your phone and the server with no open ports. |
| Android 10+ phone | Baseline needs Android 10 (API 29) or later. |

---

## 1. Connect your Zepp account with ZeppBridge

1. Install the ZeppBridge desktop app from its [releases page](https://github.com/lingcang728/ZeppBridge/releases).
   - **macOS:** if it says the app is "damaged", that's Gatekeeper reacting to an unsigned download. Follow ZeppBridge's own README for the fix.
2. On a headless server the operating system keychain isn't available, so the server needs a file-based credential store. Start the desktop app once with that store so the sign-in lands in a file you can copy:
   ```bash
   # macOS
   ZEPPBRIDGE_CREDENTIAL_STORE=file /Applications/ZeppBridge.app/Contents/MacOS/zeppbridge
   ```
   On Linux, set the same variable before launching. On Windows, set it in the shell you launch from.
3. In ZeppBridge, connect both channels:
   - **Official Zepp connection:** activity, sleep, heart rate and workouts.
   - **Advanced data:** HRV, SpO₂, stress and readiness. Baseline's best features depend on these.
4. Let it run one sync. Then find its data folder. Settings shows the path; on macOS it's `~/Library/Application Support/com.zeppbridge.ZeppBridge/data`. The folder contains `auth.json`, `credentials.json` and `zepp.db`.

> `credentials.json` holds your Zepp tokens. Treat it like a password: copy it only to your own server, keep it at mode `0600`, and delete it from your computer afterwards.

## 2. Run ZeppBridge on your server

Follow ZeppBridge's [Docker guide](https://github.com/lingcang728/ZeppBridge/blob/main/docs/guides/docker.md). In short:

```bash
git clone https://github.com/lingcang728/ZeppBridge && cd ZeppBridge
git checkout v3.1.1          # or the latest release tag
docker build -f packaging/docker/Dockerfile -t zeppbridge:local .
```

Copy the data folder from step 1 to the server, for example to `/opt/appdata/zeppbridge/data`, owned by your user with mode `0700`. Then check it works:

```bash
docker run --rm --user "$(id -u):$(id -g)" -e TZ=Asia/Kolkata \
  -v /opt/appdata/zeppbridge/data:/data zeppbridge:local \
  zeppbridge-cli sync --mode incremental --json
```

**Set `TZ` to your timezone.** ZeppBridge files readings under local days.

Schedule that command with a systemd timer; ZeppBridge's guide has a ready-made unit. Hourly works well. Add `SuccessExitStatus=4` to the unit, because exit code 4 means another process held the lock and the run was skipped.

**Backfill your history once.** Baseline compares you with your own last 60 days. Readiness unlocks after 14 nights, so pulling history makes it useful from day one:

```bash
docker run --rm --user "$(id -u):$(id -g)" -e TZ=Asia/Kolkata \
  -v /opt/appdata/zeppbridge/data:/data zeppbridge:local \
  zeppbridge-cli sync --mode history --days 365
```

Only run ZeppBridge on one machine. Zepp rotates refresh tokens, so two machines syncing the same account will log each other out.

## 3. Run Baseline Bridge

On the same server:

```bash
git clone https://github.com/SelinJodhani/baseline && cd baseline/bridge
docker build --build-arg ZEPPBRIDGE_IMAGE=zeppbridge:local -t baseline-bridge:local .

echo "BASELINE_TOKEN=$(openssl rand -base64 36 | tr -d '/+=\n')" > .env && chmod 600 .env
```

Edit [`compose.yaml`](../bridge/compose.yaml):
- `TZ`: your timezone.
- `ZEPPBRIDGE_DATA`: the folder from step 2.
- `ZEPPBRIDGE_UID` / `ZEPPBRIDGE_GID`: the owner of that folder (`id -u`, `id -g`).

Then start it and check it:

```bash
docker compose up -d
curl -s http://127.0.0.1:8787/healthz        # {"ok": true, ...}
```

The bridge listens only on `127.0.0.1`. Don't publish port 8787 to the internet.

## 4. Reach it from your phone (Tailscale)

1. Install [Tailscale](https://tailscale.com/download) on the server and on your phone, and sign both in to the same tailnet.
2. On the server, put HTTPS in front of the bridge:
   ```bash
   sudo tailscale serve --bg --https=8444 http://127.0.0.1:8787
   tailscale serve status       # shows https://<machine>.<tailnet>.ts.net:8444
   ```

The URL is reachable only from your own devices, and it comes with a valid certificate.

<details>
<summary>Not using Tailscale?</summary>

Any HTTPS reverse proxy works, for example Caddy or nginx with Let's Encrypt. Point it at `127.0.0.1:8787`. The bridge already requires the token. Still, keep it behind a VPN or an IP allow-list if you can: it serves health data.
Plain `http://` is accepted only for LAN or tailnet IP addresses, and the app warns you about anything else.
</details>

## 5. Install the app and connect

1. Download the latest `baseline-*.apk` from [Releases](https://github.com/SelinJodhani/baseline/releases) and install it. Android will ask you to allow installs from your browser.
2. On the server, print a pairing link:
   ```bash
   docker exec baseline-bridge python3 /usr/local/bin/baseline_bridge.py pair https://<machine>.<tailnet>.ts.net:8444
   ```
3. Get the link to your phone however you like: Taildrop, a note to yourself, or a message to yourself. Tap it, and Baseline opens with the address and token filled in. Tap **Connect**.

You can also type the URL and the token (from `.env`) into the connect screen by hand.

No server yet? Tap **Explore with demo data** to look around with a fictional person's data.

## 6. Fresh data: how syncing works

Your data passes through three hops: **watch → Zepp phone app → Zepp cloud → ZeppBridge**. Baseline can only show what has reached the Zepp cloud.

- **The Zepp app on your phone must sync the watch.** It does this in the background, but opening Zepp forces it.
- **ZeppBridge pulls from the cloud on its timer**, hourly if you followed step 2.
- **Sync now:** if your latest watch reading is more than 90 minutes old, Today shows a card with **Open Zepp** and **Sync now**.
  - **Sync now** asks the bridge to pull from the Zepp cloud immediately.
  - It only appears if the cloud hasn't been checked in the last 10 minutes; otherwise the card says when it was checked.
  - If you jump to Zepp from the card, Baseline syncs automatically when you come back.
  - Pull-to-refresh does the same thing when the last check is more than 10 minutes old.
- If a sync finds nothing new, the card tells you so. That almost always means the watch hasn't synced to the Zepp app yet.

## 7. Updates

Baseline updates itself from this repo's GitHub Releases:
- It checks at most every 12 hours. When there's a new version, Today shows a small card with **Update**.
- The APK's SHA-256 is verified against the release's checksum file before Android's installer opens.
- Android only accepts it if it's signed with the same key as the installed app.
- You can also check by hand under **Settings → Updates**.

To update the bridge, pull the repo and rebuild: `cd baseline && git pull && cd bridge && docker compose up -d --build`.

## 8. Make it yours

- **Settings → Your targets:** sleep need, daily steps, weekly active minutes.
- **Settings → Widgets:** add Today, Readiness, Sleep or Move to your home screen with one tap. On Android 16+ devices that support lock-screen widgets, long-press the lock screen and add them there too.
- **Settings → AI brief (optional):** paste a [Gemini API key](https://aistudio.google.com/apikey) to get a three-sentence brief each morning. Only a short text digest of the numbers on Today is sent, never raw readings.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Can't find that host — is Tailscale connected?" | Turn on Tailscale on the phone. Check that the URL matches `tailscale serve status`. |
| "The bridge rejected the token" | Copy the token from the server's `.env` again, or generate a new pairing link. |
| "The bridge couldn't read ZeppBridge" (503) | Run `docker logs baseline-bridge`. Usually the data folder is wrong, or the container's `user:` doesn't own it. |
| Readiness says "Calibrating" | It needs 14 nights with HRV. Wear the watch to bed, or run the history backfill from step 2. |
| Numbers are a day off | Set `TZ` on both the ZeppBridge sync container and the bridge. |
| Today's data seems stale | Open the Zepp app so the watch syncs, then tap **Sync now** on Today, or pull down to refresh. |
| "Cloud sync is turned off on this bridge" | Remove `BASELINE_ALLOW_SYNC=0` from the bridge's environment. |
| "This bridge is too old for cloud sync" | Update the bridge (step 7). |

## Privacy

- Your data flows Zepp cloud → your server → your phone. Baseline has no analytics, accounts or telemetry.
- On the phone, the token and settings are encrypted with an Android Keystore key, and everything is excluded from cloud backup.
- The AI brief is off by default. When it's on, the only thing that leaves your devices is the digest described above, sent to Google with your own key.

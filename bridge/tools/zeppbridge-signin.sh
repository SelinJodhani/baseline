#!/bin/zsh
# Sign in to ZeppBridge for one Zepp account and install the login on the server.
#
#   bridge/tools/zeppbridge-signin.sh current   # the account your watch syncs to now  -> .../zeppbridge/data
#   bridge/tools/zeppbridge-signin.sh old       # an older account, kept for history   -> .../zeppbridge/data-old
#
# ZeppBridge only signs in through its desktop app. This opens the app against a throwaway folder on this Mac
# (file credential store, so the token isn't stuck in the Keychain), waits while you sign in, then copies only
# the small login files to the server and deletes them here. Your server's data and database are never touched.
#
# In the app, connect BOTH: "Zepp account" (the official sign-in) and "Advanced data" (email + password; needed
# for HRV, stress and SpO2 — it doesn't accept Google sign-in). Then quit the app.
# Never press "Disconnect" in the app: that revokes the login at Zepp for every copy, including the server's.
#
# Settings (env): ZB_HOST (default selin.vps.in), ZB_ROOT (default /opt/appdata/zeppbridge),
#                 ZB_TARBALL (default ~/Downloads/ZeppBridge-3.1.1-macOS-AppleSilicon.app.tar.gz)
set -euo pipefail

which=${1:-}
case $which in
  current) lib=data ;;
  old) lib=data-old ;;
  *) print -u2 "usage: $0 current|old"; exit 2 ;;
esac

host=${ZB_HOST:-selin.vps.in}
root=${ZB_ROOT:-/opt/appdata/zeppbridge}
tarball=${ZB_TARBALL:-$HOME/Downloads/ZeppBridge-3.1.1-macOS-AppleSilicon.app.tar.gz}
app=$HOME/Applications/ZeppBridge.app
work=$HOME/.zeppbridge-signin/$lib
files=(auth.json auth.user-id credentials.json devices.json official.json)

# 1. The app (unpacked once into ~/Applications).
if [[ ! -x $app/Contents/MacOS/zeppbridge ]]; then
  [[ -f $tarball ]] || { print -u2 "ZeppBridge app not found. Download the macOS build and set ZB_TARBALL."; exit 1; }
  mkdir -p $HOME/Applications
  tar -xzf $tarball -C $HOME/Applications
  print "Unpacked ZeppBridge into $app."
  print "It's ad-hoc signed, so macOS needs you to allow it once. Run these yourself, then run this script again:"
  print "  xattr -dr com.apple.quarantine \"$app\""
  print "  codesign --force --deep -s - \"$app\""
  exit 0
fi

# 2. Sign in against a throwaway folder.
rm -rf $work && mkdir -p $work && chmod 700 $work
print "Opening ZeppBridge. Connect \"Zepp account\" and \"Advanced data\" for the $which account."
ZEPPBRIDGE_CREDENTIAL_STORE=file ZEPPBRIDGE_DATA_DIR=$work $app/Contents/MacOS/zeppbridge >/dev/null 2>&1 &
pid=$!
# The app can stay alive in the menu bar after its window closes, so don't wait for it to exit: ask instead.
read -r "?When both connections show as connected, press Enter here… "
kill $pid 2>/dev/null || true
for _ in {1..20}; do kill -0 $pid 2>/dev/null || break; sleep 0.5; done
kill -9 $pid 2>/dev/null || true

for f in auth.json credentials.json; do
  [[ -s $work/$f ]] || { print -u2 "No $f in $work — did the sign-in finish? Nothing was copied."; rm -rf $work; exit 1; }
done
if ! grep -q . $work/official.json 2>/dev/null; then print "Note: no official.json — the \"Zepp account\" connection may be missing."; fi

# 3. Install the login files on the server (owner-only), replacing that library's previous login.
present=(); for f in $files; do [[ -f $work/$f ]] && present+=$work/$f; done
tmp=$(ssh $host 'umask 077; mktemp -d')
scp -q $present $host:$tmp/
ssh $host "set -e; umask 077; mkdir -p $root/$lib; chmod 700 $root/$lib; for f in $tmp/*; do install -m 600 \$f $root/$lib/; done; rm -rf $tmp"
rm -rf $work
print "Installed the $which account's login in $host:$root/$lib and deleted the local copy."
if [[ $which == old ]]; then
  print "Next: ask Claude to run the full history sync for the old account and turn on the merge in Baseline Bridge."
fi

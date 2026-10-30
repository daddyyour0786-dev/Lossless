# Releasing Lossless

Written for **Git Bash on Windows**, which is what you said you push with — the
commands are the same in any POSIX shell.

The version is three dotted numbers that only go up — **0.1.0, 0.1.1, 0.1.2, …** because
every install compares its own `versionName` against the newest release tag and offers
an update when the tag is higher. Two numbers have to stay in step:

| What | Where | Example |
|:--|:--|:--|
| `versionName` | `build.gradle.kts` → `extra["losslessVersionName"]` | `0.1.1` |
| `versionCode` | `build.gradle.kts` → `extra["losslessVersionCode"]` | `2` |
| Tag | git | `v0.1.1` |
| APK name | produced by CI | `Lossless-0.1.1.apk` |

`tools/bump-version.sh` writes the first two together. Never edit them by hand.

---

## One-time setup

### 1. Create the repository and push

```bash
cd /path/to/Lossless
git init
git add .
git commit -m "Lossless 0.1.0"
git branch -M main
git remote add origin https://github.com/daddyyour0786-dev/Lossless.git
git push -u origin main
```

### 2. Make a signing key

An update can only install over an old build if both are signed with the **same
key**, so this key is permanent: lose it and every installer has to uninstall and
lose their library.

```bash
keytool -genkeypair -v \
  -keystore lossless-release.jks \
  -alias lossless \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass 'YOUR_STORE_PASSWORD' \
  -keypass 'YOUR_KEY_PASSWORD' \
  -dname "CN=Lossless, O=Lossless, C=FI"
```

Keep `lossless-release.jks` somewhere safe and **out of the repository** — the
`.gitignore` already excludes it and `keystore.properties`.

### 3. Add the four repository secrets

GitHub → your repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|:--|:--|
| `KEYSTORE_BASE64` | the whole `.jks` file, base64-encoded (below) |
| `KEYSTORE_PASSWORD` | the store password you just set |
| `KEY_ALIAS` | `lossless` |
| `KEY_PASSWORD` | the key password you just set |

The base64 blob, from Git Bash:

```bash
base64 -w0 lossless-release.jks > keystore.b64
cat keystore.b64      # copy all of it into KEYSTORE_BASE64
```

On Git Bash without `-w`, use `base64 lossless-release.jks | tr -d '\n' > keystore.b64`.

Optional secrets, if you use these features:

| Secret | What it enables |
|:--|:--|
| `LASTFM_API_KEY` / `LASTFM_SECRET` | Last.fm scrobbling |
| `LISTEN_TOGETHER_SERVER` | the default party-server address baked into the build |

### 4. Serve the invite landing page

Listen Together invites point at `https://daddyyour0786-dev.github.io/invite/<CODE>`.
That page lives in **your site repository** (`daddyyour0786-dev.github.io`), not this
one: copy [`docs/site/index.html`](docs/site/index.html) there as `invite/index.html`
(or as a catch-all `404.html`), and copy
[`docs/site/assetlinks.json`](docs/site/assetlinks.json) to
`.well-known/assetlinks.json` after filling in your signing certificate's SHA-256:

```bash
keytool -list -v -keystore lossless-release.jks -alias lossless | grep SHA256
```

With that file in place, a tapped invite opens Lossless directly instead of a browser.
Without it the link still works — it just opens the page, which offers the download.

---

## Cutting a release

```bash
# 1. Move the version
tools/bump-version.sh patch      # 0.1.0 -> 0.1.1   (or: minor, major)
tools/bump-version.sh --print    # sanity check

# 2. Commit and tag
git add -A
git commit -m "Release 0.1.1"
git tag v0.1.1

# 3. Push — the tag is what starts the build
git push && git push --tags
```

Then watch **Actions → Release**. When it finishes, the release is published with:

- `Lossless-0.1.1.apk` — universal
- `Lossless-0.1.1_arm64-v8a.apk`, `_armeabi-v7a`, `_x86_64` — per-ABI splits

Every installed copy of Lossless sees the new tag on its next launch and offers the
update in-app. Nothing else to do.

### If the build fails

- **"The tag says 0.1.1 but build.gradle.kts says 0.1.0"** — you tagged a commit that
  did not include the bump. `git tag -d v0.1.1`, bump, commit, tag again.
- **"KEYSTORE_BASE64 is not set"** — step 3 above is incomplete.
- **No APKs produced** — the Gradle step above it in the log will say why.

### Re-running a release

Actions → Release → **Run workflow** → enter the existing tag (e.g. `v0.1.1`). It
rebuilds and re-publishes that version without touching the version numbers.

---

## Building without CI

```bash
./gradlew assembleProdDebug          # debug APK, installable as-is
./gradlew assembleProdRelease        # needs keystore.properties next to build.gradle.kts
```

`keystore.properties` — copy `keystore.properties.example`:

```properties
storeFile=lossless-release.jks
storePassword=…
keyAlias=lossless
keyPassword=…
```

A release build with no key is not signed with the debug key on purpose: that
produces an APK that installs once and then blocks every future update with a
signature mismatch.

---

## Regenerating the icons

All of them come from one file:

```bash
python3 tools/generate_icons.py      # needs Pillow; writes drawables, mipmaps, Logo.*, Banner.png
```

Edit `tools/lossless_mark.py` (the ring, the sine, the stroke weight), not the
generated files — the generator overwrites them.

# GitHub publishing for GovMap

This build is configured for the public repository **davidreuveni/GovMap**. The repository is public. The initial release is published through the prebuilt APK workflow; no signing secrets are required for that route. Existing users need to install this updater-enabled version once, preserving app data, then enable updates and allow GovMap to install unknown apps. Phone restrictions can still require approval or block installation.

## Manual release, without putting a signing key on GitHub

1. Use public repository `davidreuveni/GovMap`. Phones download without GitHub accounts/tokens. Existing published versions must not be overwritten.
2. Extract `GovMap-GitHub-release.zip`. Create release tag **v1.10.0**. Attach **GovMap-Dark.apk** and **update.json** from that same ZIP. Copy the release notes if wanted.
3. Publish as the latest stable release. Do not mark it as a prerelease. Upload both assets before publishing; don't replace the assets of a published version.
4. Install the APK over existing GovMap, enable updates and allow Android's installation permission. Long-press the GovMap title for check/install/disable controls.

The metadata points to a version-specific APK URL, not a moving latest-APK URL. This prevents metadata and APK from different releases from being mixed during publication. The feed URL is `https://github.com/davidreuveni/GovMap/releases/latest/download/update.json`.

You can publish using the GitHub CLI from a local build too:

```bash
gh auth login
GOVMAP_SDK=/absolute/android-sdk python3 scripts/release.py publish --apk ../GovMap-Dark.apk
```

The script verifies the APK's cryptographic signature and original certificate, reads package/version/minimum Android version from the actual APK, computes its size/hash, refuses non-public repositories or non-increasing published version codes, and uploads both assets before publishing. The private signing files never go through this script. A failed upload can leave a draft; inspect/remove that draft before retrying the same tag. Never overwrite a published release.

## Publish a signed APK automatically

Replace `release-input/GovMap-Dark.apk` on `main` with a new build and commit the matching source/manifest update. **Publish signed APK** verifies the original certificate, package and manifest version, then generates update.json and publishes both assets. This workflow needs no signing key on GitHub and no added secrets. Never reuse a published version name/code.

## Optional: build and publish with one Actions button

The separate `GovMap-GitHub-source.zip` is suitable for the public code repository. Extract its CONTENTS at the repository root, including `.github/`. It contains no private signing files. Do not upload `GovMap-Dark-source.zip`: that is your private source/signing backup.

If you choose to have GitHub build releases, configure these repository Actions secrets first:

- `GOVMAP_KEYSTORE_BASE64`: base64 of the original `signing/release.p12`.
- `GOVMAP_KEYSTORE_PASSWORD`: contents of the original `signing/password.txt`.

Use repository **Settings → Secrets and variables → Actions**, or from the private source folder on your own computer:

```bash
base64 -w0 signing/release.p12 | gh secret set GOVMAP_KEYSTORE_BASE64 --repo davidreuveni/GovMap
gh secret set GOVMAP_KEYSTORE_PASSWORD --repo davidreuveni/GovMap < signing/password.txt
```

These commands send the signing material to GitHub Actions secrets, not to public source. Keep the original local backup. No replacement key is generated. The workflow restores private signing files only on its runner, verifies the original certificate, and removes the files afterward. Only the APK and update.json are published. Anyone allowed to edit/run release code must be trusted with the signing capability.

Then use **Actions → Build and publish (signing secrets required) → Run workflow**. For a future release, use a new version name (for example `1.10.1`) and higher version code (for example `14`). The workflow builds, runs checks and publishes a latest release. This build workflow requires signing-secret setup; the prebuilt publishing workflow does not.

## What users experience

- First automatic setup is optional. Long-pressing the title can enable it later.
- Launch checks wait five seconds and never block the map. Reopening within 15 minutes reuses the check interval, rather than hitting GitHub repeatedly.
- Background checks are requested every six hours; Android, battery settings, force-stop and filtering can delay/prevent them. No delivery deadline is guaranteed.
- Downloads stay in private application data. A killed/interrupted transfer is not reused. Size, hash, package, version and signing certificate are verified before staging and again before installation.
- Automatic installation is attempted when GovMap is closed. Opening the map before commit defers automatic installation. An explicit manual install can close the app.
- Android 12+ supports silent self-updates under its current API/permission requirements. Older versions, restricted devices, or unmet conditions can require Android's installer confirmation. The app retains that confirmation and offers it on the next opening, without launching a background activity.
- Turning updates off cancels the job and stops the active transfer. The map, theme, location and persistent map-asset store remain available.
- A bad/missing release, invalid APK or offline phone leaves the installed app intact. Retrying a failed installation is backed off six hours; manual controls can retry sooner.

## Validation still needed on a phone

Install 1.10.0, grant/decline the setup and unknown-app permission, publish a higher signed version, then check foreground download/background installation, Android confirmation fallback, process restart, reboot and existing map data. Also try offline launch and updates disabled. No Android device/emulator is connected to this workspace, so these installation/job checks have not been physically executed.

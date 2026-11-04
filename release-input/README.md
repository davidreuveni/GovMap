# Publish a signed APK

`GovMap-Dark.apk` in this folder is a public, already-signed build. No private signing material belongs here.

Replacing it on `main` triggers **Publish signed APK**. The workflow verifies the original signer, checks the APK package/version against the source manifest, rejects tampering, and publishes a stable GitHub release containing the APK and generated update.json. Always increase the version name and version code and update source alongside a new build. Published assets are never overwritten.

For a build on GitHub, use the separate **Build and publish (signing secrets required)** workflow after configuring its two signing secrets. The prebuilt publishing workflow needs no signing key or additional secrets.

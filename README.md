# wlr-remote-control

An Android client for [wlr-remote](https://github.com/mxpph/wlr-remote). Remote control your mouse from another device through an encrypted connection with low latency.

## Build instructions

You will need the Android SDK to build the project.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## Usage

First, you will need to setup a wlr-remote server. You can find instructions for that in the [wlr-remote repository](https://github.com/mxpph/wlr-remote).

Then, build and install the generated APK on your android device and connect to your wlr-remote server from the in-app dialog.

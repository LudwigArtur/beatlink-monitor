# Quick start

Read [Use at your own risk](../DISCLAIMER.md) first. The code is
[AI-generated](../AI_DISCLOSURE.md); hardware compatibility is not guaranteed.

## Requirements

- Git and a complete **JDK 21** (not just a JRE).
- Node.js 20.19+ on the 20.x line or 22.12+; Node 24 is recommended.
- npm (included with Node).
- Internet access for the first build; Gradle downloads its distribution and
  dependencies.

Clone the repository and enter its root directory. Check:

```sh
java -version
javac -version
node --version
npm --version
```

If the wrong Java version is active, set `JAVA_HOME` to your JDK.
For example, on macOS with Homebrew:

```sh
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

## Start without DJ hardware

```sh
cd backend
./gradlew fatJarHeadless
java -jar build/libs/beatlink-monitor-backend-0.1.0-headless.jar
```

On Windows, use `gradlew.bat` instead of `./gradlew`.
Then open `http://127.0.0.1:17812/` in a browser.
The web interface is included in the JAR; no separate frontend server is needed.

1. Click **Start Simulator** in **Simulator**; alternatively enable **Auto-Start Simulator**
   in **Settings**, save and restart.
2. Connect virtual players in **Simulator**, load a track, press Play and
   select a master.
3. Check the display in **Monitor** and **Live View**.
4. Leave outputs disabled initially. Configure and enable them deliberately
   only after testing.

## macOS desktop app

```sh
cd backend
./gradlew jpackageMac
```

Use the printed path to `BeatLinkMonitor.app`. This build targets the local
system; it does not produce automatically notarized installers.
Do not blindly bypass Gatekeeper warnings: verify provenance and the build first.

At startup, the app opens a small native control window followed by a separate
tab in the default browser for the frontend. **Open in Browser** reopens the UI.
Closing only the tab does not stop the server; use **Quit** or close the control
window instead.

## Raspberry Pi and development

- [Raspberry Pi kiosk](../raspberrypi-package/README.md)
- [Development and releases](DEVELOPMENT.md)
- [User guide](USER_GUIDE.md)
- [Troubleshooting](TROUBLESHOOTING.md)

# Getting the APK

## Option A: Android Studio (about 10 minutes)
1. Install Android Studio (free) and open this folder (the one containing settings.gradle.kts).
2. Let Gradle sync finish (first time downloads a lot).
3. Menu: Build > Build Bundle(s) / APK(s) > Build APK(s).
4. Click "locate" in the popup. The file is app/build/outputs/apk/debug/app-debug.apk.
5. Copy it to your phone, open it, and allow "install unknown apps" when asked.

## Option B: GitHub builds it for you (no installs)
1. Create a free GitHub repo and upload everything in this folder, including the hidden .github folder.
2. Open the Actions tab > "Build APK" > wait for the green tick.
3. Download the "brick-debug-apk" artifact (a zip containing app-debug.apk).

## After installing
- Allow location "all the time" when prompted.
- Settings > Accessibility > Brick blocker > turn on.
- Debug APKs are signed with a debug key, which is fine for your own phone.

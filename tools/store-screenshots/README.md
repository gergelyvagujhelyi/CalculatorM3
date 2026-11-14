# Store screenshots

`metadata/en-US/images/phoneScreenshots/*.jpg` (shown by F-Droid and the README) are made from the raw screen captures in `raw/`. Each capture goes into a phone frame on a dark background, under a headline.

To change a headline, edit `SHOTS` in `render.py` and run:

```
python3 tools/store-screenshots/render.py        # all of them
python3 tools/store-screenshots/render.py 3 7    # just these
```

This needs Python with Pillow, and Google Chrome or Chromium, which renders the HTML/CSS layout.

## Taking new captures

The captures are 1440×2960 (a Galaxy Note 9), in the app's light theme, with a clean status bar from Android's demo mode. Take them with the `.devicetest` build (`./gradlew -PdeviceTest :app:assembleDebug`), never the installed app:

```
adb shell cmd uimode night no
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile hide
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
adb exec-out screencap -p > tools/store-screenshots/raw/<n>.png
```

Afterwards, exit demo mode (`-e command exit`) and set the theme back. The answers in the AI shots are real output from the on-device model; check the maths before using one.

| n | Screen |
|---|---|
| 1 | Calculator with a live result |
| 2 | Scientific functions |
| 3 | Calculation history |
| 4 | Landscape, with the history beside the keys |
| 5 | The Solve with AI menu |
| 6 | The handwriting pad |
| 7 | A step-by-step answer |
| 8 | A word problem with a follow-up question |
| 9 | AI history |
| 10 | An answer translated into Spanish, with a follow-up |
| 11 | AI settings |

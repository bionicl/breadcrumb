# Review UI on an emulator

The Review screen loads a single question and native map at a time. Transport questions show
only their route. Place and recording-gap questions share one map with arrival/departure
routes and a measured-location pin; the camera opens around that pin instead of fitting both
complete trips. Dashed amber lines indicate missing measurements. The three-event timeline
and decisions remain outside the map viewport. The title dropdown switches Pending/Later/Resolved.
Queue details and the force-processing action are beside Settings in the top bar. Rationale is
under the information icon beside the card heading, with a relative date on the right.
Swipe the card heading or timeline to browse; map gestures only pan the map. Route colors
use the shared transport palette; there is no separate map legend.

Use synthetic data on an **emulator**, leaving the physical phone available:

```sh
emulator -avd Breadcrumb_PixelFold -no-snapshot-save
./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n io.github.valeronm.breadcrumb.debug/io.github.valeronm.breadcrumb.ui.MainActivity
python3 tools/seed_review_demo.py --serial emulator-5554
adb -s emulator-5554 shell am start -n io.github.valeronm.breadcrumb.debug/io.github.valeronm.breadcrumb.ui.MainActivity
```

The seeder refuses physical-device serials and verifies `ro.kernel.qemu=1`. It stops only the
emulator's debug app, saves a SQLite backup on the host, and replaces only its synthetic
fixtures. Existing emulator history remains. It never adds a write command to the debug bridge.
The four scenarios use 18 October 2026 by default; pass `--day YYYY-MM-DD` for another date.

Check these flows in Review:

- Misclassified transport: one route, its recorded gap, neighboring place context; choose
  another transport type from the dialog.
- Six-minute missing recording: centered measured pin, two contextual routes and a dashed gap;
  inspect rationale, postpone, restore, save as part of a trip, and undo the merge in Resolved.
- Unnamed fifteen-minute stop after a long drive: the camera remains around the stop;
  create a place with the name dialog and undo its creation in Resolved.
- Stop near an existing named place: show the measured stop separately from the proposed
  place; assign to that place or create a new one.
- Check Later/Resolved navigation, queue details, dark mode and larger text. No decision is
  made by swiping the map or moving between cards.

Read diagnostic state without changing it:

```sh
python3 tools/breadcrumb_debug.py --serial emulator-5554 suggestions
python3 tools/breadcrumb_debug.py --serial emulator-5554 status
```

Each command prints an `output_file` containing the response JSON. Geometry segmentation is
covered by `ReviewGeometryTest`; the app's debug unit-test suite and `assembleDebug` verify
the rest of the existing decision layer and compilation.

# Research: capabilities and verification (questions 15 to 17 and 47 to 50)

All line numbers are against `e976063` (`git rev-parse HEAD` = `e976063990db1365e793e01df965bcb30a3ccb39`).

## 15. Resolved library versions

Method used, in this order:

1. `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline :app:dependencies --configuration debugRuntimeClasspath`
   succeeded offline: `BUILD SUCCESSFUL in 16s`, `1 actionable task: 1 executed`.
2. Read the BOM POM from the Gradle cache:
   `/root/.gradle/caches/modules-2/files-2.1/androidx.compose/compose-bom/2024.10.01/dcdf970bb52556a27d86a5ef78b06b2cce7a520d/compose-bom-2024.10.01.pom`.
3. Listed `/root/.gradle/caches/modules-2/files-2.1/` for the resolved artifact directories.

Declared versions.

- `build.gradle.kts:2` `id("com.android.application") version "8.7.3" apply false`
- `build.gradle.kts:3` `id("org.jetbrains.kotlin.android") version "2.0.21" apply false`
- `build.gradle.kts:4` `id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false`
- `gradle/wrapper/gradle-wrapper.properties:3` `distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-all.zip`
- `app/build.gradle.kts:70` `implementation("androidx.core:core-ktx:1.13.1")`
- `app/build.gradle.kts:71` `implementation("androidx.activity:activity-compose:1.9.3")`
- `app/build.gradle.kts:72` `implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")`
- `app/build.gradle.kts:73` `implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")`
- `app/build.gradle.kts:74` `implementation(platform("androidx.compose:compose-bom:2024.10.01"))`
- `app/build.gradle.kts:75` `implementation("androidx.compose.ui:ui")`
- `app/build.gradle.kts:76` `implementation("androidx.compose.material3:material3")`

`settings.gradle.kts` declares only repositories (`google()`, `mavenCentral()`); it pins no version.

Resolved versions. BOM POM pins: `material3` 1.3.1, `ui` 1.7.5, `foundation` 1.7.5, `runtime` 1.7.5, `runtime-saveable` 1.7.5. The dependency report resolved:

- `androidx.compose.ui:ui:1.7.5` (also `ui-android`, `ui-text`, `ui-graphics`, `ui-unit`, `ui-util` all 1.7.5)
- `androidx.compose.foundation:foundation:1.7.5` (and `foundation-layout:1.7.5`)
- `androidx.compose.material3:material3:1.3.1` (`material3-android:1.3.1`)
- `androidx.compose.runtime:runtime:1.7.5`, `runtime-saveable:1.7.5`
- `androidx.activity:activity-compose:1.9.3`, `androidx.activity:activity:1.9.3`
- `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`, `lifecycle-viewmodel-compose:2.8.7`
- `androidx.core:core-ktx:1.13.1`
- Compose compiler: the Kotlin 2.0.21 Compose compiler Gradle plugin (`build.gradle.kts:4`); no separate `kotlinCompilerExtensionVersion` is declared.

The Compose BOM line in the report reads `androidx.compose:compose-bom:2024.10.01` with constraints `androidx.compose.material3:material3:1.3.1 (c)` and `androidx.compose.ui:ui:1.7.5 (c)`.

## 16. API presence in those versions

Every check below was done against the resolved AAR in the Gradle cache, either by `unzip -l` on the extracted `classes.jar` or by `javap -p` and `javap -v` on the class. Artifact paths abbreviated under `/root/.gradle/caches/modules-2/files-2.1/`.

| API | Present? | Artifact and version | Evidence |
| --- | --- | --- | --- |
| `SingleChoiceSegmentedButtonRow` | present | `androidx.compose.material3:material3-android:1.3.1` | `androidx/compose/material3/SegmentedButtonKt.class` method `public static final void SingleChoiceSegmentedButtonRow-uFdPcIQ(...)`. `SingleChoiceSegmentedButtonRowScope` exists as `androidx/compose/material3/SingleChoiceSegmentedButtonRowScope.class`. |
| `SegmentedButton` | present | material3 1.3.1 | `SegmentedButtonKt.class` has `public static final void SegmentedButton(SingleChoiceSegmentedButtonRowScope, boolean, Function0, Shape, Modifier, boolean, SegmentedButtonColors, BorderStroke, MutableInteractionSource, Function2, Function2, ...)`. `SegmentedButtonDefaults.class` exists (with `itemShape`, `ActiveIcon`, `colors`, `borderStroke`). |
| `Modifier.semantics { selected = … }` | present | `androidx.compose.ui:ui-android:1.7.5` | `androidx/compose/ui/semantics/SemanticsPropertiesKt.class`: `public static final void setSelected(SemanticsPropertyReceiver, boolean)` and `getSelected(...)Z`. |
| `stateDescription` | present | ui 1.7.5 | `SemanticsPropertiesKt.class`: `public static final void setStateDescription(SemanticsPropertyReceiver, java.lang.String)` and `getStateDescription(...)String`. |
| `LiveRegionMode` | present | ui 1.7.5 | `androidx/compose/ui/semantics/LiveRegionMode.class` (value class, `Companion` with `getPolite-0phEisY()` and `getAssertive-0phEisY()`). |
| `Modifier.semantics { liveRegion = … }` | present | ui 1.7.5 | `SemanticsPropertiesKt.class`: `public static final void setLiveRegion-hR3wRGc(SemanticsPropertyReceiver, int)` and `getLiveRegion(...)I`. |
| `Role.Button` | present | ui 1.7.5 | `androidx/compose/ui/semantics/Role.class` has `private static final int Button`; `Role$Companion.class` exposes `public final int getButton-o7Vup1c()`. Other values in the same class: `Checkbox`, `Switch`, `RadioButton`, `Tab`, `Image`, `DropdownList`. |
| `Modifier.heading()` | present | ui 1.7.5 | `SemanticsPropertiesKt.class`: `public static final void heading(SemanticsPropertyReceiver)` (receiver extension used inside `Modifier.semantics { }`; there is no separate `Modifier.heading()`). |
| `enableEdgeToEdge` | present | `androidx.activity:activity:1.9.3` | `androidx/activity/EdgeToEdge.class`, compiled from `EdgeToEdge.kt`, exposes `public static final void enable(ComponentActivity)` / `enable(ComponentActivity, SystemBarStyle)`; the Kotlin name is `enableEdgeToEdge`, confirmed in the `@Metadata` `d2` array (`...,"enableEdgeToEdge","Landroidx/activity/ComponentActivity;",...`) and in `LocalVariableTable` (`$this$enableEdgeToEdge`). The JVM methods are renamed by `kotlin.jvm.JvmName("enable")`. |
| `WindowInsets.safeDrawing` | present | `androidx.compose.foundation:foundation-layout-android:1.7.5` | `androidx/compose/foundation/layout/WindowInsets_androidKt.class`: `public static final WindowInsets getSafeDrawing(WindowInsets$Companion, Composer, int)`. |
| `Modifier.imePadding` | present | foundation-layout 1.7.5 | `androidx/compose/foundation/layout/WindowInsetsPadding_androidKt.class`: `public static final Modifier imePadding(Modifier)`. |
| `MotionDurationScale` | present | ui 1.7.5 | `androidx/compose/ui/MotionDurationScale.class` (interface extending `kotlin.coroutines.CoroutineContext$Element`, with `getScaleFactor()` and a nested `Key`). Also `MotionDurationScaleImpl.class` and `MotionDurationScale$DefaultImpls.class`. |
| `LocalMotionDurationScale` | absent | ui 1.7.5 | No class matching `*LocalMotion*` in `ui-release.aar`; `strings` over the extracted `classes.jar` returns 0 occurrences of the literal `LocalMotionDurationScale`; `androidx/compose/ui/platform/CompositionLocalsKt.class` has no `LocalMotionDurationScale` property. |
| `rememberSaveableStateHolder` | present | `androidx.compose.runtime:runtime-saveable-android:1.7.5` | `androidx/compose/runtime/saveable/SaveableStateHolderKt.class`: `public static final SaveableStateHolder rememberSaveableStateHolder(Composer, int)`. |

## 17. Which of those APIs are experimental

None of the APIs in question 16 carries an opt-in marker in the resolved versions. Method: `javap -v` plus `strings` over the defining class, checking for `ExperimentalComposeUiApi`, `ExperimentalMaterial3Api`, `ExperimentalMaterial3ExpressiveApi`, `ExperimentalFoundationApi`, `ExperimentalLayoutApi`.

- material3 1.3.1 `SegmentedButtonKt.class`: `strings ... | grep -i experimental` returns nothing; the only class-level `RuntimeVisibleAnnotations` is `kotlin.Metadata`. `SingleChoiceSegmentedButtonRow`, `MultiChoiceSegmentedButtonRow` and both `SegmentedButton` overloads need no `@OptIn`.
- ui 1.7.5 `SemanticsPropertiesKt.class`: the file contains exactly one experimental marker, `.Landroidx/compose/ui/ExperimentalComposeUiApi;`, attached to `invisibleToUser`. `heading`, `getSelected`/`setSelected`, `getStateDescription`/`setStateDescription`, `getLiveRegion`/`setLiveRegion` carry no experimental annotation.
- ui 1.7.5 `MotionDurationScale.class`: no experimental marker.
- activity 1.9.3 `EdgeToEdge.class`: no experimental marker.
- foundation-layout 1.7.5 `WindowInsets_androidKt.class`: contains `:Landroidx/compose/foundation/layout/ExperimentalLayoutApi;`, but it is attached to `getCaptionBarIgnoringVisibility` and the other `*IgnoringVisibility` / `is*Visible` members, not to `getSafeDrawing`. The `getSafeDrawing` block lists annotations `Composable`, `JvmName(name="getSafeDrawing")`, `NotNull` only.
- foundation-layout 1.7.5 `WindowInsetsPadding_androidKt.class`: no experimental marker (`imePadding` free).
- runtime-saveable 1.7.5 `SaveableStateHolderKt.class`: no experimental marker.

Opt-ins the code already carries. `grep` over `app/src/main` for `@OptIn|@Experimental|Experimental[A-Za-z]+Api` finds three sites, all in `app/src/main/java/com/maverock24/pimobile/ui/Screens.kt`:

- `Screens.kt:240` `@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)` on `ChatScreen` (`:241`).
- `Screens.kt:592` `@OptIn(ExperimentalFoundationApi::class)` on `Transcript` (`:593`).
- `Screens.kt:1813` `@OptIn(ExperimentalMaterial3Api::class)` on `SettingsScreen` (`:1814`).

The import for the foundation marker is `Screens.kt:11` `import androidx.compose.foundation.ExperimentalFoundationApi`. No other file in `app/src/main` carries an opt-in.

## 47. Test sources, lint configuration, CI jobs

Test source sets. `find app/src -type d` returns only `app/src/main` and its subdirectories (`main/java/com/maverock24/pimobile/{data,net,ui,update,voice}`, `main/res/{values,xml}`). There is no `app/src/test` and no `app/src/androidTest`. `find app/src -path '*test*' -name '*.kt'` returns nothing. The `dependencies { }` block (`app/build.gradle.kts:69-82`) contains `implementation` entries only; no `testImplementation` or `androidTestImplementation`.

Lint configuration. No `lint.xml` anywhere in the working tree outside `app/build/`. No `lintOptions` or `lint { }` block in `app/build.gradle.kts` or root `build.gradle.kts`; `grep -n "lint"` over both build files and `gradle.properties` returns nothing. No detekt, ktlint or `.editorconfig` configuration. Lint therefore runs with AGP 8.7.3 defaults; `:app:lintDebug` writes `app/build/reports/lint-results-debug.{html,txt,xml}`.

CI workflows. `.github/workflows/` holds two files.

- `.github/workflows/android-debug.yml`: trigger `on: workflow_dispatch:` only (manual). One job `debug-apk` on `ubuntu-latest`, steps: checkout, `actions/setup-java` with `java-version: '21'`, `android-actions/setup-android` with `packages: 'platform-tools'`, `./gradlew --no-daemon --stacktrace assembleDebug`, upload of `app/build/outputs/apk/debug/app-debug.apk`.
- `.github/workflows/android-release.yml`: triggers `on: push: branches: [main]` and `on: workflow_dispatch:`. One job `build-and-publish` on `ubuntu-latest`, Java 21, `./gradlew --no-daemon --stacktrace assembleRelease`, writes `latest.json`, deletes the previous `latest-build` release, publishes with `softprops/action-gh-release`, uploads `pi-remote-*.apk`.

Neither workflow runs a test task or a lint task.

## 48. Gradle tasks and which run offline on this machine

All runs used `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --offline ...` from the repo root. The default `java` on this machine is Java 25 (`openjdk version "25.0.4.1" 2026-08-18`). Every task below exists and printed `BUILD SUCCESSFUL`.

| Task | Result offline |
| --- | --- |
| `:app:dependencies --configuration debugRuntimeClasspath` | `BUILD SUCCESSFUL in 16s`, full tree printed |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL in 9s`; `> Task :app:testDebugUnitTest NO-SOURCE` (no test source set) |
| `test` (root) | `BUILD SUCCESSFUL in 10s`; `> Task :app:testReleaseUnitTest NO-SOURCE`, `> Task :app:test UP-TO-DATE` |
| `:app:lintDebug` | `BUILD SUCCESSFUL in 1m 22s`; report written to `app/build/reports/lint-results-debug.html`. Report content: `0 errors, 9 warnings`, issue ids `GradleDependency` (5), `ObsoleteSdkInt` (2), `DataExtractionRules` (1), `MissingApplicationIcon` (1) |
| `lint` (root) | `BUILD SUCCESSFUL in 10s`; `> Task :app:lint UP-TO-DATE` after `:app:lintDebug` |
| `:app:assembleDebug` | `BUILD SUCCESSFUL in 5s`; `app/build/outputs/apk/debug/app-debug.apk` exists (10055478 bytes) |
| `:app:assembleRelease` | `BUILD SUCCESSFUL in 5m 37s`; `app/build/outputs/apk/release/app-release-unsigned.apk` exists (7356755 bytes). No signing env vars were set, so the artifact is unsigned |

## 49. Device-only versus machine-provable verification

What exists on this machine today, as an automated check:

- Static analysis: `:app:lintDebug` / `lint`, offline, `BUILD SUCCESSFUL`, currently `0 errors, 9 warnings` with the ids listed under question 48. None of the nine warnings concerns back handling, touch targets, text wrapping, insets, animation scale, font scale or accessibility roles.
- Compilation and packaging: `:app:assembleDebug` and `:app:assembleRelease` succeed offline.
- Unit tests: the task runs but is `NO-SOURCE`; there is no `app/src/test` and no `testImplementation` dependency.
- Screenshot tests: no screenshot-testing plugin and no `ui-test` dependency (`app/build.gradle.kts:69-82` has `implementation` only); grep for `screenshot`, `robolectric`, `ui-test` over the build files returns nothing.
- Instrumentation tests: no `app/src/androidTest`, and no emulator or system image exists (question 50).

Per recommendation:

- R2 (back gesture): the system back dispatch and predictive-back animation are device/emulator only. On this machine the only checkable part is the static presence of an interception call, which is a source read plus lint. Not provable by a run here.
- R6 (touch target size): a declared `Modifier.height`/`size`/`padding` value is readable from source, and lint can flag undersized Compose touch targets in principle. The current lint run reports no such issue (`TouchTargetSizeCheck` is absent from the nine ids). Confirming a real hit-target or a 48dp floor under `fontScale` needs a device or emulator.
- R8 (wrap): text wrapping is a rendering result; needs a device or emulator.
- R9 (composer layout): rendering and overflow behaviour; needs a device or emulator.
- R13 (notification from the lock screen): needs a device or emulator, and Android 13+ (`POST_NOTIFICATIONS`) for the permission flow.
- R15 (TalkBack, fontScale 2.0): TalkBack needs a device or emulator with accessibility services; `fontScale` 2.0 needs a rendering pass on a device or emulator. Neither can be produced here.
- R19 (Android 15 insets): needs a device or emulator on API 35. Only the compile platform `android-35` is installed (question 50); no API 35 system image is present.
- R20 (system animation scale): the animator duration scale is a Settings value; needs a device or emulator.

Summary: every recommendation in the list above is device- or emulator-only today. The machine can prove only compilation, packaging and the current lint result set.

## 50. Android SDK and emulator availability

`local.properties` contains one line: `sdk.dir=/usr/lib/android-sdk`.

Installed under `/usr/lib/android-sdk/`:

- `platforms/android-35` (with `android.jar`, `core-for-system-modules.jar`, `package.xml`)
- `build-tools/34.0.0` and `build-tools/35.0.0`
- `platform-tools` revision 36.0.0 (`source.properties` `Pkg.Revision=36.0.0`; `adb --version` reports `Android Debug Bridge version 1.0.41`, `Version 36.0.0-13206524`). `adb devices` prints an empty device list.
- `licenses/android-sdk-license`

Absent under `/usr/lib/android-sdk/`: no `emulator/` directory, no `system-images/` directory, no `cmdline-tools/` directory.

An AVD definition exists, but its runtime is missing:

- `/home/maverock24/.android/avd/Small_Phone_API_28.ini` has `target=android-28`, `path=/home/maverock24/.android/avd/Small_Phone_API_28.avd`.
- `/home/maverock24/.android/avd/Small_Phone_API_28.avd/config.ini` has `AvdId=Small_Phone_API_28`, `abi.type=x86`, `hw.cpu.arch=x86`, `image.sysdir` absent, `hw.lcd.width=720`, `hw.lcd.height=1280`. A `snapshots/default_boot` directory exists.
- `/home/maverock24/.android/avd/Small_Phone_API_28.avd/emu-launch-params.txt` records the launcher as `/home/maverock24/Android/Sdk/emulator/emulator` and the working directory `/home/maverock24/github/mobile-media-app`. `/home/maverock24/Android/Sdk/` does not exist on this machine, even though `PATH` contains `/home/maverock24/Android/Sdk/emulator` and `/home/maverock24/Android/Sdk/platform-tools`.
- `which emulator` returns nothing; `find / -maxdepth 5 -name emulator -type f` returns nothing; `find / -maxdepth 7 -name system.img` returns nothing; `find / -maxdepth 7 -type d -name system-images` returns nothing.
- The AVD targets `android-28`, and `platforms/android-28` is not installed (only `android-35`).

An instrumentation test cannot run here: there is no `app/src/androidTest`, no emulator binary, and no system image. Font-scale and TalkBack proofs cannot be automated on this machine.

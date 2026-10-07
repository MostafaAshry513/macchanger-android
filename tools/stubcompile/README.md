# stubcompile — the offline syntax/type gate for the Android UI source

`app/src/com/macchanger/MainActivity.java` is the only Java source in the APK, and this
machine has **no Android SDK** — no `android.jar`, no `aapt`, no `d8`. Before this gate
existed, nothing here could show that the file even *compiles*; a syntax error or a
misspelled method could only be discovered on a device, or not at all.

`check.sh` closes that specific hole: it compiles hand-written `android.*` stubs and then
compiles `MainActivity.java` against them with the same Java 8 source/target levels the
project requires.

```
sh tools/stubcompile/check.sh [path/to/MainActivity.java]     # gate one file
sh tools/stubcompile/selftest.sh                              # prove the gate can fail
```

Both are POSIX `sh`, need only `javac` (a JDK that still accepts `-source 8`: 8..21) plus
`find`/`sort`/`mktemp`/`grep`/`sed`/`awk`/`tail`, and touch no app or CLI logic. No
network, no Gradle, no Android SDK, no `res/`, no manifest changes.

Absolute paths also work (the scripts locate the tree relative to themselves, so they can
be copied elsewhere with the project).

## Layout

```
tools/stubcompile/
├── check.sh        # the gate: compile stubs, compile the target against them, verdict
├── selftest.sh     # negative controls: proves check.sh actually fails broken input
├── README.md       # this file
└── stubs/          # hand-written android.* sources compiled by check.sh
    ├── android/R.java
    ├── android/app/{Activity,AlertDialog}.java
    ├── android/content/{Context,Intent,SharedPreferences,ClipData,ClipboardManager,…}.java
    ├── android/content/pm/, android/content/res/
    ├── android/graphics/, android/graphics/drawable/
    ├── android/net/, android/os/, android/provider/, android/text/, android/text/style/
    ├── android/util/, android/view/, android/widget/
```

`check.sh` compiles **every** `*.java` under `stubs/` (discovered with `find`), so adding a
stub needs no registration — drop the file in and rerun. Stubs must be `public` to be
visible from `com.macchanger`; a package-private helper may share a file with its public
type (e.g. `StubSharedPreferences` lives in `SharedPreferences.java` and cannot be named
by app code).

## Output and exit status

`check.sh` prints the javac diagnostics verbatim, then a final line that is exactly

```
STUBCOMPILE: PASS      # exit 0
STUBCOMPILE: FAIL      # exit 1
```

Exit status is 0 **only** on PASS; the verdict line is always last on stdout. The gate
also refuses to report a vacuous PASS: it fails if `javac` is missing, if the stub tree is
empty, if the target does not exist, if the stub compilation itself fails, or if javac
reports success but emits no `MainActivity.class`.

`selftest.sh` prints one line per control and ends with exactly `SELFTEST: PASS` (exit 0)
or `SELFTEST: FAIL` (exit 1).

## What this gate DOES prove

* `MainActivity.java` parses and type-checks at **Java 8 source/target level**, i.e. it
  does not use language features newer than 8 (`var`, switch expressions, records, text
  blocks, `instanceof` patterns).
* Every `android.*` type it imports exists in the stub API.
* Every method/field it calls exists on the receiver type with a compatible signature:
  argument count, argument types, return type used in context, checked exceptions that
  must be handled (`PackageManager.getPackageInfo` keeps its real `NameNotFoundException`),
  access modifiers, `@Override` targets, non-static access from static context.
* Local syntax inside the file is structurally valid (braces, semicolons, statements).
* Unresolved symbols and typos anywhere in the file are reported with the file/line and
  the symbol name.

In short: it catches the class of defect that `build.sh`'s discarded javac exit status
would otherwise hide completely, and it catches it offline, in seconds.

### Behaviour confirmed by experiment

Each row was checked by appending the construct to a copy of `MainActivity.java` and
running `check.sh` on it; the last column is the observed verdict. This is the evidence
behind the "does prove" and "does not prove" lists above.

| appended to the file | observed |
|---|---|
| `Runnable r = () -> { };` | **PASS** — lambdas are legal Java 8, so style is not enforced |
| `try (FileInputStream in = …) { }` | **PASS** — try-with-resources is Java 7, not rejected |
| `l.stream().filter(…).collect(…)` | **PASS** — `java.util.stream` is on the JDK classpath |
| `Paths.get("/tmp")` | **PASS** — `java.nio.file` does not exist on old Android and is not caught |
| `var x = 1;` | **FAIL** — `cannot find symbol`, i.e. post-8 syntax is rejected |
| `pm.getPackageInfo("a", 0);` without handling | **FAIL** — `unreported exception NameNotFoundException` |
| `etMac.getText().indexOf("x")` | **PASS** — the documented `Editable` permissiveness (false-PASS surface) |
| a method the stubs do not declare | **FAIL** — `cannot find symbol … symbol: method setNoSuchMethod(int)` |
| a stub method called with the wrong argument type | **FAIL** — `incompatible types: String cannot be converted to float` |

## What this gate does NOT prove — the honest list

1. **It does not run anything.** No line of app code executes. Wrong Android *semantics*
   — a screen that renders nothing, a callback on the wrong thread, a `LayoutParams` of
   the wrong kind, a toggle that never takes — cannot be caught here.
2. **The API surface is hand-written, not `android.jar`.** A member that this stub tree
   declares but a real device does not (or a signature that differs from the real one)
   gives a **false PASS**. Known deliberate permissiveness:
   * `android.text.Editable` here carries `indexOf`/`lastIndexOf`/`substring`/`trim`,
     which the real `Editable` interface does **not** (they are `String` methods). Code
     written against them compiles here and fails against a real `android.jar`.
     (`TextView.getText()` returns `CharSequence`, and `EditText.getText()` covariantly
     returns `Editable`, which matches the platform.)
   * Stub constant *values* (`Gravity.CENTER_VERTICAL`, `View.FOCUS_UP`,
     `WindowManager.LayoutParams.FLAG_SECURE`, `android.R.attr.*`, `Toast.LENGTH_*`,
     `InputType.*`) are plausible but arbitrary. Any code that depends on the numeric
     value of a constant — rather than just passing it through — is meaningless here.
   * Stub methods return fabricated non-null values (`getFilesDir()` → a made-up path,
     `getSharedPreferences()` → an in-memory map, `getResources().getDisplayMetrics()`
     → density 1.0). Nothing enforces the platform's real preconditions or errors.
3. **No API-level checking at all.** The stubs are one flat, unversioned surface: there is
   no `@RequiresApi`, no `minSdk`/`targetSdk`, no `Build.VERSION.SDK_INT` guarding
   analysis. Using an API 34 method in code that must run on API 21 compiles fine here.
4. **No Android-8-specific style enforcement.** The project's own rules — no lambdas, no
   streams, no try-with-resources, anonymous inner classes only — are *style*, and Java 8
   permits lambdas and streams, so `-source 8` cannot reject them. This gate will happily
   compile a lambda or a try-with-resources block. That constraint still needs a reviewer
   or a separate source check.
5. **`java.*` comes from the JDK, not from Android.** The compile classpath is the JDK 17
   runtime plus the stubs. Anything in `java.*`/`javax.*` that Android lacks or only has
   from a higher API level — `java.nio.file.Paths`, `java.util.stream`, `java.util.Optional`,
   `java.time` — compiles cleanly here and would fail on a device. `android.*` is stubbed;
   `java.*` is not constrained at all.
6. **It does not read the manifest.** Permissions (the deliberate zero-permission
   property), `targetSdkVersion`, `configChanges`, exported components, the launcher icon
   reference — none of that is checked, because checking it needs `aapt`, which is absent.
7. **It does not build, dex, align or sign anything.** "Compiles" is not "installable
   APK". `aapt`/`d8`/`zipalign`/`apksigner` do not exist in this environment, so the APK
   in `prebuilt/` cannot be reproduced and this gate never claims to.
8. **No resource-id validation.** The app ships no `res/`, so `android.R.*` references are
   just ints; a wrong framework resource reference compiles here and fails at runtime (or
   in a real `aapt` run).
9. **It compiles exactly the one file you point it at.** Other sources, the manifest, and
   `cli/macchanger.sh` are not gated by this script (the CLI belongs to the shell harness).
10. **It says nothing about correctness of behaviour** — whether the write path preserves
   the calibration image, whether restore tells the truth, whether the MAC survives a
   reboot. Those are device facts; the offline harness can only pin down the pure logic it
   can extract and run, and even that is not a substitute for testing on a phone.
11. **Stub drift is your maintenance problem.** If a future edit uses an `android.*` member
    that the stubs do not model, the gate FAILS with an honest "cannot find symbol" — that
    is a missing stub, not necessarily a bug in the app. Add the member (see below) rather
    than deleting the check.

## The negative control (`selftest.sh`) — why you can trust a PASS

A gate that always passes is worse than no gate, so `selftest.sh` feeds `check.sh`
deliberately broken input and fails loudly if the gate still says PASS. It asserts, for
each control, the exit status **and** that the diagnostics mention the injected problem
(so it cannot pass for an unrelated reason such as a bad path):

| # | control | expected |
|---|---------|----------|
| 1 | the working-tree `MainActivity.java` | PASS, exit 0 |
| 2 | an **independent baseline** `MainActivity.java`: a sibling `../.pristine/` when present, otherwise the repository's root commit read through `git` | PASS, exit 0. Skipped with a note — never failed — when the checkout holds no distinct revision (a shallow clone does not), because compiling the working tree twice would prove nothing |
| 3 | an unmodified **copy** in a temp dir | PASS — so later failures cannot be blamed on the copy |
| 4 | copy + a stray `}` (syntax error) | FAIL, diagnostics say `error:` |
| 5 | copy + an undefined symbol | FAIL, diagnostics name that symbol |
| 6 | copy + a method no stub declares | FAIL, diagnostics name that method |
| 7 | copy + a stub method called with the wrong argument type | FAIL, diagnostics name that method |
| 8 | copy + a removed statement-terminating `;` | FAIL, diagnostics say `error:` |
| 9 | a target path that does not exist | FAIL with `no such Java source` |

Controls 6 and 7 are the strong ones: they prove the compiler is genuinely resolving
members and signatures against the stub API, not merely counting braces. Control 3 makes
controls 4–8 meaningful. If the target is ever deleted from the compile, or the gate is
weakened into "always PASS", `selftest.sh` fails.

## Adding a stub for a new `android.*` member

1. Create `stubs/android/<pkg>/<Type>.java` with `package android.<pkg>;`.
2. Make the type `public` (it must be visible from `com.macchanger`).
3. Prefer `CharSequence`/interface parameters over narrow concrete types, and provide the
   obvious overloads (`setTextSize(float)` **and** `setTextSize(int, float)`,
   `addView(View)` **and** `addView(View, ViewGroup.LayoutParams)`, …) so small future
   edits do not break the gate for a reason that is not a real defect.
4. Keep the *shape* faithful where fidelity is what the gate is for: correct package,
   correct supertypes (`Button extends TextView`, `LinearLayout.LayoutParams extends
   ViewGroup.MarginLayoutParams`), checked exceptions kept checked, covariantly overridden
   returns kept covariant (`EditText.getText()` → `Editable`), nested types nested
   (`View.OnClickListener`, `TextUtils.TruncateAt`, `GradientDrawable.Orientation`).
5. Bodies may be empty or return trivial values; nothing runs them.
6. Rerun `sh tools/stubcompile/selftest.sh` — it re-validates the gate, including the
   baseline controls, against your change.

Stub coverage was additionally checked against the surface the remaining `java_core` /
`java_write` / `java_ui` stages are expected to need — `Window.setFlags` +
`WindowManager.LayoutParams.FLAG_SECURE`, `ClipboardManager` + `ClipData`,
`CheckBox`/`CompoundButton` + `OnCheckedChangeListener`, `AlertDialog.Builder`,
`ProgressBar`, `TypedValue` units in `setTextSize(int, float)`, `TextUtils.join`,
`PackageInfo.versionName/versionCode/lastUpdateTime`, `StatFs`/`SystemClock`/`Environment`,
`Editable.replace/clear/length`, `Intent(Context, Class)`, `OnLongClickListener`,
`OnTouchListener` + `MotionEvent`, `android.R.attr.state_pressed` — and all of it compiles.
That probe is deliberately **not** shipped as a fixture: it references app-level
identifiers (field and helper names) that other stages may legitimately rename, which would
turn a stub-coverage test into a false alarm about the gate.

## Relationship to the rest of the harness

This is part 1 of the `tools/` verification work package (issue **M9**): the compile gate
for the single Java source. The pure-logic extraction harness, the redirected-CLI test and
the README-claims checks are the other part and are separate scripts. `check.sh` is
self-contained and can be called from an aggregate verifier as-is.

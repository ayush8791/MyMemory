# MyMemory – Financial Ledger module (v0.1)

[![CI](https://github.com/ayush8791/MyMemory/actions/workflows/ci.yml/badge.svg)](https://github.com/ayush8791/MyMemory/actions/workflows/ci.yml)

Android app (Kotlin, Jetpack Compose). Reads bank SMS and Gmail receipts, understands them with
**Gemma running on the phone**, removes duplicates, categorises everything, and tracks money lent to or
borrowed from people. Nothing leaves the device.

## Run it

1. Open this folder in Android Studio (Ladybug or newer) and let it sync (the Gradle wrapper is included).
   Versions in `build.gradle.kts` are conservative; use the Upgrade Assistant if Studio suggests newer ones.
2. Install on a **real phone** (Android 8+, 6 GB RAM or more recommended). On-device LLMs are unreliable on emulators.
3. **Model**: on Hugging Face (LiteRT Community / Google) download a Gemma `.litertlm` file, accepting the Gemma licence:
   - Gemma 3n E2B (`google/gemma-3n-E2B-it-litert-lm`) or Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`) for recent phones
   - a Gemma 3 1B `.litertlm` for older / low-RAM phones
   In the app: **Settings → Import model file**. (`.task` files are the old MediaPipe format and will not work.)
4. **SMS**: Settings → Allow SMS access. Only messages that look like money movement are stored.
5. **Gmail** (optional, read-only):
   - Google Cloud Console → new project → enable **Gmail API**
   - OAuth consent screen → External → *Testing* → add your Gmail as a test user → add scope `gmail.readonly`
   - Credentials → **OAuth client ID → Android**, package `app.mymemory`, SHA-1 from `./gradlew signingReport`
   - In the app: Settings → Connect Gmail
   In *Testing* mode Google may ask you to re-approve periodically (about weekly). Verification of a restricted
   Gmail scope is only needed if you ever distribute the app to other people.
6. Tap **Sync now**. The first sync looks back 90 days and works through messages in the background.

## How a message becomes a ledger row

```
SMS inbox ─┐                                          ┌─ rules say "not money" ─► skipped
Gmail ─────┼─► raw (kept) ─► rule gate ─► Gemma ─► check against the text ─► dedup ─► ledger
Typed/voice┘                  (cheap)    (JSON)   (amount/ref must exist)     │
                                                                              └─► Review queue if unsure
```

- **Rules** (`parse/RuleParser.kt`) cheaply reject OTPs, promos, balance alerts and non-money texts, and extract amount,
  direction, last-4, UPI/UTR reference as hints. They are also the safety net if the model is missing or fails.
- **Gemma** (`parse/Prompt.kt`, `parse/TxnParser.kt`) returns one JSON record: merchant or person, platform
  (Amazon, Swiggy…), item, instrument, category, and whether it is a person transfer / lent / borrowed / repayment.
- **Guards**: an amount or reference number the model returns must literally appear in the source text; otherwise the
  text wins and the row goes to review. A kind that contradicts the money direction is corrected.
- **Dedup** (`ledger/Deduper.kt`): same reference ⇒ same; different references ⇒ different; different account ⇒ different;
  otherwise a score from time gap, different channel, matching counterparty/platform/account. Two alerts from the same bank
  sender are *not* merged (you can buy two coffees five minutes apart). Merging fills gaps (an email adds the item and platform
  to a bank SMS) and never overwrites what you edited.
- **People** (`ledger/Balances.kt`): a payment to or from an individual with no stated purpose is never silently an expense.
  It lands in Review with one-tap answers (I lent it / Paying back / I borrowed it / They repaid me). If you lent to Priya and
  an unexplained transfer from Priya arrives, it is pre-labelled as a repayment for you to confirm.
- **Learning**: fixing a merchant's category once re-categorises all its untouched rows and applies to future ones.
- Typed or spoken notes ("lent 500 to Rahul for lunch") go through the same pipeline via the **+** button.

## Tests

`./gradlew test` runs `LogicTest` (rule parser, dedup scoring, people balances). The same cases and the exact regexes were
validated in a standalone JVM harness before porting.

## Things worth knowing

- **Not compiled in the authoring environment** (no Android SDK / Kotlin toolchain offline). Expect a small number of compile
  fixes on first build. Two spots are most likely: the `litertlm-android:latest.release` version (pin a real one), and
  `conversation.sendMessage(prompt).toString()` in `ai/LlmEngine.kt` (change that one line if your LiteRT-LM version exposes the reply text differently).
- The SMS permission is restricted on Google Play to default SMS apps, so this is a sideload build.
- SMS recall depends on the rule gate (a bank text whose wording no rule recognises is not seen). Gmail messages go to the model whenever they contain an amount.
- Only the first ~1,800 characters of an e-mail reach the model.
- People are grouped by normalised name: "Rahul" and "Rahul Sharma" are separate people until a merge feature exists.

## CI and releases (GitHub Actions)

- **CI** (`.github/workflows/ci.yml`) runs on every push to `main` and every pull request: unit tests, debug APK build,
  lint (informational). The debug APK and test reports are attached to each run as artifacts.
- **Release** (`.github/workflows/release.yml`) runs when you push a tag:
  ```
  git tag v0.1.1 && git push origin v0.1.1
  ```
  It runs the tests, builds the APK, and publishes a GitHub Release with auto-generated notes, the APK and `SHA256SUMS.txt`.
  `v0.x` tags and unsigned builds are marked *pre-release*. The tag sets the app version name; the run number sets the version code.
- **Stable signing key (needed for Gmail).** Gmail sign-in is tied to the app's signing certificate. Without a fixed key
  every CI build is signed differently and Gmail stops working after each update. Run `scripts/create-keystore.sh`, add the four
  secrets it prints (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), register the printed SHA-1 as an
  Android OAuth client in Google Cloud, and releases will be signed with it. Keep a backup of the `.jks` file.
- **Dependabot** opens weekly PRs for Gradle and Actions updates.

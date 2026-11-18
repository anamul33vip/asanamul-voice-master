# AS ANAMUL VOICE — Master (v2.2.0)

Original Kotlin Android app (`com.anamul.voice`) + zero-dependency Node.js
API/admin backend. The reference APK in the original MASTER zip is NOT
included here and is never copied or re-signed.

## App (`android/`)

- Key login (device-locked), voice list, floating overlay, coupon redeem,
  TTS screen (ElevenLabs via backend).
- Mic Mode (v2.1.0): Shizuku injector — `Injector` + `MicInjector` (own code,
  AudioPolicy LOOP_BACK|RENDER) routes sounds into the microphone; when Mic
  Mode is off, playback is speaker-only. Needs the Shizuku app running +
  one Allow tap in Shizuku; mic result must be ear-tested in-game.
- v2.2.0 — My Voices library (own code, own design): add audio files
  (multi-pick), extract audio from video (MediaExtractor), cards with
  play / long-press rename-delete, search, export/import backup as own
  .zip format. Overlay panel rebuilt crash-safe (any error is Toast +
  log file, never a crash): Inject ON/OFF toggle, sound cards, Type &
  Send (TTS to mic), volume slider + Max, Stop all. Main screen shows
  key days left, volume, Shizuku options dialog (status + diagnostics
  + setup guide). No real-person clone voices; users add their own audio.
- v2.3.0 — Overlay FGS fix: the service now declares
  foregroundServiceType="mediaPlayback" and starts foreground with that
  type (Android 14 needs it — v2.2.0 crashed on Overlay tap without it).
  Login screen has a GET FREE KEY button opening the server's /getkey
  page: 6 steps (system check, device id, waits, YouTube Subscribe,
  Telegram Join) then a 12h key is minted and auto-copied (one active
  key per device, POST /api/web/mint). Admin panel: every key and coupon
  has a 📋 copy button, and a new key shows a copy box + auto-copy.
- v2.4.0 — Stylish redesign (own AS ANAMUL look): logo + Key status
  card, big Injector ON/OFF toggle, one-line buttons (no more clipped
  labels), logo bubble instead of the mic emoji, login Key paste button.
  Fixes: video add falls back to keeping the original file when audio
  extraction fails; TTS results auto-save into My Voices; auto-generate
  in TTS screen and auto-send in the overlay type tab (type, pause 2s,
  voice generates and plays routed); built-in 🔊 test sound (no voice
  or server needed) to check the speaker/mic path.
- Build with GitHub Actions: **Actions → Build APK → Run workflow**, enter the
  live backend URL as `server_url`. The APK is unsigned; it is signed with
  the owner's keystore before delivery.
- MasterLink self-registers the app in the Master Admin registry on launch.

## Backend (`server/`)

- `node server.js` — serves the API and the admin panel at `/` and `/admin`.
- Required env: `ADMIN_PASS` (min 12 chars, unique). Optional:
  `ELEVENLABS_API_KEY`, `ELEVENLABS_DEFAULT_VOICE_ID`,
  `ELEVENLABS_MODEL_ID`, `TTS_MAX_CHARS`, `PUBLIC_BASE_URL`, `PORT`.
- Data lives in `db.json` + `uploads/` on the server disk. On Render Free
  (ephemeral disk) keys/voices are lost on restart/redeploy — keep a backup
  of keys from the admin panel.
- Never put a real API key or password in source, the APK, or git.

# AS ANAMUL VOICE — Master (v2.1.0)

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

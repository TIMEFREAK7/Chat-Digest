# Tier 0 — feasibility spike

Throwaway app (`spike/`, label **Digest Spike**) plus the parts that survive into Tier 1 (`core/`: time lexicon, export parser, capture diff, prompt contract). No `INTERNET` or network-state permission; CI fails the build if one appears.

## Getting the APK
Every push runs `.github/workflows/build.yml`: unit tests → signed release APK → artifact `digest-spike-<run>`. Download it from the Actions run and sideload it on both phones.
Signing needs four repo secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. If they're missing, CI falls back to the debug key and prints a warning.

## Run protocol (each phone)
1. Install → enter **your name exactly as it appears in exports**, then nicknames, comma-separated.
2. **1. Grant notification access.** Leave the battery exemption **off** for the first 24 h.
3. Use the phone normally. Include at least one overnight, one reboot and one stretch with battery saver on.
4. At 24 h: **2. Battery exemption** on, and on OxygenOS/ColorOS also allow auto-launch and background activity. Run another 24 h.
5. **Save capture log…** and **4. Capture stats.**
6. Export 3–5 active chats (WhatsApp → chat → ⋮ → More → Export chat → **Without media**) → **5. Diff export(s) vs capture.**
7. Copy `gemma-4-E2B-it.litertlm` (and E4B) to the phone → **6. Import model.** Then **7. Summarize** your 6 golden-set exports, on GPU and then on CPU.
8. **8. Lexicon report** on the same 6 exports.
9. **Save report…** and send me `report.txt`, plus your hand scores. Do not commit it.

`report.txt` and `capture.jsonl` contain real messages. They're git-ignored. Keep them off the public repo.

## Missed messages: how we tackle them

**What causes a miss.** The app sees only what WhatsApp posts as a notification. A message is lost when:

| Cause | What it looks like |
|---|---|
| Listener down | OEM battery management kills the process, or Android unbinds the listener. Nothing gets logged until it reconnects. |
| Overflow | Many messages arrive in one update (for example after the phone was offline). WhatsApp's MessagingStyle window keeps only the most recent N, so older ones never reach us. |
| Truncation | WhatsApp shortens long message text inside the notification. |
| Seen live | You had the chat open, so WhatsApp posted no notification. This is not a real miss: you already read it. |

**Tier 0: measure it against ground truth.** WhatsApp's own export is the reference. `CaptureDiff` matches each export message from someone else against the capture log by normalized text and a time window of −1 to +2 minutes. Media is matched by its placeholder. Every unmatched message is given one of the causes above, using the log:
- **Listener down:** the message falls inside a disconnect→connect span, or a stretch of 45+ minutes with no log events at all (heartbeats run every 15 minutes; Doze stretches them to ~30 overnight).
- **Seen live:** the chat's notification was removed within the previous 30 minutes and nothing was posted for it since.
- **Overflow:** WhatsApp posted for that chat within 5 minutes of the message, but the message wasn't in the window.
- **Truncated:** a captured text is a prefix of the exported text.
- **Unexplained:** none of the above applies.

Your own messages and deleted messages are skipped. **Gate:** zero misses of any cause other than *seen live* across 48 h with exemptions on.

**Tier 1: make each cause visible instead of silent.** We can't recover a message that was never notified. Without root we can't read WhatsApp's database, so the rule is to never present an incomplete burst as complete:
- **Listener down:** log connect/disconnect and a heartbeat. Any burst that overlaps a gap gets a header: "⚠ capture was down HH:MM–HH:MM, messages may be missing". The onboarding status row shows the same. If a disconnect is seen, call `requestRebind`. Tier 0 deliberately doesn't, so the measurement stays honest.
- **Overflow:** WhatsApp's summary text ("N messages") is compared with what we captured for that chat. If it says more than we hold, the digest shows "⚠ ~K earlier messages not captured — open the chat".
- **Truncation:** if a captured text ends in "…", it is shown with ⚠. The verifier doesn't trust facts drawn from a truncated message.
- **Seen live:** these are dropped from the unread buffer. The reset-on-read rule already treats them as read.

How much of this ships depends on Tier 0's numbers. If overflow and listener-down both measure zero, Tier 1 only needs the gap header. A miss that is common and can't be explained is a no-go.

## Time lexicon matching rule
A message token shorter than 4 characters matches only an explicit list of variants (`kal`, `kaal`, `kl`; `aaj`, `aj`; `lac`; …). Tokens of 4+ characters match after normalization (doubled vowels collapsed, dh→d, w→v, z→j) at edit distance ≤ 1, except words on a short English blocklist (`same`, `side`, `sale`, `rate`, `pune`, …). The lexicon report lists every match as `token≈entry`, so wrong fuzzy hits are visible. Feed misses and misfires back into `TimeLexicon.kt`.

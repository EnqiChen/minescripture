# MineScripture

**An AI narrative layer for virtual worlds that recognizes meaningful moments and connects them with Scripture.**

> *AI understands the moment. Scripture provides the meaning.*

Minecraft is the proof of concept, not the product. MineScripture is an AI narrative layer that can eventually bring meaningful, Scripture-centered moments to many kinds of virtual worlds — not just Minecraft.

Built for the [Gloo × YouVersion "Scripture in New Frontiers"](https://www.kaggle.com/competitions/scripture-in-new-frontiers) hackathon.

---

## What it does

You play normally. Then a moment happens — you survive your first night, you find diamonds after an hour underground, you lose everything to lava — and a single verse arrives, timed to the moment, in the game's own language of actionbar, chat and title.

Not a Bible app bolted onto a game. The world's own moments become the invitation.

```
You died after 47 minutes underground, having just found diamonds.

  Because of the loving devotion of the Lord we are not consumed,
  for His mercies never fail. They are new every morning;
  great is Your faithfulness!
  — Lamentations 3:22-23 (BSB)
```

## How it works

**Minecraft detects the event. Gloo understands the moment and recommends Scripture. Java controls the gate. YouVersion provides every word of Scripture the player reads.**

```
Minecraft event
      │
      ▼
Session Story Memory  (ephemeral, last ~8 significant events + aggregates)
      │
      ▼
Gloo AI Studio  ──▶  interpretation: resonance · arc · emphasis · tone
      │
      └──────────▶  2–4 ranked Scripture references
                          │
                          ▼
                  MineScripture's gate
                  1. normalize   — 66-book alias dictionary; "Psalms 23:1",
                                   "Ps 23:1" and "PSA.23.1" all resolve alike
                  2. verify      — YouVersion 200/404 is the source of truth
                  3. score       — semantic fit, novelty, event fit, reliability
                  4. never fail  — fallback pool → disk cache → player
                          │
                          ▼
                  YouVersion  ──▶  verbatim text  ──▶  player
```

Gloo does not write Scripture; it points to it. MineScripture does not reinterpret Gloo's recommendation; it verifies that each reference is valid, retrievable, appropriate for presentation, and consistent with the player's experience.

### Why an AI, and not a lookup table

The same event in different stories deserves different Scripture. Die having lost everything, and the emphasis is comfort. Die seconds after finding diamonds, and it is perseverance. A static event→verse map is structurally incapable of that distinction, and so is any system that ignores what happened five minutes ago.

MineScripture also asks the AI to judge *tone*. When a moment is comedic rather than meaningful — a cactus, a fall into your own farm pit — it responds in kind, with humor that is clearly and deliberately not Scripture.

## Quick start

Requires **Java 25** and a **Paper 26.2** server.

```bash
# 1. Build
./gradlew build          # produces build/libs/minescripture-<version>.jar

# 2. Configure keys (never commit these)
cat > .env <<'EOF'
export MSC_YVP_KEY="your-youversion-platform-key"
export MSC_GLOO_ID="your-gloo-client-id"
export MSC_GLOO_SECRET="your-gloo-client-secret"
EOF
chmod 600 .env

# 3. Run a local test server (downloads Paper, boots with the plugin)
scripts/run-server.sh
```

For an existing server: drop the jar in `plugins/`, then either set the three
environment variables or fill in `plugins/MineScripture/config.yml`.

MineScripture degrades gracefully. Without a Gloo key it serves curated
interpretations; without a YouVersion key it serves previously cached passages.
Players never see an error.

## Commands

| Command | What it does |
|---|---|
| `/verse` | Replay the last verse, with full copyright |
| `/verse why` | Why this verse met this moment |
| `/verse mute` · `unmute` | Always available, persists across sessions |
| `/verse journal` | Your session's verses, bound as an in-game book |
| `/verse link` | Open the passage on YouVersion |
| `/msc explain` | Admin: the full interpret → verify → render trace |
| `/msc trigger <event>` | Admin: fire any event on demand |
| `/msc demo <event>` | Admin: deterministic scripted story through the real pipeline |
| `/msc stats` | Admin: fired/suppressed counts, AI budget, cache state |

## The thirteen moments

`first_join` · `rejoin` (welcome back, for every visit after the first) ·
`first_nightfall` · `survived_the_night` (dawn, if the night came after you) ·
`sheltered_till_dawn` (dawn, if you simply waited it out) ·
`eating_bread` · `taming` · `low_health_survival` (three hearts or less, still
standing) · `found_diamonds` · `player_death` → presented at respawn · `sleep` ·
`thunderstorm` · `fellowship` (two players near each other for a minute)

The two dawn moments are deliberately different beats. Fighting through the night
is relief and deliverance; waiting it out in a hole is patience — *"my soul waits
for the Lord more than watchmen wait for the morning"* — so they draw on separate
verses, and the quieter one arrives on the actionbar and far less often.

## Sacred, not spammy

A verse that arrives too often stops being a moment. Every candidate passes
milestone flags, per-event debounce, per-event and global cooldowns, and a
session cap before an AI call is even considered — and then an explicit budget
gate (10 per player per hour, 100 per server per hour). Over budget, only the
rarest moments still reach the AI; everything else serves from cache or the
curated pool.

The ordering matters: a player idling in a mob farm generates hundreds of
near-death events, and cooldowns retire them long before they can drain the
hourly AI allowance meant for the moment that actually matters.

Restraint here means pacing, never silence. A run of quick deaths widens the gap
each time — 60s, then 105s, then 184s, capped at five minutes and reset after ten
quiet ones — and repeats keep their verse but lose the title treatment. A player
having a terrible night is still spoken to; they just aren't spoken over.

## Privacy

Story Memory is ephemeral gameplay context, not a player profile. MineScripture
keeps only the recent in-game events needed to interpret the current session,
and clears them at logout.

**MineScripture persists gameplay milestones, not narrative profiles.**

**No personally identifying information is sent to AI services** — only anonymous
gameplay context: event names, session statistics, and which verses have already
been seen. No usernames, no chat messages, no coordinates.

## Content boundary

Every word of **Scripture** a player reads is **verbatim text from YouVersion**.
The AI selects Scripture; it never writes it.

On moments the AI judges lighthearted, **Gloo writes the joke itself** — that is
the point of asking a model to read the tone rather than matching a rule. A
validator rejects any generated line containing a chapter:verse pattern or a book
name followed by a number, so a quip can never be mistaken for Scripture, and a
curated human-written pool covers anything rejected or unavailable. Which of the
two produced a given line is recorded, and `/msc explain` reports it truthfully
rather than assuming.

The short narrative frames around a verse — *"You awaken in the dark."* — are
static human-reviewed text.

Serious moment → always Scripture. Comedic moment → humor, never Scripture-shaped.

## Attribution

Scripture text is retrieved at runtime from the **YouVersion Platform API**.
Interpretation and recommendation are performed by **Gloo AI Studio**.

Default translation: **Berean Standard Bible (BSB)**.

> The Holy Bible, Berean Standard Bible, BSB is produced in cooperation with
> Bible Hub, Discovery Bible, OpenBible.com and the Berean Bible Translation
> Committee. This text of God's Word has been dedicated to the public domain.

## Documentation

- [`docs/WRITEUP.md`](docs/WRITEUP.md) — the technical writeup
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — how the pieces fit
- [`docs/demo-script.md`](docs/demo-script.md) — the demo walkthrough
- [`docs/curation/`](docs/curation/) — prompts and curation notes

## License

[MIT](LICENSE). Scripture text remains subject to the terms of the translation
and of the YouVersion Platform API.

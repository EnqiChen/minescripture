# Architecture

How MineScripture turns a Minecraft event into a Scripture moment, and why each
boundary sits where it does.

## The responsibility boundary

**Minecraft detects the event. Gloo understands the moment and recommends
Scripture. Java controls the gate. YouVersion provides every word of Scripture
the player reads.**

Gloo expands the search space; Java controls the gate. Java does not reinterpret
Gloo's recommendation. It verifies that each recommended reference is valid,
retrievable from YouVersion, appropriate for presentation, and consistent with
the player's experience — for example, avoiding repetition and prioritizing
better contextual matches.

This split is what lets the whole canon be in range. Gloo may recommend anything;
the gate guarantees that whatever reaches a player is real, fetchable, unrepeated
and well-matched.

## Package layout

```
dev.minescripture
├── MineScripturePlugin      wiring, graceful degradation, cache warmer
├── config/    MineScriptureConfig · EventSpec(s) · FallbackPool · HumorPool
├── event/     the five listeners · NightfallClock · FellowshipTracker · Causes
├── trigger/   TriggerService · TriggerPolicy · AiBudgetGuard
│              StoryMemory · PlayerStateManager · TriggerContext
├── select/    MomentInterpreter · GlooClient · GlooTokenManager
│              RefNormalizer · RefValidator · CandidateScorer
│              LevityGuard · SessionVerseMemory · MomentProfileCache
├── scripture/ ScriptureClient · PassageCache · Passage
├── present/   Presenter (pure UI) · MomentPresenter (routing + timing)
├── command/   VerseCommand · AdminCommand
├── journal/   SessionJournal · BookWriter · MarkdownExporter
└── util/      Http · JsonUtil
```

Everything in `trigger/`, `select/` and `config/` is Bukkit-free, which is what
makes the interesting logic unit-testable without a server.

## Two-path timing

A cached interpretation computed *before* a death cannot know about the death.
Serving it at respawn would quietly destroy the project's central claim — that
the verse answers *this* moment. So events split by predictability.

**Predictable** (nightfall, sleep, bread, taming, thunderstorm, fellowship) —
arcs we can see coming. Serve instantly from the cached profile, then restock in
the background with the updated story.

```
event → policy [sync] → cached profile [instant] → validate → present
                     └→ async: Gloo re-interprets with updated story → cache
```

**Sudden** (death, diamonds, low health) — the meaning *is* the surprise. Build a
fresh interpretation whose story window already contains the just-fired event,
and wait for it, bounded.

```
event → policy [sync] → async executor:
    story += this event → Gloo interpret (2.5s cap)
      ├─ in time → validate → schedule to main thread → present
      └─ timeout → curated default → present
```

The server's main thread is never blocked: the bounded wait lives on an async
executor and presentation is scheduled back onto the main thread. Death has a
natural window — the respawn screen absorbs the wait, and the verse lands at
respawn. Diamonds get a deliberate one-beat delay that reads as reverence.

`TriggerService` enforces this structurally: when a sudden moment is granted no
AI call (budget exhausted, or the story has not changed), it presents the curated
default rather than a stale cached profile. There is no code path that shows a
pre-event reading for a post-event moment.

## The gate, in order

`TriggerPolicy` is pure Java and deterministic — all timing derives from the
event's timestamp, so tests drive it with a fake clock.

1. **mute** — always available to the player, persists across sessions
2. **milestone flags** — first join, first nightfall, first diamonds fire once ever
3. **per-event debounce** — a 1–8 block diamond vein must never fire twice
4. **per-event cooldown** — low health carries ten minutes
5. **repeat collapse** — mechanical repeats become aggregates, not moments
6. **global cooldown** — a floor between any two presentations
7. **session cap**
8. **death pacing** — deaths in a quick run wait progressively longer (60s,
   105s, 184s, capped at five minutes, reset after ten quiet ones). There is no
   lockout: a bad night thins out but is never silenced.
9. **AI budget guard** — 10/player/hour, 100/server/hour; over budget, only
   priority events still reach the AI

Priority moments (death, diamonds, first-times) use a shorter global floor than
ordinary ones, so dying seconds after a discovery is still allowed to speak.

An earlier design also carried an "unchanged story" check that skipped the AI
when nothing new had entered the window. It was removed: a moment that fires is
by definition new information, and the AFK protection it was meant to add is
already provided by steps 3–5. Keeping it meant every moment following an
interpretation was silently denied its own reading.

Steps 3–9 all run *before* step 10. That ordering is the AFK-farm fix: a player
parked in a mob grinder produces a near-death event every few seconds, and if the
budget gate came first, ten of them would exhaust the hourly allowance in two
minutes — leaving nothing for the dragon fight that evening. Cooldowns retire
mechanical repetition long before it can cost a token.

## Selection pipeline

```
Gloo refs (raw, possibly malformed)
   │
   ▼ RefNormalizer      "Psalms 23:1" · "Ps 23:1" · "PSA.23.1" · "I John 4:19"
   │                    → BOOK.CHAPTER.VERSE[-VERSE]
   │                    66-book alias dictionary; per-book chapter sanity;
   │                    1–3 verse span cap
   ▼ RefValidator       YouVersion 200 → accept · 404 → next candidate
   │                    curated refs pre-trusted; results memoized;
   │                    network error → skip, never block
   ▼ CandidateScorer    emphasis +10 · event fit +10 · resonance/tone +5
   │                    arc tokens +3 (cap 6) · no connection −5
   │                    seen today −10 · unseen +5 · reliable +2 · rank +3/2/1
   ▼ PassageCache → ScriptureClient → verbatim text
```

Scoring, not filtering. A valid-but-mismatched reference — Genesis 1:1 on a death
— loses to a better candidate rather than being rejected outright. References the
curated pool has never heard of score neutral, which is what keeps the whole
canon genuinely in range.

The normalizer exists because language models are inconsistent about book names.
The same model will write `Psalm`, `Psalms` and `Psa` for the same book, and
`Revelations` for a book that has no plural. Prompt instructions reduce this; they
do not eliminate it. The alias dictionary makes the inconsistency harmless.

## Never failing

```
scored pick → curated default (unseen first) → any curated default → disk cache
```

At startup, every reference in the fallback pool is fetched once and written to
disk. From then on the common moments are network-free, and a total outage still
produces real Scripture. The player never sees an error state; the worst case is
a curated verse instead of a tailored one.

## Memory: two stores, deliberately separate

| Store | Contents | Lifetime |
|---|---|---|
| **Gameplay progression** | milestone flags, mute, translation | persisted, like vanilla advancements |
| **Narrative interpretation** | recent events, deaths, themes, seen verses | **session only**, cleared at logout |

*MineScripture persists gameplay milestones, not narrative profiles.*

What reaches Gloo is a sliding window of the last ~8 significant events, plus
aggregates (`total_deaths`, `minutes_played`, `lost_items`), plus which verses and
themes this session has already seen. No usernames, no coordinates, no chat.

## Levity

When Gloo classifies a moment as `light`, MineScripture answers with humor
instead of Scripture. The quip rides along in the same interpretation response —
no second AI call, no extra latency, already covered by the budget guard.

`LevityGuard` rejects any generated quip that exceeds 120 characters, contains
quotation marks, contains a chapter:verse pattern, or contains a known Bible book
name followed by a number. Rejection is cheap and safe: the curated pool takes
over. The guard shares its book dictionary with `RefNormalizer`, so there is one
definition of "looks like a Scripture reference" in the codebase.

Bare biblical names remain allowed — the curated pool deliberately uses Jonah and
Daniel and Jericho, because that is where the warmth of the humor lives. What is
forbidden is the *shape* of a citation.

## Presentation

Three tiers, and nothing else. No toasts, no resource packs, no hover text.

- **minor** (bread, sleep) — actionbar, vanilla ~3s
- **standard** — three-line chat block: gray-italic frame, white verse, gold
  reference; stays in history for `/verse`
- **major** (respawn, diamonds, first join) — title and subtitle on a strict
  10/70/20-tick cycle so the screen is never trapped, plus the chat block
- **levity** — a single distinct line, no frame, no reference

The power is timing, not chrome.

## Testing

97 unit tests, none of which need a running server.

- `TriggerPolicyTest` — the full gate, including the AFK grinder: 40 rapid
  near-death events yield exactly one presentation and one AI call
- `TriggerServiceTest` — two-path timing, including a hung interpretation
  proving the 2.5s cap delivers a curated verse instead of nothing, and the
  post-death check proving a sudden interpretation sees the death
- `RefNormalizerTest` — every alias family, plus the sanity rejections
- `CandidateScorerTest` — the Genesis-on-a-death trap, novelty math, event fit
- `LevityGuardTest` — blocks `John 3:16`, `Gen 1`, quoted text; passes the
  curated pool
- `AssetsTest` — validates the shipped JSON: 52 verses, 15 quips, every event
  covered, no curated quip resembling a citation

# MineScripture — An AI Narrative Layer for Virtual Worlds (Gloo Hackathon Plan, rev 7)

## Context — what changed rev 6 → rev 7

Rev 6 was written the evening of Tue 7/22 and lost with the temporary chat. Today is **Sat 7/26**: the Hackathon folder is empty (no code exists), the Kaggle team is registered ✅ and Gloo credentials are verified ✅, but the **YouVersion key is not yet obtained** and the forum questions are presumably unposted. Rev 7 therefore makes five changes:

1. **Renamed Selah → MineScripture** (repo, package, classes, env vars, commands, all writeup wording).
2. **Responsibility boundary reworded technically** — no "spiritually correct" language.
3. **Platform vision elevated** — the narrative-layer positioning leads, immediately after the proof-of-concept line.
4. **Levity now includes AI-generated quips** (config-gated, light moments only, never mixed with Scripture) — and every compliance statement in the plan is updated to stay consistent with that.
5. **Schedule recalculated**: the 9-day D0–D8 plan is compressed into a 5-day R0–R4 sprint (today Sat 7/26 → submit Wed 7/30; Fri 7/31 close = emergency buffer only). Scope: **full rev 6 MVP** (user's choice — journal book + stretch window retained), with a pre-agreed cut order if gates slip.
6. **Two-path timing design** (review fix): sudden events (death, diamonds, low-health) get a *fresh* Gloo interpretation that includes the just-fired event, awaited off-thread with a strict 2.5 s timeout → FallbackPool. A cached pre-death interpretation is never shown for a post-death moment. Predictable events stay cache-first.
7. **AFK-grinder throttling** (review fix): per-event cooldowns + repeat-event collapsing + an unchanged-story check all run *before* the AI budget gate, so a mob farm can't drain the hourly budget.
8. **RefNormalizer alias dictionary** (review fix): `Psalm`/`Psalms`/`Psa`, `Rev`/`Revelation`/`Revelations`, `Song of Solomon`/`Song of Songs`, numbered-book variants (`1Jn`/`1 John`/`I John`) — LLM output inconsistency can never fail a lookup.
9. **R0 = build night**: most/all of the MVP gets built **tonight** by Claude Code (Fable 5, multiple agents as needed), starting with generating the two JSON asset files. The standing developer briefing is embedded below as **Development rules**.

First execution steps after approval = R0 (below), starting with the YouVersion key and the JSON assets. This plan file gets copied into the repo as `docs/PLAN.md`.

---

## Competition context

Enqi + Florence are competing in the Gloo/YouVersion **"Scripture in New Frontiers"** Kaggle hackathon (submissions close **Fri July 31, 2026**; internal deadline **Wed July 30**). Judging: **Impact & Vision 40 · Video ≤3 min 30 · Technical Depth 30**. Deliverables: public YouTube video, technical writeup, public repo (OSI license). **Both the YouVersion Platform API and Gloo AI Studio API are mandatory.** Prize ~$10,000; ~2–6 teams entered as of mid-July.

**Positioning:** *MineScripture is an AI-powered narrative layer for virtual worlds that recognizes meaningful moments and connects them with Scripture.* **Minecraft is the proof of concept, not the product.** MineScripture is an AI narrative layer that can eventually bring meaningful, Scripture-centered moments to many kinds of virtual worlds — not just Minecraft. Tagline: **"AI understands the moment. Scripture provides the meaning."**

## The Product

**MineScripture** — a **Paper server-side plugin** (Java 21, Paper 1.21.x). Play normally → a meaningful moment happens → a short verse appears with precise **timing** and minimal UI (actionbar/chat/title only) → optional `/verse why`, journal, `/verse link`. Debounce + cooldowns + no-repeat + death-spam guard + an AI budget guard keep it sacred, not spammy. `/verse mute` always available. When a moment is *comedic* rather than meaningful, MineScripture knows the difference — see **Levity**.

### Responsibility boundary (hackathon architecture)

**Minecraft detects the event. Gloo understands the moment and recommends Scripture. Java controls the gate. YouVersion provides every word of Scripture the player reads.**

**Gloo expands the search space; Java controls the gate.** Java does not reinterpret Gloo's recommendation. It verifies that each recommended reference is **valid, retrievable from YouVersion, appropriate for presentation, and consistent with the player's experience** (for example, avoiding repetition and prioritizing better contextual matches).

```
Minecraft event
       |
 Session Story Memory (sliding window)
       |
       v
     Gloo  ──→ interpretation (resonance + arc + emphasis + tone [+ quip if light])
       |
       └──→ recommended_refs (2–4, all of Scripture)
                  |
                  v
          Java RefValidator
          1. parse & normalize        ("Lamentations 3:22-23" | "LAM 3:22-23"
                                        → internal BOOK.CHAPTER.VERSE)
          2. existence check           (YouVersion GET passage: 200 accept,
                                        404 reject → next candidate)
                  |
        +---------+---------+
        |                   |
   valid candidates      rejected
        |
        v
   candidate scoring:                 (scoring, not hard filtering)
   - semantic fit vs interpretation   (lightweight local metadata; Gen 1:1
                                        for a death moment → penalty)
   - unseen bonus / seen-today −10    (no "every death = the same famous verse")
   - event fit
   - reliability                      (previously validated refs score up)
        |
        v
   YouVersion API  →  verbatim text  →  Player

Failure chain (Gloo down / malformed JSON / all refs invalid / YouVersion down):
   FallbackPool → YouVersion disk cache → Player.  The player NEVER experiences failure.
```

- **Gloo chooses the Scripture.** Output: `{resonance, emotional_arc, emphasis, tone, recommended_refs:[2–4 ranked], quip (optional, only when tone is light), reasoning (logs only)}`. Not limited to a curated pool — the whole canon is in range, which is exactly the variety a long-term "narrative layer" needs, and the product argument for a recommendation layer: **when MineScripture expands beyond Minecraft, the recommendation layer is what scales.** The `emphasis` field is where Gloo earns its place: died having lost everything → *comfort*; died right after finding diamonds → *perseverance*; died after the dragon fight with friends → *humility/gratitude* — and the verse follows that nuance.
- **Java's gate, in order:** (1) **normalize** — accept `LAM.3.22-23`, `LAM 3:22-23`, `Lamentations 3:22-23`, **plus an alias dictionary for the LLM-inconsistency trap** (`Psalm`/`Psalms`/`Psa`/`Ps`, `Rev`/`Revelation`/`Revelations`, `Song of Solomon`/`Song of Songs`, `1Jn`/`1 John`/`I John`), convert to one internal `BOOK.CHAPTER.VERSE` format (kills the string-matching trap); (2) **exist** — the YouVersion passages API doubles as the source-of-truth validator (200/404); (3) **score** — semantic fit against the interpretation via lightweight local metadata (known refs carry `{themes, tone}` tags; unknown refs score neutral), novelty (seen-today penalty −10, unseen bonus), event fit, reliability. Best score wins; a valid-but-mismatched ref loses to a better candidate rather than being hard-rejected. (4) **never fail** — FallbackPool (~40 refs + per-event defaults) → disk cache → player. (The full offline-first VerseRanker over an expanded library remains a documented future improvement.)
- **Content boundary:** Every word of *Scripture* the player reads is **verbatim YouVersion text** — the AI selects Scripture; it never writes it. All other player-facing text is **human-written** (frames, curated jokes), with one clearly-bounded exception: on `tone: light` moments with `levity_ai: true`, a short **AI-generated quip** may appear — visually distinct, never presented as, alongside, or in the style of Scripture (see Levity). We still post the YouVersion AI-clause question to organizers on R0 (their co-hosted hackathon makes approval highly likely; verse text remaining verbatim YouVersion is the substantive protection either way).
- **How Gloo influences the next selection:** after each fire, Gloo re-interprets with the *updated* story and replaces the cached profile+refs — consecutive moments in one arc get evolving emphasis (loss → restoration → gratitude). On the predictable path, interpretation+recommendation is cached ahead of the moment; on the sudden path it is computed fresh within the bounded window (see **Two-path timing**). Integrity checks always run at fire time with fresh anti-repeat state.

### Proving Gloo earns its place (the skeptical-judge question)

*"Couldn't you just map events to themes statically?"* — demonstrated on camera and in the writeup:
1. **Same event, different story → different Scripture.** `player_death` after losing everything (comfort → "When you pass through the waters…") vs seconds after a diamond find (perseverance → Rom 5:3-5). A static map is structurally incapable of this.
2. **Tone discrimination (Levity)** — knowing reverence from comedy is judgment no rule table makes well. The AI's primary job stays *understanding the moment*; generating a quip is a showcase, not the point.
3. **Evolving arcs** — the third verse of a session knows about the first two moments.
4. **Open-canon recommendation** — verse variety beyond any hand-built list.

### Levity — humor with the same integrity

When Gloo classifies a moment as `tone: light` (e.g., a cactus death, a small-fall pratfall, falling into your own farm pit), MineScripture may respond with either **(1) a curated human-written Bible-themed joke** (~12–15 pool) or **(2) an AI-generated lighthearted Bible-themed quip**, depending on configuration. AI recognizes that this is a lighthearted moment and may respond with either, config-controlled:

- **Config:** `levity: true|false` (master switch) and `levity_ai: true|false`. Shipped defaults: `levity: true`, `levity_ai: false` (conservative default for public release); **the demo config enables both** so the AI-quip beat can be shown on camera.
- **Constraints:** AI-generated humor fires on light moments **only**, **never replaces Scripture on meaningful moments**, and is **clearly separated from biblical text** — distinct presentation format (lighter styling, no verse frame, no gold attribution line), so generated text can never read as a verse. Serious moment → always Scripture from YouVersion. Comedic moment → joke (curated or generated). **Never mix generated text with Scripture or present AI text as if it were biblical.**
- **Mechanics:** the quip rides in the same interpretation response (optional `quip` field, ≤120 chars, only when tone is light) — no extra Gloo call, cache-first timing preserved, AI budget guard already covers it. A **LevityGuard** validates every generated quip: length cap, and rejection of anything containing a verse-citation pattern (book name + chapter:verse) so AI text can never masquerade as Scripture. Any guard failure, generation absence, or `levity_ai: false` → curated pool. Tone gate + separate levity cooldown; never fires on genuinely meaningful moments. Delightful video beat; tone judgment is itself evidence the AI understands moments.

### Two-path timing — the verse must match *this* moment

A cached interpretation computed *before* a death cannot know about the death — serving it at respawn would break the exact same-event-different-story moment the demo depends on. Events therefore split into two paths:

**Predictable events** (nightfall, sleep, bread, taming, thunderstorm, fellowship — arcs we can see coming; fellowship's 60 s proximity window even gives us a head start): fully async, cache-first. Interpretations are prepared ahead of the moment and refreshed in the background after each fire.

```
Bukkit event → TriggerPolicy [sync] → MomentProfileCache.get(event) [instant]
             → RefValidator → Presenter.show (timed to the moment)
             → async: Gloo re-interprets with UPDATED story → cache updated profile+refs
```

**Sudden events** (player_death, found_diamonds, low_health_survival — the meaning IS the surprise): a fresh Gloo interpretation whose story window *includes the just-fired event*, awaited on an async executor with a strict timeout:

```
Bukkit event → TriggerPolicy [sync] → async executor:
    story += current event → Gloo interpret (CompletableFuture, 2.5 s timeout)
      ├─ returns in time → RefValidator → schedule back to main thread → Presenter.show
      └─ timeout / failure → FallbackPool (per-event default) → Presenter.show
```

The server main thread is **never** blocked — the bounded wait lives on the async executor and presentation is scheduled back onto the main thread. Death has a natural window: the respawn screen absorbs the wait, and the verse presents at respawn. Diamonds get a deliberate one-beat delay, which reads as reverence, not lag. **Never show a pre-death interpretation for a post-death moment.**

- **Cold start:** per-event curated defaults until real StoryMemory exists — Gloo never interprets a hypothetical for a blank player.
- **Any Gloo failure:** FallbackPool via per-event defaults (2.5 s cap on the sudden path, 4 s on background restock; lenient first-`{…}` extraction, enum + USFM validation). Display never waits beyond the bound, never blocks.

### AI budget guard — "does this moment deserve AI?"

Even with cooldowns, a 5 h/day player generates many candidate moments. TriggerPolicy therefore ends with a budget gate **before** any Gloo call: **max interpretations 10/player/hour, 100/server/hour** (config). Over budget → priority events only (death, diamonds, first-times); everything else serves from cached profiles or FallbackPool. The flow is *Event → TriggerPolicy → does this deserve AI? → Gloo*, never *Event → Gloo*. This bounds token burn AND reinforces the sacred-not-spammy philosophy — worth one line in the writeup.

**Order matters — cooldowns before budget (the AFK-farm problem).** A player parked in a mob grinder takes `low_health` hits continuously; naïvely each one is a "near-death experience" and ten of them would drain the hourly budget in two minutes — leaving nothing for the real Ender Dragon death later. Three throttles run *before* the budget gate so mechanical repetition can never reach Gloo: (1) `low_health_survival` carries a long per-player cooldown (default 10 min); (2) repeated identical events collapse into StoryMemory **aggregates** (`hits_taken: 40`) rather than individual AI-worthy moments; (3) an **unchanged-story check** — if nothing materially new has entered the story window since the last interpretation, reuse it and skip the call entirely. The budget guard is the last line of defense, not the first.

### Memory — two explicitly separate stores

| Store | Contents | Lifetime |
|---|---|---|
| **Gameplay progression state** | `has_seen_first_join`, `has_discovered_diamonds`, mute preference, chosen translation | persisted locally (like vanilla advancement flags) |
| **Narrative interpretation state** | recent events, deaths, theme history, seen verses, emotional arcs | **ephemeral — session only**, max 50 events, cleared at logout (short reconnect grace) |

Writeup sentence (verbatim): **"MineScripture persists gameplay milestones, not narrative profiles."**

Sent to Gloo: sliding window of the last 5–8 significant events + aggregates (`total_deaths: 3, minutes_played: 240, lost_items: true`) + session seen_refs/theme_counts. No chat messages, no coordinates, no usernames.

### Privacy (writeup section — trust multiplier)

> "Story Memory is ephemeral gameplay context, not a player profile. MineScripture keeps only the recent in-game events needed to interpret the current session, and clears them at logout. MineScripture persists gameplay milestones, not narrative profiles. **No personally identifying information is sent to AI services** — only anonymous gameplay context (events, biome class, session stats)."

## Stack decision (objective comparison)

| Criterion | Paper plugin | Fabric mod | Datapack + RCON app |
|---|---|---|---|
| Event coverage | ✅ 1:1 Bukkit events | ⚠️ no crafting event w/o mixins | ⚠️ gaps |
| API calls (both mandatory) | ✅ unrestricted JVM HTTP | ✅ | ❌ needs 2nd process |
| Demo friction | ✅ players join with an unmodified vanilla-compatible client | ❌ per-machine Fabric install | ⚠️ two processes |
| Impact story (40 pts) | ✅ one jar on any Paper/Spigot server | ⚠️ per-player install | ❌ hobby-grade |
| Technical-depth optics (30 pts) | ✅ | ✅ | ❌ reads as a hack |
| Dev risk in 5 days | ✅ lowest | ⚠️ toolchain overhead | ⚠️ integration jank |

**Verdict: Paper plugin.** Roadmap caveat: admins currently supply API keys in `config.yml`; enterprise version = centralized MineScripture key-proxy so church/community admins drop the jar in and go.

## Verified API facts

- **YouVersion** — base `https://api.youversion.com`, header `X-YVP-App-Key` (free, instant key — **obtain today, R0**). `GET /v1/bibles/{bible_id}/passages/{USFM}?format=text`; `GET /v1/bibles` requires `language_ranges[]=eng`. Default **bible-id 3034 = Berean Standard Bible** (public domain); request NIV fast-track R0. Attribution: reference + translation inline; full copyright via `/verse` + README. No audio API. (Bonus: the passages API doubles as our ref-existence validator.)
- **Gloo** — ✅ **credentials verified.** OAuth2 `POST https://platform.ai.gloo.com/oauth2/token` (client credentials, `scope api/access`) → 1-h token, auto-refresh. `POST /ai/v2/chat/completions`: OpenAI shape + `auto_routing:true` + `tradition` param. PAYG + $20 kickoff credit suffices; budget guard bounds burn. No published rate limits → cache-first + budget guard keep calls rare.
- **Tradition setting (locked):** default `tradition: broad_christian` in `config.yml`. Design intent, stated in config comment and writeup: *MineScripture recommends Scripture based on the human moment and biblical themes, without applying a denominational lens.* At request time this maps to the closest value Gloo's `tradition` param actually accepts — confirm the exact accepted enum during the R0 curl test so an unknown value never 400s. Server admins may set a specific tradition; tradition A/B stays a writeup note.

## Architecture

Repo `minescripture/` in `/Users/chenenqi/Desktop/Hackathon/` (public GitHub, MIT). Gradle Kotlin DSL, Java 21, `compileOnly paper-api:1.21.x`, **zero shaded deps** (Gson in Paper, `java.net.http.HttpClient`, Adventure bundled).

```
minescripture/src/main/java/dev/minescripture/
├── MineScripturePlugin
├── config/    MineScriptureConfig, FallbackPool, HumorPool, EventSpec
├── event/     LifecycleListener, DeathListener, SurvivalListener,
│              WorldListener, MilestoneListener, NightfallClock, FellowshipTracker
├── trigger/   TriggerService, TriggerPolicy (incl. AiBudgetGuard), TriggerContext,
│              StoryMemory, PlayerStateManager
├── select/    MomentInterpreter (Gloo), GlooClient, GlooTokenManager,
│              MomentProfileCache, RefNormalizer, RefValidator, CandidateScorer,
│              SessionVerseMemory, DefaultInterpretations, LevityGuard
├── scripture/ ScriptureClient, Passage, PassageCache, BibleCatalog
├── present/   Presenter (actionbar | chat | title — nothing else)
├── command/   VerseCommand, AdminCommand (/msc)
├── journal/   SessionJournal, BookWriter, MarkdownExporter
└── util/      Http, JsonUtil
resources/: plugin.yml, config.yml (empty keys; env MSC_YVP_KEY / MSC_GLOO_ID / MSC_GLOO_SECRET),
            fallback.json (~40 refs with {themes, tone} metadata + per-event defaults + default interpretations —
            the metadata doubles as the CandidateScorer's semantic-fit table),
            humor.json (~12–15 curated), events.json (event specs)
docs/: PLAN.md (this file), WRITEUP.md, ARCHITECTURE.md, demo-script.md, curation/
scripts/: run-server.sh, prefetch-fallback.sh
```

Command surface: players `/verse …` (unchanged); admin **`/msc`** (alias `/minescripture`).

### Event semantics (defined precisely)

| Event | Definition |
|---|---|
| `first_join` | first time this player ever joins this server (persisted milestone flag) |
| `first_nightfall` | **per-player first experienced nightfall**: world time crosses ~13000 while player online and flag unset |
| `player_death→respawn` | one selection at death (from cached profile), **presented at respawn** ("You awaken at dawn."), one beat; comedic causes route to Levity via tone |
| `found_diamonds` | breaks diamond ore **AND never discovered diamonds before** (persisted milestone flag) **AND** 5-min debounce — a 1–8 block vein must never fire twice |
| `fellowship` | **not** login: `FellowshipTracker` (every ~10 s) — two players within 12 blocks for ≥60 s, first time per pair per session |
| `low_health_survival` | `EntityDamageEvent`: `getFinalDamage() > 0` and `getHealth() − getFinalDamage()` ∈ (0, 4.0] (player survived), not cancelled; **10-min per-player cooldown + repeated hits collapse into aggregates (AFK-grinder guard)** |
| `eating_bread` / `sleep` / `taming` / `thunderstorm` | `PlayerItemConsumeEvent` (BREAD) / `PlayerBedEnterEvent` OK / `EntityTameEvent` / `ThunderChangeEvent` → world-level cooldown, broadcast to opted-in players |

### Key components

- **`MomentInterpreter`** — system prompt: "You interpret meaningful moments in a player's session story and recommend Scripture. You never write Scripture text shown to players. Respond ONLY with JSON `{resonance, emotional_arc, emphasis, tone, recommended_refs:[2–4 canonical USFM refs, ranked], quip (optional: a short lighthearted Bible-themed one-liner, ONLY when tone is light, never a verse or citation), reasoning}` using the provided enums and standard USFM book codes." User msg = event + sliding-window story + aggregates + seen_refs + theme_counts. `temperature 0.4`, `auto_routing:true`, `tradition` from config. Reasoning + routed model logged server-side only.
- **`RefNormalizer` / `RefValidator` / `CandidateScorer`** — the gate: normalize any of `LAM.3.22-23` / `LAM 3:22-23` / `Lamentations 3:22-23` to internal `BOOK.CHAPTER.VERSE` via a book-name+code table **backed by an alias dictionary** — singular/plural (`Psalm`/`Psalms`), common abbreviations (`Psa`, `Ps`, `Rev`), popular misnamings (`Revelations`), numbered-book variants (`1Jn`/`1 John`/`I John`), `Song of Solomon`/`Song of Songs` — so Gloo's output inconsistency can never fail a lookup (chapter/verse sanity-checked); existence via YouVersion (200/404, 404 → next candidate); then score valid candidates — semantic fit from local `{themes, tone}` metadata (unknown refs neutral), seen-today −10 / unseen bonus, event fit, reliability (previously validated refs) — best score wins. All pure Java, JUnit-tested.
- **`LevityGuard`** — pure Java: quip length cap, verse-citation-pattern rejection (generated text may never resemble Scripture), fallthrough to curated pool. JUnit-tested.
- **`TriggerPolicy`** — mute → milestone flags → per-event debounce → per-event + global cooldowns (low_health: 10 min) → repeat-event collapse into aggregates → session cap → death-spam guard (≥3 deaths/5 min → 15-min suppression) → unchanged-story check (no material story delta → reuse last interpretation, skip Gloo) → **AiBudgetGuard** (10/player/hr, 100/server/hr; over budget → priority events only, others cached/fallback) → levity gate. Pure Java, JUnit-tested; suppression reasons logged.
- **`Presenter`** — (1) Minor events (bread, sleep): actionbar (vanilla ~3 s duration, unobtrusive). (2) Standard events: 3-line chat block (vanilla ~10 s fade, remains in history for replay/`why`), formatted gray-italic human frame / white verse / gold `— Lamentations 3:22-23 (BSB)`. (3) Major events (respawn, diamonds): title + subtitle + chat block; title timing uses the Adventure API's strict 5-second cycle — `Title.Times` of 10 ticks in, 70 stay, 20 out — to prevent screen trapping. Levity output uses a visually distinct light format (no frame, no attribution line). Soft vanilla chime, no resource pack. No toasts, no hover/click, no audio. **The power is timing, not chrome.**
- **Commands** — `/verse` (replay) · `why` · `mute|unmute` · `translation list|set` · `journal` · `link`. Admin: `/msc trigger <event>` · **`/msc explain`** — judge panel, wording matches architecture: *"Moment: died after 47 min underground, having just found diamonds. · Interpreted & recommended by Gloo AI Studio: loss → restoration (emphasis: perseverance) → Lamentations 3:22-23. · Verified by MineScripture: canonical, unseen this session. · Text: YouVersion (BSB)."* Model-routing details in writeup only. · **`/msc demo <event>`** (locked in) — deterministic demo mode: scripted StoryMemory + fixed seed through the full real pipeline, then `/msc explain`. Live gameplay footage stays real; technical-explanation shots become repeatable. Cheap video-day insurance. · `stats` (fired/suppressed by reason, gloo/cache/fallback mix, budget state) · `reload` · `prefetch`.
- **`ScriptureClient`/`PassageCache`** — memory + disk; startup warmer prefetches FallbackPool refs; Gloo-recommended refs cached on first fetch → repeat moments are network-free.
- **`journal/`** — session verses → written book + markdown export at quit. Stretch: labeled Gloo recap appended to markdown only, never in-game.

## Development rules (standing Claude Code briefing — applies to every build session)

Claude Code (Fable 5, multiple agents as needed) builds as an expert Java 21 / Paper API 1.21.x developer under three non-negotiable rules:

1. **The threading boundary (two-path design).** Do not default all network calls to fire-and-forget async. Predictable events (nightfall, sleep, bread, taming, thunderstorm, fellowship) are fully async cache-first. Sudden events (player_death, found_diamonds, low_health_survival) need the verse to match the *current* moment: a bounded wait on an async executor — `CompletableFuture.get(2500, TimeUnit.MILLISECONDS)` — never on the main server thread, with presentation scheduled back sync; on timeout, drop immediately to the local `FallbackPool`. Do not show a cached pre-event interpretation for a post-event moment.
2. **Strict Paper 1.21.x API compliance.** No hallucinated legacy/deprecated Spigot-era methods. `EntityDamageEvent`: check `getFinalDamage() > 0` and that the player actually survived. Modern events for interactions (`PlayerItemConsumeEvent` for bread, `EntityTameEvent`, `PlayerBedEnterEvent`). Adventure API for all player-facing text.
3. **Assets before Java.** The first build task is generating the two JSON asset files completely (no hand-writing): **`fallback.json`** — exactly 40 diverse Bible verses mapped across the 10 core events, each entry with reference + `themes` (string array) + `tone` (string), plus per-event default narrative interpretations; **`humor.json`** — exactly 15 curated, clean, lighthearted Bible-themed jokes/quips for comedic-death fallbacks. Enqi + Florence review/trim both files R1 morning (~30 min — replaces the old 2-hour curation block).

## MVP — 10 events, fallback pool, humor pool (full rev 6 scope, locked)

Events: first_join · first_nightfall · eating_bread · taming · low_health_survival · found_diamonds · player_death→respawn (+ Levity routing) · sleep · thunderstorm · fellowship (proximity).

**Curation (R0, AI-drafted → human-reviewed):** generated tonight per Development rule 3; reviewed R1 morning. Never blocks a gate.

**Stretch (only if R2 gate green by Mon evening):** dragon_defeat (title finale) → Session Story markdown recap. **Cut:** villager trade, crop harvest, audio/resource pack, toast hack, hover/click chat, grounded endpoint.

**Pre-agreed cut order if gates slip** (decide at the gate, not mid-day): 1) stretch dragon_defeat, 2) in-game written book (keep markdown export), 3) taming + thunderstorm events, 4) fellowship, 5) `/verse link`. **Never cut:** video time, the same-event-different-story A/B, the fallback chain, either mandatory API integration.

## Future improvements (writeup section — not hackathon scope)

- **Offline-first VerseRanker**: full local ranked selection over an expanded curated library so even network outages get narrative-aware picks (today's outage path = curated defaults). Weight philosophy, documented so it reads intentional: *resonance = primary meaning; event fit = contextual appropriateness; arc = narrative progression; emphasis = emotional nuance; novelty = repetition control.*
- **`tradition` A/B exploration** (same death context across four traditions) — interesting, unnecessary for MVP.
- **Opt-in persistent preference memory** (cross-session seen_refs/theme history).
- **Hosted key-proxy** so server admins need no developer accounts.
- **More worlds** — the narrative layer generalizes beyond Minecraft.

## Day-by-day — compressed sprint (A = plugin core + video lead; B = APIs + writeup lead; both drive Claude Code)

Status going in: Kaggle team ✅ · Gloo credentials + credits ✅ · YouVersion key ❌ · forum questions ❌ · repo ❌.

- **R0 — Sat 7/26 (today): BUILD NIGHT.** B: YouVersion key → curl BSB passage; accept BSB license; request NIV fast-track; post AI-approval question on the Kaggle forum + confirm exact deadline hour + max team size. A + Claude Code (Fable 5, multiple agents as needed), in order: (1) generate `fallback.json` + `humor.json` (Development rule 3); (2) public repo `minescripture` (MIT, .gitignore, empty-key config), Gradle skeleton, Paper 1.21.x runs locally; (3) the MVP core, as far as it lands tonight: config + loaders, TriggerService/TriggerPolicy (incl. AiBudgetGuard + AFK throttles), StoryMemory, PlayerStateManager, all listeners incl. NightfallClock + FellowshipTracker, ScriptureClient + PassageCache + GlooTokenManager, MomentInterpreter (incl. quip field) + MomentProfileCache + RefNormalizer (alias dictionary) / RefValidator / CandidateScorer + SessionVerseMemory + cold-start defaults, Presenter (3 tiers + levity format), two-path timing flow, LevityGuard. OBS test. **Gate R0 minimum (tonight): die → real BSB verse at respawn via fallback path; both APIs curl-verified. Stretch gate: Gloo end-to-end on the sudden path.**
- **R1 — Sun 7/27:** Finish whatever R0 didn't land. Morning: 30-min human review of `fallback.json` + `humor.json`; prompts saved to `docs/curation/`. Then: `/verse` family + persistence + `/msc explain|trigger|stats|demo|reload|prefetch` + journal/book/markdown + prefetch warmer + Levity end-to-end (curated + AI quip on demo config). **Gate R1: Gloo recommends → normalized/validated/scored → YouVersion text end-to-end on BOTH paths (a death gets a post-death interpretation); unplug network → still instant via fallback; cactus death → levity fires; every event fires via `/msc trigger`; state survives restart.**
- **R2 — Mon 7/28:** Tuning (cooldowns, debounce, budget guard + AFK throttles, tone gate, timing-to-moment, sudden-path timeout feel), edge cases (bad keys, Gloo down, malformed refs, timeout mid-respawn), JUnit suite green. Evening: **real 45-min survival playtest — tune by feel**; then stage demo world (vista spawn, prepared diamond cave, bed hut, wolf, bread, a cactus) + shot list + rehearsal. Tag v0.9. B all day in parallel: writeup skeleton → full draft. **Gate R2: playtest feels right (sacred when sacred, light when light); demo world staged; shot list written.** Stretch window (dragon_defeat) opens only if green.
- **R3 — Tue 7/29 — VIDEO DAY:** **GUI Scale 3–4 first**; 1080p60; record all beats — opening nightfall sequence, death→respawn centerpiece, **same-event-different-story A/B (the "why AI" proof), one Levity beat (label the AI-generated quip on screen)**, storm/fellowship with two players, `/msc explain` cutaway; voiceover; rough cut assembled by end of day. B: writeup finalized in parallel.
- **R4 — Wed 7/30 — FINISH & SUBMIT:** final edit ≤2:50, captions, upload to YouTube (public), verify logged-out; code freeze v1.0; git-history secrets sweep; fresh-clone build + `run-server.sh` from scratch + vanilla client joins + 3 live triggers; README final; proofread everything; **Kaggle SUBMIT.** (7/31 = emergencies only.)

## Submission strategy (70/100 pts is story)

- **Impact framing:** 170M+ monthly Minecraft players; Gen Z/Alpha live inside game worlds while scripture engagement declines. Prior art puts a book *in* the game; **MineScripture makes the world's own moments point to Scripture** — verified novel. Players join with an unmodified vanilla-compatible client; one jar on any Paper/Spigot server; roadmap: hosted key-proxy. Lead and close with the platform vision: **Minecraft is the proof of concept, not the product — MineScripture is an AI narrative layer that can eventually bring meaningful, Scripture-centered moments to many kinds of virtual worlds.** *Minecraft is our first world, not our last.*
- **Video (feel it before explaining it):** 0:00 player alone, night arrives, monsters near, torch flickers — no text → 0:08 survives, sunrise → *"Some moments in games stay with us."* → first verse. Middle: montage — death→respawn centerpiece, **same-event-different-story A/B (the "why AI" proof), one Levity beat**, storm with two players → 30 s how-it-works: Story Memory → Gloo *interprets and recommends* → MineScripture verifies → YouVersion renders the Word verbatim (`/msc explain` on screen; both APIs named). Close: positioning line + repo card. Captions throughout.
- **Writeup — translate engineering into human impact.** Not "MomentProfileCache + retry-once on 401" but *"the verse is already there when the moment arrives, and Scripture moments survive network outages."* Structure: problem → narrative-layer vision → Story Memory + interpret/recommend/verify architecture (responsibility-boundary diagram) → **why-AI proof** → **privacy** ("persists gameplay milestones, not narrative profiles"; no PII sent) → **content boundary** (*every word of Scripture players read is verbatim YouVersion text; all other text is human-written, except clearly-separated AI quips on lighthearted moments, config-gated and guarded so generated text can never resemble Scripture*) → AI budget guard as product philosophy → challenges → **future improvements** → appendix: model-routing observations.

## Risks

| Risk | Mitigation |
|---|---|
| **5-day compression, single video day** | pre-agreed cut order; writeup written in parallel from R2; demo mode makes technical shots repeatable; rehearsal Mon evening; R3 ends with a rough cut so R4 is edit-only |
| YouVersion key delay (not yet obtained) | first task today; key issuance is instant per verified facts; fallback pool + BSB public domain means no licensing blocker |
| Gloo latency / rate limits / token burn | main thread never blocked; sudden path hard-capped at 2.5 s → fallback; sliding-window context; AiBudgetGuard (10/player/hr, 100/server/hr) |
| AFK farms / grinders drain the AI budget | per-event cooldowns (low_health: 10 min) + repeat-event collapse into aggregates + unchanged-story check, all **before** the budget gate |
| Sudden-event wait feels like lag | death absorbs the wait in the respawn screen; diamonds get a deliberate one-beat delay (reads as reverence); 2.5 s hard cap → instant fallback |
| Corrupted/hallucinated verse refs | RefNormalizer (multi-format → canonical) + YouVersion 200/404 resolution + next-candidate retry + fallback chain — display never blocks |
| Valid-but-mismatched verse (Gen 1:1 on a death) | CandidateScorer semantic-fit penalty → better candidate wins; scoring, not hard filtering |
| Cold-start nonsense | curated per-event defaults until real story exists |
| Verse repetition | seen_refs to Gloo + session anti-repeat in RefValidator; open-canon recommendations give natural variety |
| Levity misfires / AI quip quality | tone gate on eligible comedic causes only + separate cooldown + LevityGuard (length cap, citation-pattern rejection) + curated-pool fallthrough + `levity_ai: false` shipped default + `levity: false` master off-switch |
| Verse spam | milestone flags, vein debounce, cooldowns, session cap, death-spam guard, budget guard |
| YouVersion licensing surprise | default BSB (public domain); fallback pool prefetched; NIV requested R0; AI-clause question posted R0 |
| Secrets in public repo | empty-key config + env vars + .gitignore R0 + history sweep at freeze |
| Curation eats an evening | AI-generates both JSON assets tonight (rule 3); humans only review 30 min R1 morning |
| Video under-scoped | full day reserved + R4 morning edit; script + rehearsal R2 evening; GUI Scale 3–4; captions; deterministic demo mode |

## Verification

- Gates R0–R2 (each a runnable end-to-end check); R1's includes the unplugged-network test, the levity/cactus test, and the **post-death-interpretation check** (the verse shown at respawn reflects a story window that includes the death).
- JUnit on Bukkit-free `TriggerPolicy` (cooldown/debounce/budget math, tone gating, **AFK-grinder scenario: 40 rapid low-health events → at most one Gloo call**) + `RefNormalizer` (all three input formats, **alias dictionary: Psalm/Psalms/Psa, Rev/Revelation/Revelations, numbered-book variants**, garbage rejection) + `CandidateScorer` (semantic-fit penalty, novelty math, fallback chain) + `LevityGuard` (length cap, verse-citation rejection, curated fallthrough) + **sudden-path timeout (future exceeds 2.5 s → FallbackPool result, never a stale profile)**.
- R2 real playtest = product verification (sacred when sacred, light when light).
- Pre-submit (R4): fresh-clone build, `run-server.sh` from scratch, vanilla client joins, 3 live triggers, links checked incognito.

## Locked decisions (was "Open items")

1. ✅ Name: **MineScripture**. Repo `minescripture`, package `dev.minescripture`, admin command `/msc` (alias `/minescripture`), env vars `MSC_*`.
2. ✅ `tradition` default: `broad_christian` — "recommend Scripture based on the human moment and biblical themes, without applying a denominational lens"; mapped to Gloo's accepted enum at request time; admin-configurable.
3. ✅ `/msc demo <event>` deterministic demo mode: in scope.
4. ✅ AI-generated humor: in scope, `levity_ai` config flag, shipped default off, demo config on.
5. ✅ Scope: full rev 6 MVP (journal book + stretch window), with pre-agreed cut order.
6. ✅ Submit target Wed 7/30; Fri 7/31 emergency buffer.
7. ✅ Build approach: R0 build night — Claude Code (Fable 5, multiple agents as needed) builds most/all of the MVP tonight under the Development rules; JSON assets generated first.
8. ✅ Timing architecture: two-path design (predictable = cache-first async; sudden = fresh interpretation, 2.5 s bounded off-thread wait → fallback).
9. ⏳ Still open (R0 forum posts): YouVersion AI-clause answer, exact deadline hour, max team size, NIV fast-track.

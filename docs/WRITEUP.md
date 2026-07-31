# MineScripture — technical writeup

**An AI narrative layer for virtual worlds that recognizes meaningful moments and connects them with Scripture.**

*Gloo × YouVersion — "Scripture in New Frontiers"*

---

## The problem

More than 170 million people play Minecraft every month. For a generation that
spends its formative hours inside virtual worlds, that is not a game statistic —
it is where a large share of their meaningful experiences now happen. They build
things there, lose things there, and stay up too late there with friends.
Meanwhile, Scripture engagement among the same generation continues to fall.

The usual response is to bring an app to them: a Bible reader, a devotional feed,
a plugin that puts a readable book *inside* the game. Each of those asks the same
thing — stop playing, and go read something.

We asked a different question. What if the world's own moments became the
invitation?

## What we built

MineScripture is a server-side Paper plugin. Players join with an unmodified
Minecraft client and simply play. When something meaningful happens — surviving a
first night, finding diamonds after an hour underground, dying with everything
you were carrying — a single verse arrives, timed to that moment, in the game's
own visual language.

```
You died after 47 minutes underground, having just found diamonds.

  Because of the loving devotion of the Lord we are not consumed,
  for His mercies never fail. They are new every morning;
  great is Your faithfulness!
  — Lamentations 3:22-23 (BSB)
```

No menus. No second screen. Nothing to install on the player's machine. One jar
on any Paper or Spigot server, and the world starts speaking.

**Minecraft is the proof of concept, not the product.** The thing we actually
built is a narrative layer: a system that watches a stream of events, decides
which ones carry weight, understands what they mean in the context of a person's
session, and answers with Scripture. Nothing about that pipeline is specific to
Minecraft. The event source is the only part that is.

## The architecture, and the boundary that defines it

**Minecraft detects the event. Gloo understands the moment and recommends
Scripture. Java controls the gate. YouVersion provides every word of Scripture
the player reads.**

Gloo expands the search space; our code controls the gate. We do not reinterpret
Gloo's recommendation. We verify that each recommended reference is valid,
retrievable from YouVersion, appropriate for presentation, and consistent with
the player's experience — avoiding repetition and preferring better contextual
matches.

```
Minecraft event
      │
      ▼
Session Story Memory   ephemeral: last ~8 significant events + aggregates
      │
      ▼
Gloo AI Studio  ──▶  resonance · emotional arc · emphasis · tone
      │
      └──────────▶  2–4 ranked Scripture references (all of Scripture in range)
                          │
                          ▼
                  normalize → verify → score → never fail
                          │
                          ▼
                  YouVersion ──▶ verbatim text ──▶ player
```

That boundary is the whole design. It lets us hand the AI the genuinely hard
problem — *what does this moment mean, and what Scripture speaks to it* — while
guaranteeing in code that whatever reaches a player is a real passage, rendered
verbatim from YouVersion, that they have not just seen.

## Why this needs an AI

The fair challenge to a project like this is: couldn't you just map events to
themes with a lookup table? Four things make the answer no.

**The same event in different stories deserves different Scripture.** A player who
dies having lost everything is in a moment of loss, and the emphasis is comfort.
A player who dies seconds after finally finding diamonds is in a moment of
frustrated perseverance. Same event, same game, opposite pastoral need. A static
map cannot see the difference, because the difference is not in the event — it is
in the story around it.

**Arcs evolve.** Because Gloo re-interprets with the updated story after every
moment, the third verse of a session knows about the first two. Loss can become
restoration, and restoration can become gratitude.

**Tone is judgment, not classification.** Some deaths are sad. Some deaths are
funny — a cactus, a fall into your own farm pit. Knowing reverence from comedy is
exactly the kind of call a rule table makes badly and a language model makes well.

**The canon is bigger than any list we could write.** Gloo recommends from all of
Scripture. Our curated pool of forty references exists as a safety net, not as
the menu. Over a long-running server, that difference is the difference between a
narrative layer and a rotation of the same twelve famous verses.

That last point is also the product argument. When MineScripture expands beyond
Minecraft, the recommendation layer is what scales.

## Engineering the moment

### The verse must answer *this* moment

Our first architecture cached an interpretation ahead of time and served it
instantly. It was fast, and it was subtly wrong: a reading computed before a
death cannot know about the death. Serving it at respawn would have quietly
destroyed the exact claim the project rests on.

So events split by predictability. Predictable moments — nightfall, sleep, a
shared storm — serve instantly from cache and restock in the background.
**Sudden** moments — death, diamonds, a near-miss — build a fresh interpretation
whose story already contains the event that just fired, and wait for it under a
strict 2.5 second cap on a background thread. If Gloo answers in time, the player
gets a reading of the moment they just lived. If it does not, a curated verse
arrives instead, and nothing about the experience stutters.

Death turned out to have a natural window: the respawn screen. The player is
already waiting. The verse lands as they return to the world.

This rule is enforced structurally rather than by convention. When a sudden
moment is granted no AI call — because the budget is exhausted, or because
nothing in the story has changed — the code presents a curated default rather
than a stale cached reading. There is no path through the system that shows a
pre-event interpretation for a post-event moment.

### Scripture moments survive network outages

At startup, every reference in the curated pool is fetched once from YouVersion
and written to disk. From that point on, the common moments cost no network
calls, and a total outage still produces real Scripture rather than an error.

The failure chain runs: best-scoring reference → an unseen curated default → any
curated default → disk cache. The player never learns that anything went wrong.
The worst case is a good verse instead of a perfect one.

### Sacred, not spammy

A verse that arrives too often stops being a moment and becomes a notification.
Restraint is not a setting here; it is most of the code.

Every candidate passes milestone flags, per-event debounce, per-event and global
cooldowns and a session cap before an AI call is even considered — and then an
explicit budget gate of 10 interpretations per player per hour and 100 per server
per hour. Over budget, only the rarest moments still reach the AI; everything
else serves from cache or the curated pool.

But restraint has to mean pacing rather than silence, and we learned that the
hard way. An early version answered a run of quick deaths with a fifteen-minute
blackout. Play-testing made the flaw obvious: a genuinely bad night — nine deaths
in ten minutes, which is simply what exploring at night looks like — produced one
verse and then nothing, and the silence read as the plugin having broken. Deaths
now widen the gap each time instead: sixty seconds, then a hundred and five, then
three minutes, capped at five and reset after ten quiet ones. Repeats keep their
verse but lose the title treatment. A player having an awful night is still
spoken to; they are simply not spoken over.

The *ordering* is the interesting part. Minecraft players do strange things — one
of the most common is standing in a mob grinder, taking damage every few seconds,
while away from the keyboard. To a naive system, that is hundreds of near-death
experiences. If the budget gate came first, twenty minutes of afk farming would
consume the entire hourly allowance, and the player's real death that evening
would get a generic verse. So cooldowns and repeat-collapsing both run ahead of
the budget guard. Mechanical repetition is retired long before it can cost a
token, and it accumulates as an aggregate — *this player has taken forty hits* —
rather than as forty separate moments.

The budget guard is the last line of defence, not the first. That ordering is
also the philosophy: the question is never "can we afford this call," it is "does
this moment deserve one."

### Making a language model's output safe to use

Two problems recur when a model's output feeds a lookup.

The first is naming. The same model will write `Psalm 23:1`, `Psalms 23:1` and
`Psa 23:1` for the same verse, and `Revelations 21:4` for a book that has no
plural form. A normalizer with a 66-book alias dictionary — singular and plural,
abbreviations, roman numerals, common misnamings — resolves all of them to one
canonical internal form. Prompt instructions reduce this variance; they do not
remove it, and a lookup that trusts them will fail on a Sunday afternoon for
reasons nobody can reproduce.

The second is existence. A recommended reference might not be a real passage. We
resolve that against YouVersion itself: a 200 accepts, a 404 moves to the next
candidate. The passages API doubles as our source-of-truth validator, and
validating a reference warms its text into the cache as a side effect, so the
verification pass pays for the fetch that follows.

What survives is then *scored* rather than filtered — semantic fit against the
interpretation, a penalty for anything seen today, a bonus for the unseen,
contextual fit for the event, and a reliability bonus for references that have
resolved before. Genesis 1:1 recommended for a death does not get rejected; it
simply loses to a passage about mercy. Scoring rather than filtering is what
keeps a recommendation system from collapsing into a whitelist.

### Humor with the same integrity

When Gloo classifies a moment as lighthearted, **it writes the line itself**. A
curated human-written pool exists behind that, but as a safety net rather than
the default — asking a model to recognise that a moment is funny and then having
a human supply the joke would waste the judgement we went to the trouble of
getting. The quip travels inside the same interpretation response, so it costs no
extra call and no extra latency.

Which source produced a given line is recorded rather than inferred. An earlier
version of the admin panel reported "AI-generated" whenever the feature was
switched on, which would have been a lie in exactly the case that matters — a
quip the guard had rejected and quietly replaced.

The boundary is absolute and enforced in code. A validator rejects any generated
line that runs long, contains quotation marks, contains a chapter-and-verse
pattern, or contains a Bible book name followed by a number. Anything rejected
falls through to the curated pool. Humor is presented in a visually distinct
format with no verse frame and no reference line.

Serious moment → always Scripture, verbatim from YouVersion. Comedic moment →
humor that can never be mistaken for Scripture. The two never mix.

## How this reaches people

The instinct is to ask how many players one server can hold. That is the wrong
axis. Minecraft communities are not a handful of enormous servers; they are
hundreds of thousands of small ones, and the ones this is for are smaller still —
a youth group with fifteen regulars, a family server, a Christian community of a
few dozen. Reach here is servers multiplied by players, not players per server.

That is what makes the deployment story the impact story. One jar, dropped into a
server an admin already runs, and every player on it is included from the next
restart — with no client install, no launcher, no per-person setup, and nothing
asked of the people it is meant to reach. A thousand community servers of thirty
players is thirty thousand people, and it grows by adoption rather than hardware.

There is a real ceiling, and it is worth naming precisely: **the AI budget, not
the player count.** The default allows a hundred interpretations per server per
hour, which is generous for twenty players and thin for two hundred. Past that
point most moments serve from the curated pool — still Scripture, still timed to
the moment, but chosen locally rather than interpreted.

That limit is a cost control rather than a capacity one. Every interpretation
spends the administrator's own Gloo credits, so the shipped default is
deliberately conservative: it is not our money, and a plugin that quietly runs up
someone's bill has failed them regardless of what it put on screen. A server that
wants fuller coverage raises the number.

Which points at the one piece of infrastructure this design still wants. Today an
administrator needs their own YouVersion and Gloo credentials, and for a youth
pastor with a Minecraft server that is a real barrier — the same barrier, in
miniature, that keeps this kind of thing from reaching the people it is for. A
hosted key proxy removes it, and turns out to be the same artifact as the service
that would let other games call this at all. Building it once answers both.

## Privacy

Story Memory is ephemeral gameplay context, not a player profile. MineScripture
keeps only the recent in-game events needed to interpret the current session, and
clears them at logout.

**MineScripture persists gameplay milestones, not narrative profiles.** The only
data that survives a session is the same kind of flag vanilla Minecraft already
keeps: whether you have joined before, whether you have found diamonds, whether
you have muted the plugin.

**No personally identifying information is sent to AI services.** What Gloo
receives is anonymous gameplay context — event names, session statistics, and
which verses have already been shown. No usernames, no coordinates, no chat
messages.

## What was hard

**The temporal trap.** Described above: the cache-first design that would have
served pre-death readings at respawn. It was caught by reasoning about the demo
we wanted to film — the same-event-different-story comparison — and realising the
architecture could not actually produce it.

**Models do not follow formatting instructions reliably.** Our system prompt asks
for strictly valid raw JSON with no markdown fences. On the first live call, Gloo
returned exactly the right JSON wrapped in exactly the fences we had forbidden.
Because we had kept a lenient extractor that pulls the first balanced object out
of whatever arrives, this cost nothing. Had we trusted the instruction, every
interpretation would have failed and silently fallen back to curated verses — and
the failure would have looked, from the outside, like an AI that had nothing to
say.

**Small regex decisions have theological consequences.** Our alias dictionary
rewrites leading roman numerals so `I John` resolves correctly. An early version
applied that rule too eagerly and turned *Isaiah* into `1saiah`, silently
removing one of the most quoted books in Scripture from the system's reach. It
was caught by a test written specifically because that class of bug does not
announce itself.

**Infrastructure rots underneath you.** Our local test-server script broke when
PaperMC sunset the API version it used. Better to discover that on a build night
than on the day you are trying to record a video.

## Future improvements

- **Offline-first ranking** over an expanded local library, so even a total
  outage produces narrative-aware selection rather than curated defaults.
- **Tradition exploration** — the same moment interpreted across traditions, as a
  study of how emphasis shifts.
- **Opt-in persistent memory**, so a returning player's arc can span sessions
  rather than resetting — strictly opt-in, because the current guarantee that
  nothing narrative persists is a feature we are unwilling to weaken by default.
- **A hosted key proxy**, so a church or community server administrator can drop
  in the jar without creating developer accounts.
- **More worlds.** The narrative layer generalizes; Minecraft is the first world,
  not the last.

## Appendix: notes on the model layer

Requests use Gloo AI Studio's chat completions endpoint with `auto_routing`
enabled and a `tradition` parameter, at temperature 0.4. In our testing, routing
selected a current-generation frontier model, and interpretations returned well
within the 2.5 second budget the sudden path allows.

Every interpretation carries a `reasoning` field that is written to the server log
and never shown to a player. It exists for us, not for them — when a verse choice
surprises us, we can read why it was made. That log is also what makes
`/msc explain` possible: at any moment an administrator can see the full trace
from event, through interpretation and emphasis, to verification and the final
verbatim text.

The interpretation is asked for JSON with a fixed schema — resonance, emotional
arc, emphasis, tone, two to four ranked references, an optional quip on
lighthearted moments, and reasoning. Every field is validated on arrival: tones
outside the allowed set are coerced, quips on non-light moments are discarded,
reference lists are truncated, and anything unparseable falls back to a curated
interpretation. The AI is trusted for judgment. It is not trusted for format.

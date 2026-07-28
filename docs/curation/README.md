# Curation notes

How the two human-reviewed asset files were built, and the rules a reviewer
should apply when changing them.

## `fallback.json` — 40 references

The curated pool serves three jobs at once, which is why its metadata matters
more than its size:

1. **The never-fail chain.** When Gloo is unreachable or every recommendation
   fails verification, these are the verses that reach the player.
2. **Cold start.** Before a player has a story, there is nothing to interpret.
   Each event carries a default interpretation so the first moment of a session
   is still a considered one.
3. **The semantic-fit table.** Each entry's `themes` and `tone` are what
   `CandidateScorer` scores Gloo's open-canon recommendations against. This is
   why a reference can be in the pool *and* recommended by the AI — the pool is a
   vocabulary, not a whitelist.

**Rules for editing:**

- Store **references only, never verse text.** Text always comes from YouVersion
  at runtime. A reviewer who pastes verse text into this file has broken the
  project's central compliance guarantee.
- Spans of **1–3 verses**. `RefNormalizer` rejects anything longer, so a 4-verse
  entry would fail the asset test at build time.
- `themes` should be the words an interpreter would plausibly use for *emphasis*
  — `comfort`, `perseverance`, `gratitude` — because that is what they are
  matched against.
- `tone` must be one of: `solemn`, `warm`, `awe`, `calm`, `encouraging`, `light`.
- Every event needs at least three defaults so the anti-repeat logic has room.

**Review questions** for a human pass:

- Would this verse land badly for someone actually grieving? (Death entries
  especially — avoid anything that reads as explaining away loss.)
- Is any verse doing work out of context? Prefer passages whose meaning survives
  being read alone, because that is how they will be read.
- Is the frame line (`"You awaken at dawn."`) doing too much? Frames should set
  the moment, never interpret the verse for the player.

## `humor.json` — 15 quips

Served only when the interpretation classifies a moment as `light`, and only
after the levity cooldown. All entries are human-written.

**Rules for editing:**

- No chapter:verse patterns, no book name followed by a number, no quotation
  marks. `LevityGuard` enforces this and `AssetsTest` verifies every shipped quip
  passes its own guard — so an entry that breaks the rule fails the build.
- Bare biblical names are welcome. Jonah, Daniel, Jericho and Balaam's donkey are
  where the warmth lives. What is forbidden is the *shape* of a citation.
- Aim for dry and observational. The rule of thumb: it should sound like a
  friend watching over your shoulder, not a church bulletin pun.
- `causes` tags let a quip match the actual death — `cactus`, `fall`, `lava`,
  `drown`, `mob`, `farm`, `starve`. Untagged quips still serve as general
  fallbacks.

**The line we hold:** the humor is *about* the player's mishap, never about
Scripture. A joke whose punchline requires Scripture to be silly is out, however
funny.

## The interpretation prompt

Lives in `MomentInterpreter.SYSTEM_PROMPT`. It is version-controlled with the
code rather than as data, because its shape and the parser's expectations must
change together.

Design decisions worth preserving:

- **It asks for judgment, not text.** The model interprets and recommends; it is
  told explicitly that it never writes Scripture shown to players.
- **The tone enum is listed exhaustively**, with a note that anything else is
  discarded — cheaper than coercion after the fact, though we coerce anyway.
- **Quip rules are strict on shape, loose on content.** No digits, no book names,
  no quotes, dry rather than punning — but the model chooses the joke.
- **Format instructions are a request, not a guarantee.** The prompt asks for raw
  JSON with no markdown fences. The first live response arrived fenced anyway.
  The lenient parser stays. Never remove it because the prompt "handles it."

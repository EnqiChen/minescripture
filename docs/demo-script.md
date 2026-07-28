# Demo script — the ≤3 minute video

**Principle: let them feel it before you explain it.** The first forty seconds
contain no architecture. Judges should want to know how it works before we tell
them.

Target length **2:50**. Captions throughout (many judges watch muted).

## Before recording

- [ ] **GUI Scale 3 or 4** in Minecraft video settings — the single most common
      way a plugin demo becomes unreadable at 1080p
- [ ] 1080p60, OBS, game audio low under voiceover
- [ ] Brightness up; night scenes must still read on a laptop screen
- [ ] `levity: true` **and** `levity_ai: true` in the demo config
- [ ] Clear the passage cache before filming *nothing* — leave it warm, so
      timing on camera matches the real player experience
- [ ] Hide coordinates/debug overlay (F3 off)
- [ ] Second account logged in for the fellowship and storm beats

## Staged world

| Location | Purpose |
|---|---|
| Spawn on a vista | Opening shot, sunrise |
| Small hut with bed | `sleep` beat |
| Prepared cave with diamonds ~40s from spawn | `found_diamonds`, and the A/B setup |
| Lava pool near the diamonds | The death that follows the diamonds |
| Cactus in the open | The levity beat |
| Wolf + bones | `taming` |
| Bread in inventory | `eating_bread` |

## Shot list

**0:00–0:08 — cold open, no text.**
Player alone as night falls. Distant zombie sounds. Torch flicker. No UI, no
verse. Just the feeling of being small in a big dark world.

**0:08–0:20 — the first verse.**
Sunrise. They survived. Voiceover: *"Some moments in games stay with us."*
The first verse arrives on the actionbar as the sun clears the horizon.
Hold on it. Let it breathe. Do not cut early.

**0:20–0:35 — the death centerpiece.**
Player deep underground, finds diamonds — the title beat fires. Seconds later,
lava. Death screen. Respawn — and **"You awaken at dawn."** with the verse.
This is the emotional center of the video. It gets the most screen time.

**0:35–1:05 — the "why AI" proof (the most important 30 seconds).**
Two clips side by side or back to back, same event, different story:

- Death after losing everything → emphasis **comfort**
- Death seconds after finding diamonds → emphasis **perseverance**

On-screen text: *Same event. Different story. Different Scripture.*
Voiceover: *"A lookup table cannot do this. It doesn't know what your last
hour looked like."*

Use `/msc demo player_death` for repeatability if a live take will not cooperate —
the demo path runs the real pipeline, only the story is scripted.

**1:05–1:20 — levity.**
Player walks into a cactus. Beat. A dry one-liner appears in a visibly different
style. On-screen label: *AI-recognized tone: light.*
Voiceover: *"It also knows when a moment isn't sacred."*

**1:20–1:35 — together.**
Thunderstorm, two players near each other. The fellowship verse arrives for both.
Shows the layer is not single-player.

**1:35–2:15 — how it works (only now).**
Clean diagram, 4 beats, narrated:

1. **Story Memory** — the last few things that happened to you, and nothing more
2. **Gloo AI Studio** interprets the moment and recommends Scripture
3. **MineScripture verifies** — real passage, not seen recently, fits the moment
4. **YouVersion** renders the Word, verbatim

Cut to `/msc explain` on screen showing a real trace. Both API names visible.
Voiceover names both partners explicitly.

**2:15–2:35 — the vision.**
*"Minecraft is the proof of concept, not the product. MineScripture is a
narrative layer that can bring Scripture-centered moments to many kinds of
virtual worlds."*
Consider a brief non-Minecraft visual suggestion here — even abstract.

**2:35–2:50 — close.**
Positioning line. Repo card. *AI understands the moment. Scripture provides the
meaning.*

## Voiceover notes

- Slow down. The instinct is to rush; the subject rewards calm.
- Do not narrate over the first verse. Silence sells it.
- Say "Gloo AI Studio" and "YouVersion" clearly, at least once each.

## What must survive an edit down

If time runs long, cut in this order: the fellowship beat, the vision B-roll, the
levity beat. **Never cut**: the death centerpiece, the same-event-different-story
comparison, or the four-beat architecture explanation.

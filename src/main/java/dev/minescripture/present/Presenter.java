package dev.minescripture.present;

import dev.minescripture.config.MineScriptureConfig;
import dev.minescripture.scripture.Passage;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * The only UI surface: actionbar | chat | title. No toasts, no hover/click,
 * no resource packs. The power is timing, not chrome.
 *
 * Tiers:
 *  - MINOR (bread, sleep): actionbar, vanilla ~3 s duration.
 *  - STANDARD: 3-line chat block (gray-italic frame / white verse / gold
 *    attribution) — stays in history for /verse replay.
 *  - MAJOR (respawn, diamonds): title + subtitle on the strict 5-second cycle
 *    (10 ticks in / 70 stay / 20 out — no screen trapping) + the chat block.
 *  - LEVITY: one visually distinct light line — no frame, no attribution,
 *    can never be mistaken for Scripture.
 */
public final class Presenter {

    // Long enough to actually read a reference, short enough that the screen is
    // never trapped. Play-testing found 3.5s too brief to take anything in.
    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(500),   // 10 ticks in
            Duration.ofMillis(6000),  // 120 ticks stay
            Duration.ofMillis(1000)); // 20 ticks out

    private static final Sound CHIME = Sound.sound(
            Key.key("block.note_block.chime"), Sound.Source.MASTER, 0.5f, 1.2f);

    private final MineScriptureConfig config;

    public Presenter(MineScriptureConfig config) {
        this.config = config;
    }

    public void showMinor(Player player, Passage passage) {
        player.sendActionBar(Component.text()
                .append(Component.text(passage.text() + "  ", NamedTextColor.WHITE))
                .append(Component.text("— " + attribution(passage), NamedTextColor.GOLD))
                .build());
        chime(player);
    }

    public void showStandard(Player player, String frame, Passage passage) {
        chatBlock(player, frame, passage);
        chime(player);
    }

    public void showMajor(Player player, String title, String frame, Passage passage) {
        player.showTitle(Title.title(
                Component.text(title, NamedTextColor.WHITE),
                Component.text(attribution(passage), NamedTextColor.GOLD),
                TITLE_TIMES));
        chatBlock(player, frame, passage);
        chime(player);
    }

    /** Distinct light format: single italic line, no frame, no gold attribution. */
    public void showLevity(Player player, String quip) {
        player.sendMessage(Component.text()
                .append(Component.text("✦ ", NamedTextColor.YELLOW))
                .append(Component.text(quip, NamedTextColor.YELLOW, TextDecoration.ITALIC))
                .build());
    }

    /** The 3-line block; also reused verbatim by /verse replay. */
    public void chatBlock(Player player, String frame, Passage passage) {
        player.sendMessage(Component.empty());
        if (frame != null && !frame.isBlank()) {
            player.sendMessage(Component.text(frame, NamedTextColor.GRAY, TextDecoration.ITALIC));
        }
        player.sendMessage(Component.text(passage.text(), NamedTextColor.WHITE));
        player.sendMessage(Component.text("— " + attribution(passage), NamedTextColor.GOLD));
    }

    private String attribution(Passage passage) {
        String translation = passage.translation().isBlank() ? "" : " (" + passage.translation() + ")";
        return passage.display() + translation;
    }

    private void chime(Player player) {
        if (config.chime) {
            player.playSound(CHIME);
        }
    }
}

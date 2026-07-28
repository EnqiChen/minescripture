package dev.minescripture.command;

import dev.minescripture.config.MineScriptureConfig;
import dev.minescripture.journal.BookWriter;
import dev.minescripture.journal.SessionJournal;
import dev.minescripture.present.MomentPresenter;
import dev.minescripture.trigger.PlayerStateManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** Player-facing: /verse [why|mute|unmute|translation|journal|link]. */
public final class VerseCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS =
            List.of("why", "mute", "unmute", "translation", "journal", "link");

    private final MineScriptureConfig config;
    private final MomentPresenter moments;
    private final PlayerStateManager playerState;
    private final SessionJournal journal;

    public VerseCommand(MineScriptureConfig config, MomentPresenter moments,
                        PlayerStateManager playerState, SessionJournal journal) {
        this.config = config;
        this.moments = moments;
        this.playerState = playerState;
        this.journal = journal;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.GRAY));
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> replay(player);
            case "why" -> why(player);
            case "mute" -> setMuted(player, true);
            case "unmute" -> setMuted(player, false);
            case "journal" -> journal(player);
            case "link" -> link(player);
            case "translation" -> translation(player);
            default -> player.sendMessage(Component.text(
                    "/verse [why|mute|unmute|translation|journal|link]", NamedTextColor.GRAY));
        }
        return true;
    }

    private void replay(Player player) {
        MomentPresenter.ShownMoment last = moments.lastShown(player.getUniqueId());
        if (last == null) {
            player.sendMessage(Component.text("No verse yet this session — play on.", NamedTextColor.GRAY));
            return;
        }
        if (last.isLevity()) {
            moments.presenter().showLevity(player, last.quip());
            return;
        }
        moments.presenter().chatBlock(player, last.frame(), last.passage());
        if (!last.passage().copyright().isBlank()) {
            player.sendMessage(Component.text(last.passage().copyright(), NamedTextColor.DARK_GRAY));
        }
    }

    private void why(Player player) {
        MomentPresenter.ShownMoment last = moments.lastShown(player.getUniqueId());
        if (last == null) {
            player.sendMessage(Component.text("Nothing to explain yet.", NamedTextColor.GRAY));
            return;
        }
        if (last.isLevity()) {
            player.sendMessage(Component.text(
                    "That moment read as lighthearted — so MineScripture answered in kind.",
                    NamedTextColor.GRAY, TextDecoration.ITALIC));
            return;
        }
        player.sendMessage(Component.text()
                .append(Component.text("This verse met a moment of ", NamedTextColor.GRAY, TextDecoration.ITALIC))
                .append(Component.text(last.interp().resonance(), NamedTextColor.WHITE, TextDecoration.ITALIC))
                .append(Component.text(" — an arc of ", NamedTextColor.GRAY, TextDecoration.ITALIC))
                .append(Component.text(last.interp().emotionalArc().replace('_', ' '),
                        NamedTextColor.WHITE, TextDecoration.ITALIC))
                .append(Component.text(", spoken with ", NamedTextColor.GRAY, TextDecoration.ITALIC))
                .append(Component.text(last.interp().emphasis(), NamedTextColor.WHITE, TextDecoration.ITALIC))
                .append(Component.text(".", NamedTextColor.GRAY, TextDecoration.ITALIC))
                .build());
    }

    private void setMuted(Player player, boolean muted) {
        playerState.setMuted(player.getUniqueId(), muted);
        player.sendMessage(Component.text(
                muted ? "MineScripture muted. /verse unmute to return." : "MineScripture unmuted.",
                NamedTextColor.GRAY));
    }

    private void journal(Player player) {
        List<SessionJournal.Entry> entries = journal.entries(player.getUniqueId());
        if (entries.isEmpty()) {
            player.sendMessage(Component.text("No verses in this session yet.", NamedTextColor.GRAY));
            return;
        }
        BookWriter.give(player, entries);
        player.sendMessage(Component.text("Your session verses, bound as a book.", NamedTextColor.GRAY));
    }

    private void link(Player player) {
        MomentPresenter.ShownMoment last = moments.lastShown(player.getUniqueId());
        if (last == null || last.isLevity()) {
            player.sendMessage(Component.text("No verse to link yet.", NamedTextColor.GRAY));
            return;
        }
        String url = "https://www.bible.com/bible/" + config.bibleId + "/" + last.passage().ref();
        player.sendMessage(Component.text("Read " + last.passage().display() + " on YouVersion: ",
                        NamedTextColor.GRAY)
                .append(Component.text(url, NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(url))));
    }

    private void translation(Player player) {
        player.sendMessage(Component.text(
                "Active translation: Berean Standard Bible (BSB), bible id " + config.bibleId
                        + ". Additional translations arrive as YouVersion approvals land (NIV requested).",
                NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}

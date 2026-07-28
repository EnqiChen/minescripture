package dev.minescripture.event;

import dev.minescripture.journal.MarkdownExporter;
import dev.minescripture.journal.SessionJournal;
import dev.minescripture.present.MomentPresenter;
import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;

/** Session lifecycle: story start (with reconnect grace), first_join, quit export. */
public final class LifecycleListener implements Listener {

    private final Plugin plugin;
    private final TriggerService service;
    private final SessionJournal journal;
    private final MomentPresenter momentPresenter;
    private final Path dataDir;

    public LifecycleListener(Plugin plugin, TriggerService service, SessionJournal journal,
                             MomentPresenter momentPresenter, Path dataDir) {
        this.plugin = plugin;
        this.service = service;
        this.journal = journal;
        this.momentPresenter = momentPresenter;
        this.dataDir = dataDir;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        service.onJoin(player.getUniqueId(), now);
        // Let the join screen settle before the very first moment (3 s).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                service.submit(TriggerContext.of(player.getUniqueId(), "first_join",
                        System.currentTimeMillis()));
            }
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        MarkdownExporter.export(dataDir, player.getUniqueId(), journal.entries(player.getUniqueId()));
        journal.clear(player.getUniqueId());
        momentPresenter.dropPending(player.getUniqueId());
        service.onQuit(player.getUniqueId(), now);
    }
}

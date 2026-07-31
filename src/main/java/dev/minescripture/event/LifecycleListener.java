package dev.minescripture.event;

import dev.minescripture.journal.MarkdownExporter;
import dev.minescripture.journal.SessionJournal;
import dev.minescripture.present.MomentPresenter;
import dev.minescripture.trigger.PlayerStateManager;
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
    private final PlayerStateManager playerState;

    public LifecycleListener(Plugin plugin, TriggerService service, SessionJournal journal,
                             MomentPresenter momentPresenter, Path dataDir,
                             PlayerStateManager playerState) {
        this.plugin = plugin;
        this.service = service;
        this.journal = journal;
        this.momentPresenter = momentPresenter;
        this.dataDir = dataDir;
        this.playerState = playerState;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        service.onJoin(player.getUniqueId(), now);
        // Let the join screen settle before the moment lands (3 s). A player who
        // has been here before is welcomed back rather than welcomed — first_join
        // fires once per player ever, so without this a returning player, which is
        // to say almost everyone almost always, arrives to silence.
        boolean returning = playerState.milestoneDone(player.getUniqueId(), "first_join");
        String welcome = returning ? "rejoin" : "first_join";
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                service.submit(TriggerContext.of(player.getUniqueId(), welcome,
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

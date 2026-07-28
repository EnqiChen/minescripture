package dev.minescripture.event;

import dev.minescripture.trigger.TriggerContext;
import dev.minescripture.trigger.TriggerService;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * found_diamonds: first-ever diamond ore break (persistent milestone flag in
 * the policy) with the 5-min vein debounce so a 1-8 block vein never fires twice.
 */
public final class MilestoneListener implements Listener {

    private final TriggerService service;

    public MilestoneListener(TriggerService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Material type = event.getBlock().getType();
        if (type != Material.DIAMOND_ORE && type != Material.DEEPSLATE_DIAMOND_ORE) {
            return;
        }
        GameMode mode = event.getPlayer().getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return;
        }
        service.submit(TriggerContext.of(event.getPlayer().getUniqueId(), "found_diamonds",
                System.currentTimeMillis()));
    }
}

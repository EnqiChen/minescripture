package dev.minescripture.journal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;

/** Turns the session journal into an in-game written book. */
public final class BookWriter {

    private static final int ENTRIES_PER_PAGE = 2;

    private BookWriter() {
    }

    public static void give(Player player, List<SessionJournal.Entry> entries) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(Component.text("Session Verses"));
        meta.author(Component.text("MineScripture"));
        meta.addPages(pages(entries).toArray(Component[]::new));
        book.setItemMeta(meta);
        player.getInventory().addItem(book);
    }

    private static List<Component> pages(List<SessionJournal.Entry> entries) {
        List<Component> pages = new ArrayList<>();
        Component page = Component.text("Verses of this session", NamedTextColor.DARK_GRAY)
                .append(Component.newline()).append(Component.newline());
        int onPage = 0;
        for (SessionJournal.Entry entry : entries) {
            Component block = Component.text()
                    .append(Component.text(entry.text(), NamedTextColor.BLACK))
                    .append(Component.newline())
                    .append(Component.text("— " + entry.refDisplay()
                            + (entry.translation().isBlank() ? "" : " (" + entry.translation() + ")"),
                            NamedTextColor.DARK_GRAY, TextDecoration.ITALIC))
                    .append(Component.newline()).append(Component.newline())
                    .build();
            page = page.append(block);
            if (++onPage == ENTRIES_PER_PAGE) {
                pages.add(page);
                page = Component.empty();
                onPage = 0;
            }
        }
        if (onPage > 0 || pages.isEmpty()) {
            pages.add(page);
        }
        return pages;
    }
}

package com.questjournal.client.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.questjournal.client.ClientJournal;
import com.questjournal.client.ui.Names;
import com.questjournal.client.ui.Theme;
import com.questjournal.client.ui.Ui;
import com.questjournal.client.ui.UiList;
import com.questjournal.core.model.QuestDefinition;
import com.questjournal.core.model.RegistryKind;
import com.questjournal.core.util.Ids;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The registry browser. Lists everything registered by ANY installed mod (items, entities, blocks) plus the server's
 * dimensions, searchable by name, id or mod namespace, and always shows the real registry id. Because it reads Forge's
 * registries directly, no per-mod integration code is ever needed. A typed id can also be used (for content of mods
 * that are not installed on this machine); such references are kept and flagged by validation.
 */
public final class PickerScreen extends Screen {
    public record Entry(String id, String name, String namespace, String blob, ItemStack icon) {
    }

    private static final Map<RegistryKind, List<Entry>> CACHE = new EnumMap<>(RegistryKind.class);

    /** Frees the (potentially large) registry listings when the editor is closed. */
    public static void clearCaches() {
        CACHE.clear();
    }

    public static List<Entry> entriesFor(RegistryKind kind) {
        return CACHE.computeIfAbsent(kind, PickerScreen::build);
    }

    private static List<Entry> build(RegistryKind kind) {
        List<Entry> out = new ArrayList<>();
        switch (kind) {
            case ITEM:
                for (ResourceLocation rl : ForgeRegistries.ITEMS.getKeys()) {
                    Item it = ForgeRegistries.ITEMS.getValue(rl);
                    if (it != null && it != Items.AIR) {
                        add(out, rl.toString(), it.getDescription().getString(), rl, new ItemStack(it));
                    }
                }
                break;
            case ENTITY:
                for (ResourceLocation rl : ForgeRegistries.ENTITY_TYPES.getKeys()) {
                    add(out, rl.toString(), ForgeRegistries.ENTITY_TYPES.getValue(rl).getDescription().getString(), rl, null);
                }
                break;
            case BLOCK:
                for (ResourceLocation rl : ForgeRegistries.BLOCKS.getKeys()) {
                    Block b = ForgeRegistries.BLOCKS.getValue(rl);
                    Item item = b == null ? Items.AIR : b.asItem();
                    add(out, rl.toString(), b == null ? rl.toString() : b.getName().getString(), rl, item == Items.AIR ? null : new ItemStack(item));
                }
                break;
            default:
                for (String id : EditorSession.dimensions) {
                    ResourceLocation rl = ResourceLocation.tryParse(id);
                    if (rl != null) {
                        add(out, id, Names.name(RegistryKind.DIMENSION, id), rl, null);
                    }
                }
        }
        out.sort(Comparator.comparing((Entry e) -> e.namespace()).thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static void add(List<Entry> out, String id, String name, ResourceLocation rl, ItemStack icon) {
        out.add(new Entry(id, name, rl.getNamespace(), (name + " " + id).toLowerCase(Locale.ROOT), icon));
    }

    /** Quests the editor knows about (for choosing prerequisites). */
    public static List<Entry> questEntries(String exceptId) {
        List<Entry> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (EditorSession.EQuest e : EditorSession.quests) {
            if (e.def != null && !e.draft && !e.def.id.equals(exceptId) && seen.add(e.def.id)) {
                out.add(questEntry(e.def));
            }
        }
        for (ClientJournal.CQuest c : ClientJournal.I.quests.values()) {
            if (!c.def.id.equals(exceptId) && seen.add(c.def.id)) {
                out.add(questEntry(c.def));
            }
        }
        out.sort(Comparator.comparing(Entry::id));
        return out;
    }

    private static Entry questEntry(QuestDefinition d) {
        return new Entry(d.id, d.title, Ids.namespaceOf(d.id), (d.title + " " + d.id).toLowerCase(Locale.ROOT), null);
    }

    // ------------------------------------------------------------------ screen

    private final Screen parent;
    private final String heading;
    private final List<Entry> all;
    private final String current;
    private final Consumer<String> onPick;
    private final boolean allowTyped;
    private final UiList<Entry> list = new UiList<>();
    private final List<Entry> filtered = new ArrayList<>();
    private EditBox search;
    private Theme t;
    private String lastQuery = null;
    private long lastClick;
    private int lastClickIndex = -1;
    private String note = "";

    public PickerScreen(Screen parent, String heading, List<Entry> entries, String current, Consumer<String> onPick, boolean allowTyped) {
        super(Component.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.all = entries;
        this.current = current == null ? "" : current;
        this.onPick = onPick;
        this.allowTyped = allowTyped;
    }

    @Override
    protected void init() {
        t = Theme.load();
        String keep = search == null ? "" : search.getValue();
        search = new EditBox(font, 10, 30, width - 20, 16, Component.literal("Search"));
        search.setMaxLength(100);
        search.setHint(Component.literal("Search by name, id or mod (e.g. gun, mymod:, minecraft:diamond)..."));
        search.setValue(keep);
        addRenderableWidget(search);
        setInitialFocus(search);
        lastQuery = null;
        int bw = Math.min(120, (width - 24) / 3);
        addRenderableWidget(Button.builder(Component.literal("Select"), b -> chooseSelected()).bounds(10, height - 24, bw, 18).build());
        if (allowTyped) {
            addRenderableWidget(Button.builder(Component.literal("Use typed id"), b -> useTyped()).bounds(14 + bw, height - 24, bw, 18).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width - 10 - bw, height - 24, bw, 18).build());
    }

    @Override
    public void tick() {
        search.tick();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void refilter() {
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (q.equals(lastQuery)) {
            return;
        }
        lastQuery = q;
        filtered.clear();
        String[] tokens = q.isEmpty() ? new String[0] : q.split("\\s+");
        for (Entry e : all) {
            boolean ok = true;
            for (String tk : tokens) {
                if (!e.blob().contains(tk)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                filtered.add(e);
            }
        }
        list.setItems(filtered);
        list.selected = -1;
        for (int i = 0; i < filtered.size(); i++) {
            if (filtered.get(i).id().equals(current)) {
                list.selected = i;
                list.ensureVisible(i);
                break;
            }
        }
    }

    private void chooseSelected() {
        Entry e = list.selectedItem();
        if (e == null) {
            note = "Click an entry first, or type a full id and use \"Use typed id\".";
            return;
        }
        onPick.accept(e.id());
        minecraft.setScreen(parent);
    }

    private void useTyped() {
        String id = Ids.normalizeResourceId(search.getValue());
        if (id == null) {
            note = "Type a full registry id such as mymod:my_item.";
            return;
        }
        onPick.accept(id);
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        Ui.rect(g, 0, 0, width, height, t.bg);
        Ui.rect(g, 0, 0, width, 24, t.panel);
        Ui.rect(g, 0, 0, 4, 24, t.accent);
        g.drawString(font, heading, 10, 8, t.gold, false);
        refilter();
        list.rowH = 24;
        list.setBounds(10, 52, width - 20, height - 52 - 30);
        Ui.rect(g, 10, 52, width - 20, height - 82, t.panel);
        list.render(g, t, mx, my, true, (gg, e, i, x, y, w, h, hover, sel) -> {
            if (sel) {
                Ui.rect(gg, x, y, w, h - 1, t.panelLight);
            } else if (hover) {
                Ui.rect(gg, x, y, w, h - 1, Theme.mix(t.panel, t.panelLight, 0.5f));
            }
            if (e.icon() != null) {
                gg.renderItem(e.icon(), x + 4, y + 4);
            }
            gg.drawString(font, Ui.clip(font, e.name(), w - 150), x + 26, y + 3, sel ? t.gold : t.text, false);
            gg.drawString(font, Ui.clip(font, e.id(), w - 100), x + 26, y + 13, t.dim, false);
            String ns = e.namespace();
            gg.drawString(font, ns, x + w - 6 - font.width(ns), y + 8, ns.equals("minecraft") ? t.dim : t.gold, false);
        });
        String count = filtered.size() + " of " + all.size();
        g.drawString(font, count, width - 12 - font.width(count), 12, t.dim, false);
        if (filtered.isEmpty()) {
            g.drawString(font, "No match. If the mod is not installed here, type the full id and use \"Use typed id\".", 16, 62, t.dim, false);
        }
        if (!note.isEmpty()) {
            g.drawString(font, note, 12, height - 36, t.bad, false);
        }
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (list.mousePressed(mx, my)) {
                return true;
            }
            int i = list.indexAt(mx, my);
            if (i >= 0) {
                long now = Util.getMillis();
                list.selected = i;
                if (i == lastClickIndex && now - lastClick < 350) {
                    chooseSelected();
                }
                lastClick = now;
                lastClickIndex = i;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return list.mouseScrolled(mx, my, delta) || super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return list.mouseDragged(my) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        list.mouseReleased();
        return super.mouseReleased(mx, my, button);
    }
}

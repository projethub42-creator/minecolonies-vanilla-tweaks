package fr.mcvanillatweaks.client.buildtool;

import com.ldtteam.structurize.Structurize;
import com.ldtteam.structurize.api.Utils;
import com.ldtteam.structurize.blocks.interfaces.ILeveledBlueprintAnchorBlock;
import com.ldtteam.structurize.blocks.interfaces.INamedBlueprintAnchorBlock;
import com.ldtteam.structurize.blocks.interfaces.IRequirementsBlueprintAnchorBlock;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintTagUtils;
import com.ldtteam.structurize.client.BlueprintHandler;
import com.ldtteam.structurize.client.ModKeyMappings;
import com.ldtteam.structurize.client.gui.WindowExtendedBuildTool;
import com.ldtteam.structurize.config.ClientConfiguration;
import com.ldtteam.structurize.network.messages.BuildToolPlacementMessage;
import com.ldtteam.structurize.network.messages.SyncPreviewCacheToServer;
import com.ldtteam.structurize.storage.ISurvivalBlueprintHandler;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.storage.SurvivalBlueprintHandlers;
import com.ldtteam.structurize.storage.rendering.RenderingCache;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.mojang.blaze3d.platform.InputConstants;
import fr.mcvanillatweaks.MineColoniesVanillaTweaks;
import fr.mcvanillatweaks.client.citizen.Gui;
import fr.mcvanillatweaks.client.citizen.IconButton;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.util.Lazy;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Replaces Structurize's build tool ({@code WindowExtendedBuildTool}, without a selection callback): the world and the
 * blueprint preview stay visible, with three panels around them.
 * <ul>
 * <li>At the bottom, the browser: the style (opens {@link PackScreen}), the path, the pack's top categories as item
 * tabs, then the folders or blueprints of the current category, three per row. Blueprints are grouped like Structurize
 * (levels of a leveled anchor, alternatives of a named anchor); a group with several alternatives or levels lists them
 * on the left.</li>
 * <li>On the right, the manipulator (rotate, move, mirror, up and down, the same moves and keys as Structurize), the
 * rotation, then place, cancel, preview settings and contents.</li>
 * <li>Placing goes through Structurize's survival handlers (MineColonies places huts, supply camps...), or the
 * "complete" / "pretty" modes in creative, chosen in a small list when there are several; it sends
 * {@code BuildToolPlacementMessage}. Cancel clears the preview like Structurize ({@code SyncPreviewCacheToServer}).</li>
 * </ul>
 * The current place in the browser is kept for the session, like Structurize's static state.
 */
public final class BuildToolScreen extends Screen
{
    private static final String PREVIEW = "blueprint";
    private static final int ROW = 20;
    private static final int GRID_ROWS = 2;
    private static final int TAB = 22;
    private static final int MANIPULATOR_WIDTH = 84;
    private static final int MANIPULATOR_HEIGHT = 182;
    private static final int SIDE_WIDTH = 112;
    private static final int TIP_TICKS = 200;

    // ------------------------------------------------------------------ session state, as Structurize's statics

    private static String statePack = "";
    /** Current folder ("" = only the top categories). */
    private static String statePath = "";
    /** Top category the path is in, for the selected tab. */
    private static String stateTab = "";
    /** Group and alternative shown on the left, or "". */
    private static String stateGroup = "";
    private static String stateAlt = "";
    private static final Map<String, CompletableFuture<List<StructurePacks.Category>>> CATEGORIES = new HashMap<>();
    private static final Map<String, CompletableFuture<List<Blueprint>>> BLUEPRINTS = new HashMap<>();
    /** Whether a folder only holds blueprints (from its parent's listing). */
    private static final Map<String, Boolean> TERMINAL = new HashMap<>();

    private enum Side
    {
        NONE, ALTERNATIVES, LEVELS
    }

    private enum Modal
    {
        NONE, PLACEMENT, SETTINGS
    }

    /** An entry of the browser grid. */
    private record Entry(@Nullable StructurePacks.Category folder, @Nullable String group, boolean back)
    {
    }

    private final int groundstyle;
    @Nullable
    private final Predicate<Blueprint> available;
    private final HolderLookup.Provider provider;

    private List<StructurePacks.Category> tabs = List.of();
    @Nullable
    private CompletableFuture<List<StructurePacks.Category>> tabsLoading;
    /** What the path shows, once loaded. */
    private List<StructurePacks.Category> folders = List.of();
    /** Blueprint groups of the path: group key, then alternative name, then levels. */
    private Map<String, Map<String, List<Blueprint>>> groups = Map.of();
    private boolean loaded;
    private Side side = Side.NONE;
    private Modal modal = Modal.NONE;
    private List<Placement> placements = List.of();
    private int gridScroll;
    private int ticks;
    private boolean showTip;

    private record Placement(Component name, BuildToolPlacementMessage.HandlerType type, String id)
    {
    }

    /**
     * A blueprint chooser (MineColonies' schematic analyzer opens the build tool with a callback): "place" hands the
     * blueprint to the callback with the replaced window instead of placing it.
     */
    @Nullable
    private java.util.function.BiConsumer<com.ldtteam.structurize.client.gui.WindowExtendedBuildTool, Blueprint> selection;
    @Nullable
    private com.ldtteam.structurize.client.gui.WindowExtendedBuildTool selectionWindow;

    BuildToolScreen selecting(final java.util.function.BiConsumer<com.ldtteam.structurize.client.gui.WindowExtendedBuildTool, Blueprint> callback,
      final com.ldtteam.structurize.client.gui.WindowExtendedBuildTool window)
    {
        this.selection = callback;
        this.selectionWindow = window;
        return this;
    }

    BuildToolScreen(final int groundstyle, @Nullable final Predicate<Blueprint> available, final HolderLookup.Provider provider, final boolean newPosition)
    {
        super(Component.translatable("item.structurize.sceptergold"));
        this.groundstyle = groundstyle;
        this.available = available;
        this.provider = provider;
        this.showTip = newPosition;
        final StructurePackMeta pack = StructurePacks.getSelectedPack();
        final String name = pack == null ? "" : pack.getName();
        if (!name.equals(statePack))
        {
            // Another style: start from its top, like Structurize.
            statePack = name;
            statePath = "";
            stateTab = "";
            stateGroup = "";
            stateAlt = "";
            CATEGORIES.clear();
            BLUEPRINTS.clear();
            TERMINAL.clear();
        }
        tabsLoading = name.isEmpty() ? null : StructurePacks.getCategoriesFuture(name, "");
        if (!statePath.isEmpty())
        {
            load(statePath);
        }
    }

    private static BlueprintPreviewData preview()
    {
        return RenderingCache.getOrCreateBlueprintPreviewData(PREVIEW);
    }

    // ------------------------------------------------------------------ browsing

    private void load(final String path)
    {
        loaded = false;
        folders = List.of();
        groups = Map.of();
        gridScroll = 0;
        if (Boolean.TRUE.equals(TERMINAL.get(path)))
        {
            BLUEPRINTS.computeIfAbsent(path, key -> StructurePacks.getBlueprintsFuture(statePack, key, provider));
        }
        else
        {
            CATEGORIES.computeIfAbsent(path, key -> StructurePacks.getCategoriesFuture(statePack, key));
        }
    }

    private void open(final String path)
    {
        statePath = path;
        stateGroup = "";
        stateAlt = "";
        side = Side.NONE;
        final int slash = path.indexOf('/');
        stateTab = path.isEmpty() ? "" : slash < 0 ? path : path.substring(0, slash);
        if (!path.isEmpty())
        {
            load(path);
        }
        else
        {
            loaded = false;
            folders = List.of();
            groups = Map.of();
        }
        rebuildWidgets();
    }

    private static String parent(final String path)
    {
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** Checks the loading folder or blueprint lists, as Structurize's window does every update. */
    private void poll()
    {
        if (tabsLoading != null && tabsLoading.isDone())
        {
            tabs = tabsLoading.getNow(List.of());
            for (final StructurePacks.Category category : tabs)
            {
                TERMINAL.put(category.subPath, category.isTerminal);
            }
            tabsLoading = null;
            rebuildWidgets();
        }
        if (loaded || statePath.isEmpty())
        {
            return;
        }
        final CompletableFuture<List<StructurePacks.Category>> categories = CATEGORIES.get(statePath);
        if (categories != null && !Boolean.TRUE.equals(TERMINAL.get(statePath)))
        {
            if (!categories.isDone())
            {
                return;
            }
            final List<StructurePacks.Category> list = categories.getNow(List.of());
            if (list.isEmpty())
            {
                // No sub-folder: Structurize looks for blueprints there instead.
                TERMINAL.put(statePath, true);
                load(statePath);
                return;
            }
            for (final StructurePacks.Category category : list)
            {
                TERMINAL.put(category.subPath, category.isTerminal);
            }
            folders = list;
            loaded = true;
            rebuildWidgets();
            return;
        }
        final CompletableFuture<List<Blueprint>> blueprints = BLUEPRINTS.get(statePath);
        if (blueprints != null && blueprints.isDone())
        {
            groups = group(blueprints.getNow(List.of()));
            loaded = true;
            // Coming back to the tool: show the group that was open, without changing the chosen blueprint.
            if (!stateGroup.isEmpty() && groups.containsKey(stateGroup))
            {
                showGroup(stateGroup, true);
                if (!stateAlt.isEmpty())
                {
                    showAlternative(stateAlt, true);
                }
            }
            rebuildWidgets();
        }
    }

    /** Structurize's grouping: levels of a leveled anchor together, then alternatives of a named anchor together. */
    private Map<String, Map<String, List<Blueprint>>> group(final List<Blueprint> blueprints)
    {
        final boolean creative = minecraft != null && minecraft.player != null && minecraft.player.isCreative();
        final Map<String, List<Blueprint>> byName = new LinkedHashMap<>();
        for (final Blueprint blueprint : blueprints)
        {
            if (!creative && BlueprintTagUtils.isInvisible(blueprint))
            {
                continue;
            }
            final BlockState anchor = blueprint.getBlockState(blueprint.getPrimaryBlockOffset());
            String name;
            if (anchor.getBlock() instanceof ILeveledBlueprintAnchorBlock leveled)
            {
                // Browsing can happen before a preview position has been set. Reading the
                // anchor level only needs its NBT, so use an origin until placement begins.
                final BlockPos previewPos = preview().getPos();
                final int level = leveled.getLevel(blueprint.getTileEntityData(
                  previewPos == null ? BlockPos.ZERO : previewPos, blueprint.getPrimaryBlockOffset()));
                final String withoutLevel = customName(blueprint, blueprint.getFileName().replace(Integer.toString(level), ""));
                name = withoutLevel.isEmpty() ? customName(blueprint, blueprint.getFileName()) : withoutLevel;
            }
            else
            {
                name = customName(blueprint, blueprint.getFileName());
            }
            byName.computeIfAbsent(name, key -> new ArrayList<>()).add(blueprint);
        }
        final Map<String, Map<String, List<Blueprint>>> result = new LinkedHashMap<>();
        for (final Map.Entry<String, List<Blueprint>> entry : byName.entrySet())
        {
            final Blueprint first = entry.getValue().get(0);
            final BlockState anchor = first.getBlockState(first.getPrimaryBlockOffset());
            final String key = anchor.getBlock() instanceof INamedBlueprintAnchorBlock ? anchor.getBlock().getDescriptionId() : entry.getKey();
            result.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static String customName(final Blueprint blueprint, final String fallback)
    {
        final Optional<String> tag = BlueprintTagUtils.getBlueprintTags(blueprint).getOrDefault(BlockPos.ZERO, List.of()).stream()
                                       .filter(t -> t.startsWith("name=")).findFirst();
        return tag.map(s -> s.substring(5)).orElse(fallback);
    }

    private static Blueprint first(final Map<String, List<Blueprint>> group)
    {
        return group.values().iterator().next().get(0);
    }

    private Component groupName(final String key)
    {
        final Blueprint first = first(groups.get(key));
        final BlockState anchor = first.getBlockState(first.getPrimaryBlockOffset());
        if (anchor.getBlock() instanceof INamedBlueprintAnchorBlock named)
        {
            return named.getBlueprintDisplayName();
        }
        return Component.literal(customName(first, first.getFileName()));
    }

    private boolean locked(final Blueprint blueprint)
    {
        return available != null && !available.test(blueprint);
    }

    private boolean selected(final Map<String, List<Blueprint>> group)
    {
        final Blueprint current = preview().getBlueprint();
        if (current == null)
        {
            return false;
        }
        for (final List<Blueprint> list : group.values())
        {
            if (list.contains(current))
            {
                return true;
            }
        }
        return false;
    }

    private void showGroup(final String key, final boolean reopening)
    {
        final Map<String, List<Blueprint>> group = groups.get(key);
        if (group == null)
        {
            return;
        }
        stateGroup = key;
        stateAlt = "";
        if (group.size() == 1 && first(group) != null && group.values().iterator().next().size() == 1)
        {
            side = Side.NONE;
            if (!reopening)
            {
                setBlueprint(first(group));
            }
            return;
        }
        if (group.size() > 1)
        {
            side = Side.ALTERNATIVES;
            return;
        }
        stateAlt = group.keySet().iterator().next();
        side = Side.LEVELS;
        if (!reopening || preview().getBlueprint() == null)
        {
            setBlueprint(first(group));
        }
    }

    private void showAlternative(final String alt, final boolean reopening)
    {
        final Map<String, List<Blueprint>> group = groups.get(stateGroup);
        final List<Blueprint> list = group == null ? null : group.get(alt);
        if (list == null || list.isEmpty())
        {
            return;
        }
        stateAlt = alt;
        if (!reopening || preview().getBlueprint() == null || list.size() == 1)
        {
            setBlueprint(list.get(0));
        }
        side = list.size() > 1 ? Side.LEVELS : Side.ALTERNATIVES;
    }

    private List<Blueprint> levels()
    {
        final Map<String, List<Blueprint>> group = groups.get(stateGroup);
        final List<Blueprint> list = group == null ? null : group.get(stateAlt);
        return list == null ? List.of() : list;
    }

    private void setBlueprint(final Blueprint blueprint)
    {
        preview().setBlueprint(blueprint);
        adjustToGroundOffset();
        rebuildWidgets();
    }

    /** Structurize's ground anchor offset for the build tool's ground style. */
    private void adjustToGroundOffset()
    {
        final Blueprint blueprint = preview().getBlueprint();
        if (blueprint == null)
        {
            return;
        }
        final int offset = switch (groundstyle)
        {
            case 2 -> BlueprintTagUtils.getGroundAnchorOffsetFromGroundLevels(blueprint, BlueprintTagUtils.getNumberOfGroundLevels(blueprint, 1));
            case 3 -> BlueprintTagUtils.getGroundAnchorOffsetFromGroundLevels(blueprint, BlueprintTagUtils.getNumberOfGroundLevels(blueprint, 3));
            default -> BlueprintTagUtils.getGroundAnchorOffset(blueprint, 1);
        };
        preview().setGroundOffset(offset - 1);
    }

    private List<Entry> entries()
    {
        final List<Entry> list = new ArrayList<>();
        if (statePath.isEmpty())
        {
            return list;
        }
        list.add(new Entry(null, null, true));
        for (final StructurePacks.Category folder : folders)
        {
            list.add(new Entry(folder, null, false));
        }
        for (final String key : groups.keySet())
        {
            list.add(new Entry(null, key, false));
        }
        return list;
    }

    // ------------------------------------------------------------------ manipulation, as Structurize

    private void move(final BlockPos offset)
    {
        if (preview().getBlueprint() != null)
        {
            preview().move(offset);
        }
    }

    private void forward(final int sign)
    {
        if (minecraft != null && minecraft.player != null)
        {
            move(BlockPos.ZERO.relative(sign > 0 ? minecraft.player.getDirection() : minecraft.player.getDirection().getOpposite()));
        }
    }

    private void sideways(final boolean right)
    {
        if (minecraft != null && minecraft.player != null)
        {
            move(BlockPos.ZERO.relative(right ? minecraft.player.getDirection().getClockWise() : minecraft.player.getDirection().getCounterClockWise()));
        }
    }

    private void rotate(final Rotation rotation)
    {
        if (preview().getBlueprint() != null)
        {
            preview().rotate(rotation);
        }
    }

    private void mirror()
    {
        if (preview().getBlueprint() != null)
        {
            preview().mirror();
        }
    }

    private void confirm()
    {
        final Blueprint blueprint = preview().getBlueprint();
        if (selection != null && blueprint != null)
        {
            selection.accept(selectionWindow, blueprint);
            return;
        }
        if (blueprint == null || minecraft == null || minecraft.player == null || minecraft.level == null || locked(blueprint))
        {
            return;
        }
        final List<ISurvivalBlueprintHandler> handlers = SurvivalBlueprintHandlers.getMatchingHandlers(blueprint, minecraft.level, minecraft.player,
          preview().getPos(), preview().getRotationMirror());
        if (!minecraft.player.isCreative())
        {
            if (handlers.isEmpty())
            {
                Utils.playErrorSound(minecraft.player);
                if (SurvivalBlueprintHandlers.getHandlers().isEmpty())
                {
                    minecraft.player.displayClientMessage(Component.translatable("structurize.gui.no.survival.handler"), false);
                }
                return;
            }
            if (handlers.size() == 1)
            {
                place(BuildToolPlacementMessage.HandlerType.Survival, handlers.get(0).getId());
                return;
            }
        }
        final List<Placement> list = new ArrayList<>();
        if (minecraft.player.isCreative())
        {
            list.add(new Placement(Component.translatable("structurize.gui.buildtool.complete"), BuildToolPlacementMessage.HandlerType.Complete, ""));
            list.add(new Placement(Component.translatable("structurize.gui.buildtool.pretty"), BuildToolPlacementMessage.HandlerType.Pretty, ""));
        }
        for (final ISurvivalBlueprintHandler handler : handlers)
        {
            list.add(new Placement(handler.getDisplayName(), BuildToolPlacementMessage.HandlerType.Survival, handler.getId()));
        }
        placements = list;
        modal = Modal.PLACEMENT;
        rebuildWidgets();
    }

    private void place(final BuildToolPlacementMessage.HandlerType type, final String id)
    {
        final Blueprint blueprint = preview().getBlueprint();
        final StructurePackMeta pack = StructurePacks.getStructurePack(statePack);
        if (blueprint == null || pack == null)
        {
            return;
        }
        new BuildToolPlacementMessage(type, id, statePack, pack.getSubPath(blueprint.getFilePath().resolve(blueprint.getFileName() + ".blueprint")),
          preview().getPos(), preview().getRotationMirror()).sendToServer();
        if (type == BuildToolPlacementMessage.HandlerType.Survival)
        {
            cancel();
        }
        else
        {
            modal = Modal.NONE;
            rebuildWidgets();
        }
    }

    /** Structurize's cancel: the preview is removed (and the server told), the browser goes back to the top. */
    private void cancel()
    {
        final BlueprintPreviewData data = RenderingCache.removeBlueprint(PREVIEW);
        if (data != null)
        {
            data.setBlueprint(null);
            data.setPos(BlockPos.ZERO);
            new SyncPreviewCacheToServer(data).sendToServer();
        }
        statePath = "";
        stateTab = "";
        stateGroup = "";
        stateAlt = "";
        onClose();
    }

    private void contents()
    {
        final BlueprintPreviewData data = preview();
        if (data.getBlueprint() != null && minecraft != null && minecraft.level != null)
        {
            minecraft.setScreen(new ContentsScreen(this, data.getBlueprint(), minecraft.level, BlueprintHandler.getInstance().getOptionalEntitiesForBlueprint(data)));
        }
    }

    private void switchPack()
    {
        if (minecraft != null)
        {
            // Back to the build tool afterwards, through Structurize's window (swapped for this screen again).
            final BlockPos pos = preview().getPos();
            minecraft.setScreen(new PackScreen(() -> new WindowExtendedBuildTool(pos, groundstyle, null, available, provider), pack -> true));
        }
    }

    // ------------------------------------------------------------------ geometry

    private int panelWidth()
    {
        return Math.min(380, width - 8);
    }

    private int panelX()
    {
        return (width - panelWidth()) / 2;
    }

    private int panelHeight()
    {
        return 58 + GRID_ROWS * ROW + 6;
    }

    private int panelY()
    {
        return height - panelHeight() - 4;
    }

    private int manipulatorX()
    {
        return width - MANIPULATOR_WIDTH - 4;
    }

    private int manipulatorY()
    {
        return Math.max(4, Math.min((height - MANIPULATOR_HEIGHT) / 2, panelY() - MANIPULATOR_HEIGHT - 4));
    }

    private int gridColumnWidth()
    {
        return (panelWidth() - 12 - 8) / 3;
    }

    private int sideRows()
    {
        return Math.max(1, (panelY() - 4 - 4 - 22) / ROW);
    }

    // ------------------------------------------------------------------ widgets

    @Override
    protected void init()
    {
        try
        {
            switch (modal)
            {
                case PLACEMENT -> initPlacement();
                case SETTINGS -> initSettings();
                default -> initMain();
            }
        }
        catch (final LinkageError | RuntimeException e)
        {
            fail(e);
        }
    }

    private void initMain()
    {
        final int px = panelX();
        final int py = panelY();
        final int pw = panelWidth();
        addRenderableWidget(new IconButton(px + 6, py + 6, 124, 20, new ItemStack(Items.PAINTING),
          Component.translatable("mcvanillatweaks.buildtool.style_button"), b -> switchPack()));

        // Top categories, as item tabs.
        int tx = px + 6;
        for (final StructurePacks.Category category : tabs)
        {
            if (tx + TAB > px + pw - 6)
            {
                break;
            }
            final IconButton tab = addRenderableWidget(new IconButton(tx, py + 30, TAB, TAB, new ItemStack(categoryIcon(category.subPath)), Component.empty(),
              b -> open(category.subPath)));
            tab.setTooltip(Tooltip.create(Component.literal(capitalize(category.subPath))));
            tab.active = !category.subPath.equals(stateTab) || !stateTab.equals(statePath);
            tx += TAB + 2;
        }

        // Folders and blueprints of the path.
        final List<Entry> entries = entries();
        final int cw = gridColumnWidth();
        final int gy = py + 56;
        final int first = gridScroll * 3;
        for (int i = first; i < Math.min(entries.size(), first + GRID_ROWS * 3); i++)
        {
            final Entry entry = entries.get(i);
            final int bx = px + 6 + ((i - first) % 3) * (cw + 2);
            final int by = gy + ((i - first) / 3) * ROW;
            addRenderableWidget(entryButton(entry, bx, by, cw));
        }

        // Alternatives or levels on the left.
        if (side != Side.NONE)
        {
            final int rows = sideRows();
            final List<Button> buttons = sideButtons();
            final int shown = Math.min(rows, buttons.size());
            final int sy = panelY() - 4 - (shown * ROW + 22);
            for (int i = 0; i < shown; i++)
            {
                final Button button = buttons.get(i);
                button.setRectangle(SIDE_WIDTH - 12, 18, 10, sy + 18 + i * ROW);
                addRenderableWidget(button);
            }
        }

        // Manipulator.
        final int mx = manipulatorX() + 8;
        final int my = manipulatorY() + 18;
        final boolean active = preview().getBlueprint() != null;
        addArrow(mx, my, "↺", "mcvanillatweaks.buildtool.rotate_left", () -> rotate(Rotation.COUNTERCLOCKWISE_90), active);
        addArrow(mx + 23, my, "▲", "mcvanillatweaks.buildtool.forward", () -> forward(1), active);
        addArrow(mx + 46, my, "↻", "mcvanillatweaks.buildtool.rotate_right", () -> rotate(Rotation.CLOCKWISE_90), active);
        addArrow(mx, my + 22, "◀", "mcvanillatweaks.buildtool.left", () -> sideways(false), active);
        addArrow(mx + 23, my + 22, "⇄", "mcvanillatweaks.buildtool.mirror", this::mirror, active);
        addArrow(mx + 46, my + 22, "▶", "mcvanillatweaks.buildtool.right", () -> sideways(true), active);
        addArrow(mx, my + 44, "↓", "mcvanillatweaks.buildtool.down", () -> move(new BlockPos(0, -1, 0)), active);
        addArrow(mx + 23, my + 44, "▼", "mcvanillatweaks.buildtool.back", () -> forward(-1), active);
        addArrow(mx + 46, my + 44, "↑", "mcvanillatweaks.buildtool.up", () -> move(new BlockPos(0, 1, 0)), active);

        final int bw = MANIPULATOR_WIDTH - 16;
        final Blueprint blueprint = preview().getBlueprint();
        final Button place = addRenderableWidget(new IconButton(mx, my + 92, bw, 20, new ItemStack(Items.LIME_DYE),
          Component.translatable("mcvanillatweaks.buildtool.place"), b -> confirm()));
        place.active = blueprint != null && !locked(blueprint);
        if (blueprint != null && locked(blueprint))
        {
            place.setTooltip(Tooltip.create(Component.translatable("mcvanillatweaks.buildtool.locked")));
        }
        addRenderableWidget(new IconButton(mx, my + 114, bw, 20, new ItemStack(Items.BARRIER), Component.translatable("gui.cancel"), b -> cancel()))
          .setTooltip(Tooltip.create(Component.translatable("mcvanillatweaks.buildtool.cancel_hint")));
        final int half = (bw - 2) / 2;
        final IconButton settings = addRenderableWidget(new IconButton(mx, my + 136, half, 20, new ItemStack(Items.COMPARATOR), Component.empty(), b -> {
            modal = Modal.SETTINGS;
            rebuildWidgets();
        }));
        settings.setTooltip(Tooltip.create(Component.translatable("mcvanillatweaks.buildtool.settings")));
        final IconButton contents = addRenderableWidget(new IconButton(mx + bw - half, my + 136, half, 20, new ItemStack(Items.PAPER), Component.empty(),
          b -> contents()));
        contents.setTooltip(Tooltip.create(Component.translatable("com.ldtteam.structurize.gui.scantool.showres")));
        contents.active = blueprint != null;
    }

    private void addArrow(final int x, final int y, final String label, final String tooltip, final Runnable action, final boolean active)
    {
        final Button button = addRenderableWidget(new ArrowButton(x, y, label, b -> action.run()));
        button.setTooltip(Tooltip.create(Component.translatable(tooltip)));
        button.active = active;
    }

    /** A manipulator button whose arrow is drawn larger: the font's arrows are small. */
    static final class ArrowButton extends Button
    {
        ArrowButton(final int x, final int y, final String label, final OnPress onPress)
        {
            super(x, y, 22, 20, Component.literal(label), onPress, DEFAULT_NARRATION);
        }

        @Override
        public void renderString(final GuiGraphics g, final net.minecraft.client.gui.Font font, final int colour)
        {
            // The rotation arrows are tiny in the font: twice as big.
            final String text = getMessage().getString();
            final float scale = text.equals("↺") || text.equals("↻") ? 2.2F : 1.5F;
            g.pose().pushPose();
            g.pose().translate(getX() + getWidth() / 2.0F, getY() + getHeight() / 2.0F, 0);
            g.pose().scale(scale, scale, 1);
            g.drawString(font, getMessage(), -font.width(getMessage()) / 2, -4, colour | 0xFF000000, true);
            g.pose().popPose();
        }
    }

    private Button entryButton(final Entry entry, final int x, final int y, final int w)
    {
        if (entry.back())
        {
            final IconButton back = new IconButton(x, y, w, 18, new ItemStack(Items.ARROW), Component.translatable("gui.back"), b -> open(parent(statePath)));
            back.setTooltip(Tooltip.create(Component.translatable("mcvanillatweaks.buildtool.back_hint")));
            return back;
        }
        if (entry.folder() != null)
        {
            final String path = entry.folder().subPath;
            final String[] parts = path.split("/");
            return new IconButton(x, y, w, 18, new ItemStack(categoryIcon(path)), Component.literal(capitalize(parts[parts.length - 1])), b -> {
                TERMINAL.putIfAbsent(path, entry.folder().isTerminal);
                open(path);
            });
        }
        final String key = entry.group();
        final Map<String, List<Blueprint>> group = groups.get(key);
        final Blueprint first = first(group);
        final boolean locked = locked(first);
        final boolean creativeOnly = group.values().stream().allMatch(list -> BlueprintTagUtils.isInvisible(list.get(0)));
        final MutableComponent name = groupName(key).copy();
        if (selected(group))
        {
            name.withStyle(ChatFormatting.YELLOW);
        }
        else if (locked)
        {
            name.withStyle(ChatFormatting.RED);
        }
        else if (creativeOnly)
        {
            name.withStyle(ChatFormatting.LIGHT_PURPLE);
        }
        final IconButton button = new IconButton(x, y, w, 18, new ItemStack(blueprintIcon(first)), name, b -> {
            showGroup(key, false);
            rebuildWidgets();
        });
        final List<Component> lines = new ArrayList<>();
        final BlockState anchor = first.getBlockState(first.getPrimaryBlockOffset());
        if (anchor.getBlock() instanceof INamedBlueprintAnchorBlock named)
        {
            lines.add(named.getBlueprintDisplayName());
            lines.addAll(named.getDesc());
        }
        else
        {
            lines.add(groupName(key));
        }
        if (group.size() > 1)
        {
            lines.add(Component.translatable("mcvanillatweaks.buildtool.variants", group.size()).withStyle(ChatFormatting.GRAY));
        }
        else if (group.values().iterator().next().size() > 1)
        {
            lines.add(Component.translatable("mcvanillatweaks.buildtool.levels", group.values().iterator().next().size()).withStyle(ChatFormatting.GRAY));
        }
        if (creativeOnly)
        {
            lines.add(Component.translatable("mcvanillatweaks.buildtool.creative").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (locked && anchor.getBlock() instanceof IRequirementsBlueprintAnchorBlock requirements && minecraft != null && minecraft.level != null)
        {
            for (final MutableComponent line : requirements.getRequirements(minecraft.level, preview().getPos(), minecraft.player))
            {
                lines.add(line.withStyle(ChatFormatting.RED));
            }
        }
        button.setTooltip(Tooltip.create(join(lines)));
        return button;
    }

    private static Component join(final List<Component> lines)
    {
        final MutableComponent text = Component.empty();
        for (int i = 0; i < lines.size(); i++)
        {
            if (i > 0)
            {
                text.append("\n");
            }
            text.append(lines.get(i));
        }
        return text;
    }

    private List<Button> sideButtons()
    {
        final List<Button> buttons = new ArrayList<>();
        final Map<String, List<Blueprint>> group = groups.get(stateGroup);
        if (group == null)
        {
            return buttons;
        }
        if (side == Side.ALTERNATIVES)
        {
            for (final String alt : group.keySet())
            {
                final Button button = Button.builder(Component.literal(alt), b -> {
                    showAlternative(alt, false);
                    rebuildWidgets();
                }).build();
                button.active = !alt.equals(stateAlt) || group.get(alt).size() > 1;
                buttons.add(button);
            }
            return buttons;
        }
        if (group.size() > 1)
        {
            buttons.add(Button.builder(Component.translatable("mcvanillatweaks.buildtool.variants_back"), b -> {
                side = Side.ALTERNATIVES;
                rebuildWidgets();
            }).build());
        }
        final List<Blueprint> levels = levels();
        final Blueprint current = preview().getBlueprint();
        for (int i = 0; i < levels.size(); i++)
        {
            final Blueprint blueprint = levels.get(i);
            final Button button = Button.builder(Component.translatable("mcvanillatweaks.buildtool.level", i + 1), b -> setBlueprint(blueprint)).build();
            button.active = !blueprint.equals(current);
            buttons.add(button);
        }
        return buttons;
    }

    private void initPlacement()
    {
        final int w = 180;
        final int h = 30 + placements.size() * 22 + 26;
        final int x = (width - w) / 2;
        final int y = (height - h) / 2;
        for (int i = 0; i < placements.size(); i++)
        {
            final Placement placement = placements.get(i);
            addRenderableWidget(Button.builder(placement.name(), b -> place(placement.type(), placement.id())).bounds(x + 8, y + 24 + i * 22, w - 16, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> {
            modal = Modal.NONE;
            rebuildWidgets();
        }).bounds(x + 8, y + h - 26, w - 16, 20).build());
    }

    /** Structurize's preview renderer settings: switches for booleans, fields for numbers checked against the setting's range. */
    private void initSettings()
    {
        final List<ModConfigSpec.ConfigValue<?>> settings = previewSettings();
        final int w = 280;
        final int h = 30 + settings.size() * 22 + 26;
        final int x = (width - w) / 2;
        final int y = (height - h) / 2;
        for (int i = 0; i < settings.size(); i++)
        {
            final ModConfigSpec.ConfigValue<?> setting = settings.get(i);
            final int ry = y + 24 + i * 22;
            if (setting.get() instanceof Boolean)
            {
                @SuppressWarnings("unchecked") final ModConfigSpec.ConfigValue<Boolean> bool = (ModConfigSpec.ConfigValue<Boolean>) setting;
                addRenderableWidget(Button.builder(Component.translatable(bool.get() ? "options.on" : "options.off"), b -> {
                    Structurize.getConfig().set(bool, !bool.get());
                    b.setMessage(Component.translatable(bool.get() ? "options.on" : "options.off"));
                }).bounds(x + w - 8 - 50, ry, 50, 20).build());
            }
            else if (setting.get() instanceof Number number)
            {
                final EditBox box = addRenderableWidget(new EditBox(font, x + w - 8 - 49, ry + 1, 48, 18, Component.empty()));
                box.setValue(number.toString());
                box.setFilter(text -> text.matches("-?\\d*\\.?\\d*"));
                box.setResponder(text -> applyNumber(setting, box, text));
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            modal = Modal.NONE;
            rebuildWidgets();
        }).bounds(x + 8, y + h - 26, w - 16, 20).build());
    }

    static List<ModConfigSpec.ConfigValue<?>> previewSettings()
    {
        final List<ModConfigSpec.ConfigValue<?>> settings = new ArrayList<>();
        ((ClientConfiguration) Structurize.getConfig().getClient()).collectPreviewRendererSettings(settings::add);
        return settings;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void applyNumber(final ModConfigSpec.ConfigValue<?> setting, final EditBox box, final String text)
    {
        if (text.isBlank())
        {
            return;
        }
        try
        {
            final double value = Double.parseDouble(text);
            final Object old = setting.get();
            final Object parsed = old instanceof Integer ? (Object) (int) value : old instanceof Long ? (Object) (long) value : (Object) value;
            final ModConfigSpec.ValueSpec spec = setting.getSpec();
            if (spec != null && !spec.test(parsed))
            {
                box.setTextColor(0xFF5555);
                return;
            }
            box.setTextColor(0xE0E0E0);
            Structurize.getConfig().set((ModConfigSpec.ConfigValue) setting, parsed);
        }
        catch (final NumberFormatException e)
        {
            box.setTextColor(0xFF5555);
        }
    }

    // ------------------------------------------------------------------ screen

    @Override
    public void tick()
    {
        try
        {
            poll();
            if (++ticks >= TIP_TICKS)
            {
                showTip = false;
            }
        }
        catch (final LinkageError | RuntimeException e)
        {
            fail(e);
        }
    }

    @Override
    public void renderBackground(final GuiGraphics g, final int mouseX, final int mouseY, final float partialTick)
    {
        // The world and the blueprint preview stay visible.
        if (modal != Modal.NONE)
        {
            g.fill(0, 0, width, height, 0x60000000);
            return;
        }
        Gui.panel(g, panelX(), panelY(), panelWidth(), panelHeight());
        Gui.panel(g, manipulatorX(), manipulatorY(), MANIPULATOR_WIDTH, MANIPULATOR_HEIGHT);
        if (side != Side.NONE)
        {
            final int shown = Math.min(sideRows(), sideButtons().size());
            final int h = shown * ROW + 22;
            Gui.panel(g, 4, panelY() - 4 - h, SIDE_WIDTH, h);
        }
    }

    @Override
    public void render(final GuiGraphics g, final int mouseX, final int mouseY, final float partialTick)
    {
        if (modal == Modal.PLACEMENT)
        {
            renderModalPanel(g, 180, 30 + placements.size() * 22 + 26, Component.translatable("mcvanillatweaks.buildtool.how"));
        }
        else if (modal == Modal.SETTINGS)
        {
            renderSettingsPanel(g);
        }
        super.render(g, mouseX, mouseY, partialTick);
        if (modal != Modal.NONE)
        {
            return;
        }
        try
        {
            renderTexts(g);
        }
        catch (final LinkageError | RuntimeException e)
        {
            fail(e);
        }
    }

    private void renderModalPanel(final GuiGraphics g, final int w, final int h, final Component title)
    {
        final int x = (width - w) / 2;
        final int y = (height - h) / 2;
        g.fill(0, 0, width, height, 0x60000000);
        Gui.panel(g, x, y, w, h);
        g.drawString(font, Gui.fit(font, title, w - 16), x + 8, y + 8, Gui.LABEL, false);
    }

    private void renderSettingsPanel(final GuiGraphics g)
    {
        final List<ModConfigSpec.ConfigValue<?>> settings = previewSettings();
        final int w = 280;
        final int h = 30 + settings.size() * 22 + 26;
        renderModalPanel(g, w, h, Component.translatable("mcvanillatweaks.buildtool.settings"));
        final int x = (width - w) / 2;
        final int y = (height - h) / 2;
        for (int i = 0; i < settings.size(); i++)
        {
            final ModConfigSpec.ValueSpec spec = settings.get(i).getSpec();
            final Component label = Component.translatable(spec == null || spec.getTranslationKey() == null ? settings.get(i).getPath().get(settings.get(i).getPath().size() - 1)
                                                             : spec.getTranslationKey());
            g.drawString(font, Gui.fit(font, label, w - 16 - 60), x + 8, y + 24 + i * 22 + 6, Gui.LABEL, false);
        }
    }

    private void renderTexts(final GuiGraphics g)
    {
        final int px = panelX();
        final int py = panelY();
        final int pw = panelWidth();
        // Path in the pack, like Structurize's.
        final Blueprint blueprint = preview().getBlueprint();
        String path = statePack + (statePath.isEmpty() ? "" : " / " + statePath.replace("/", " / "));
        if (blueprint != null)
        {
            path += " / " + blueprint.getFileName();
        }
        g.drawString(font, Gui.fit(font, Component.literal(path), pw - 144), px + 136, py + 12, Gui.LABEL, false);
        if (tabsLoading != null || !statePath.isEmpty() && !loaded)
        {
            g.drawString(font, Component.translatable("mcvanillatweaks.buildtool.loading"), px + 8, py + 62, Gui.LABEL_SOFT, false);
        }
        else if (statePath.isEmpty())
        {
            g.drawString(font, Gui.fit(font, Component.translatable("mcvanillatweaks.buildtool.pick_category"), pw - 16), px + 8, py + 62, Gui.LABEL_SOFT, false);
        }
        // Scroll marker of the grid.
        final int rows = (entries().size() + 2) / 3;
        if (rows > GRID_ROWS)
        {
            final int track = GRID_ROWS * ROW - 2;
            final int thumb = Math.max(6, track * GRID_ROWS / rows);
            final int offset = (track - thumb) * gridScroll / (rows - GRID_ROWS);
            final int sx = px + pw - 9;
            g.fill(sx, py + 56, sx + 3, py + 56 + track, 0xFF8B8B8B);
            g.fill(sx, py + 56 + offset, sx + 3, py + 56 + offset + thumb, 0xFF404040);
        }

        // Manipulator title and state.
        final int mx = manipulatorX();
        final int my = manipulatorY();
        g.drawString(font, Component.translatable("mcvanillatweaks.buildtool.position"), mx + 8, my + 7, Gui.LABEL, false);
        if (blueprint != null)
        {
            final Rotation rotation = preview().getRotationMirror().rotation();
            final int degrees = switch (rotation)
            {
                case CLOCKWISE_90 -> 90;
                case CLOCKWISE_180 -> 180;
                case COUNTERCLOCKWISE_90 -> 270;
                default -> 0;
            };
            g.drawString(font, Component.translatable("mcvanillatweaks.buildtool.rotation", degrees), mx + 8, my + 86, Gui.LABEL_SOFT, false);
            final boolean mirrored = preview().getRotationMirror().mirror() != Mirror.NONE;
            g.drawString(font, Component.translatable(mirrored ? "mcvanillatweaks.buildtool.mirrored" : "mcvanillatweaks.buildtool.not_mirrored"), mx + 8, my + 97,
              Gui.LABEL_SOFT, false);
        }
        else
        {
            g.drawString(font, Gui.fit(font, Component.translatable("mcvanillatweaks.buildtool.none"), MANIPULATOR_WIDTH - 16), mx + 8, my + 90, Gui.LABEL_SOFT,
              false);
        }

        if (side != Side.NONE)
        {
            final int shown = Math.min(sideRows(), sideButtons().size());
            final int sy = panelY() - 4 - (shown * ROW + 22);
            g.drawString(font, Component.translatable(side == Side.ALTERNATIVES ? "mcvanillatweaks.buildtool.variants_title" : "mcvanillatweaks.buildtool.levels_title"),
              12, sy + 7, Gui.LABEL, false);
        }

        if (showTip)
        {
            final List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.translatable("structurize.gui.manipulation.info"), 220);
            int ty = height / 2 - 40;
            for (final var line : lines)
            {
                g.drawCenteredString(font, line, width / 2, ty, Gui.WHITE);
                ty += 10;
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY)
    {
        if (modal == Modal.NONE && Gui.inside(mouseX, mouseY, panelX(), panelY() + 52, panelWidth(), GRID_ROWS * ROW + 8))
        {
            final int rows = (entries().size() + 2) / 3;
            final int next = Math.max(0, Math.min(rows - GRID_ROWS, gridScroll - (int) Math.signum(scrollY)));
            if (next != gridScroll)
            {
                gridScroll = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(final int key, final int scanCode, final int modifiers)
    {
        if (modal != Modal.NONE && key == GLFW.GLFW_KEY_ESCAPE)
        {
            modal = Modal.NONE;
            rebuildWidgets();
            return true;
        }
        if (modal == Modal.NONE && !(getFocused() instanceof EditBox))
        {
            // Structurize's keys (their context only matches its own window, so they are compared by key here).
            final InputConstants.Key input = InputConstants.getKey(key, scanCode);
            if (matches(ModKeyMappings.MOVE_FORWARD, input))
            {
                forward(1);
                return true;
            }
            if (matches(ModKeyMappings.MOVE_BACK, input))
            {
                forward(-1);
                return true;
            }
            if (matches(ModKeyMappings.MOVE_LEFT, input))
            {
                sideways(false);
                return true;
            }
            if (matches(ModKeyMappings.MOVE_RIGHT, input))
            {
                sideways(true);
                return true;
            }
            if (matches(ModKeyMappings.MOVE_UP, input))
            {
                move(new BlockPos(0, 1, 0));
                return true;
            }
            if (matches(ModKeyMappings.MOVE_DOWN, input))
            {
                move(new BlockPos(0, -1, 0));
                return true;
            }
            if (matches(ModKeyMappings.ROTATE_CW, input))
            {
                rotate(Rotation.CLOCKWISE_90);
                return true;
            }
            if (matches(ModKeyMappings.ROTATE_CCW, input))
            {
                rotate(Rotation.COUNTERCLOCKWISE_90);
                return true;
            }
            if (matches(ModKeyMappings.MIRROR, input))
            {
                mirror();
                return true;
            }
            if (matches(ModKeyMappings.PLACE, input))
            {
                confirm();
                return true;
            }
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    static boolean matches(final Lazy<KeyMapping> mapping, final InputConstants.Key input)
    {
        try
        {
            final KeyMapping key = mapping.get();
            return key != null && !key.isUnbound() && key.getKey().equals(input);
        }
        catch (final RuntimeException e)
        {
            return false;
        }
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    private void fail(final Throwable e)
    {
        BuildToolScreens.report(e);
        if (minecraft != null && minecraft.screen == this)
        {
            onClose();
        }
    }

    // ------------------------------------------------------------------ icons

    private static String capitalize(final String path)
    {
        final String[] parts = path.split("/");
        final String last = parts[parts.length - 1];
        return last.isEmpty() ? path : last.substring(0, 1).toUpperCase(Locale.US) + last.substring(1);
    }

    /** A vanilla item for MineColonies' blueprint categories, matched by name; a bookshelf for the others. */
    static Item categoryIcon(final String path)
    {
        final String[] parts = path.toLowerCase(Locale.ROOT).split("/");
        final String name = parts[parts.length - 1];
        return switch (name)
        {
            case "fundamentals" -> Items.BELL;
            case "craftsmanship" -> Items.ANVIL;
            case "agriculture" -> Items.WHEAT;
            case "infrastructure" -> Items.RAIL;
            case "military" -> Items.IRON_SWORD;
            case "decorations" -> Items.FLOWER_POT;
            case "walls" -> Items.COBBLESTONE_WALL;
            case "mystic" -> Items.AMETHYST_SHARD;
            case "education" -> Items.LECTERN;
            case "horticulture" -> Items.OAK_SAPLING;
            case "husbandry" -> Items.LEAD;
            case "carpentry" -> Items.OAK_PLANKS;
            case "luxury" -> Items.GOLD_INGOT;
            case "masonry" -> Items.STONE_BRICKS;
            case "metallurgy" -> Items.IRON_INGOT;
            case "storage" -> Items.BARREL;
            case "supplies" -> Items.SCAFFOLDING;
            case "canal" -> Items.WATER_BUCKET;
            case "crossroads" -> Items.OAK_SIGN;
            case "mineshafts" -> Items.LADDER;
            case "gate" -> Items.OAK_FENCE_GATE;
            case "tower" -> Items.STONE_BRICK_WALL;
            default -> Items.BOOKSHELF;
        };
    }

    /** The hut block of a hut blueprint; the structure block for the others. */
    private static Item blueprintIcon(final Blueprint blueprint)
    {
        try
        {
            final BlockState anchor = blueprint.getBlockState(blueprint.getPrimaryBlockOffset());
            if (anchor.getBlock() instanceof AbstractBlockHut<?> hut && hut.asItem() != Items.AIR)
            {
                return hut.asItem();
            }
        }
        catch (final LinkageError | RuntimeException e)
        {
            MineColoniesVanillaTweaks.LOGGER.debug("No anchor for blueprint {}", blueprint.getFileName(), e);
        }
        return Items.STRUCTURE_BLOCK;
    }
}

package fr.mcvanillatweaks.client.hut;

import com.minecolonies.core.colony.buildings.moduleviews.BuildingResourcesModuleView;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource;
import com.minecolonies.core.colony.buildings.utils.BuildingBuilderResource.RessourceAvailability;
import com.minecolonies.core.network.messages.server.colony.building.TransferItemsRequestMessage;
import fr.mcvanillatweaks.client.PinnedBuilders;
import fr.mcvanillatweaks.client.citizen.Gui;
import fr.mcvanillatweaks.client.citizen.IconButton;
import fr.mcvanillatweaks.client.citizen.ScrollArea;
import fr.mcvanillatweaks.client.citizen.Tooltip;
import fr.mcvanillatweaks.client.data.BuilderRef;
import fr.mcvanillatweaks.client.domum.DomumMaterials;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builder's resources page (MineColonies' resource list): the current construction, its step and how much of the
 * resources are supplied and used, then every resource coloured like the Resource HUD, with what the player carries
 * and what is on its way, and MineColonies' "give" button that hands the missing amount over from the player's
 * inventory. A button pins the builder to the Resource HUD. The user finds giving from anywhere too easy: the button
 * only works at the hut (opened from its block, the player close to it), not from the resource scroll.
 * <pre>
 * Town hall level 2                         [Pin to HUD]
 * Step 2/5
 * Supplied 45%  [=====     ]   Used 30%  [===       ]
 * +----------------------------------------------------+
 * | (o) Oak Planks                       12 / 64   [+] |
 * |     In your inventory: 30 · on its way: 8           |
 * +----------------------------------------------------+
 * </pre>
 */
final class ResourcesTab extends HutTab
{
    private static final int ROW = 24;
    private static final int GIVE = 14;
    /** How close to the hut block the player must stand to give (clicking the block needs about 5 blocks). */
    private static final double GIVE_DISTANCE = 8.0D;
    /** Rows of Domum Ornamentum blocks have a third line: the materials to cut them from. */
    private static final int MATERIALS_ROW = 34;
    private static final int BUTTON_HEIGHT = 20;
    private static final int PIN_WIDTH = 116;

    /**
     * Warehouse contents remembered by the resource scroll (MineColonies' {@code WarehouseSnapshot}, keyed by description
     * id and components hash), handed over by the scroll's window just before this page is created.
     */
    @Nullable
    static Map<String, Integer> pendingSnapshot;
    /** Set by the resource scroll's hook just before this page is created: no giving from the scroll. */
    static boolean pendingFromScroll;

    private final BuildingResourcesModuleView module;
    private final Map<String, Integer> warehouse;
    private final boolean fromScroll;
    private final ScrollArea list = new ScrollArea();
    private BuilderSite site;
    /** Per resource of {@link #site}: its Architect's Cutter materials, or {@code null}. */
    private List<Component> materials = List.of();
    /** Top of each row in the list, plus the list's height at the end. */
    private int[] rowTops = {0};
    private IconButton pin;
    private IconButton supplyAll;
    private int ticks;
    /** After a transfer, the local amounts are kept until the server has had time to answer (MineColonies waits 3 s). */
    private int holdUntil;

    ResourcesTab(final HutScreen screen, final BuildingResourcesModuleView module)
    {
        super(screen);
        this.module = module;
        this.warehouse = pendingSnapshot == null ? Map.of() : pendingSnapshot;
        pendingSnapshot = null;
        this.fromScroll = pendingFromScroll;
        pendingFromScroll = false;
        site(BuilderSite.of(screen.building(), module));
        BuilderSite.refresh(screen.building());
    }

    @Override
    protected void init()
    {
        supplyAll = add(new IconButton(x, y + height - BUTTON_HEIGHT, PIN_WIDTH, BUTTON_HEIGHT,
          new ItemStack(Items.CHEST), Component.translatable("mcvanillatweaks.hut.res.supply_all"), b -> supplyAll()));
        supplyAll.active = canSupplyAll();
        final BuilderRef ref = BuilderSite.ref(building());
        if (ref != null)
        {
            pin = add(new IconButton(x + width - PIN_WIDTH, y, PIN_WIDTH, BUTTON_HEIGHT, new ItemStack(Items.MAP), pinLabel(ref), b -> {
                PinnedBuilders.toggle(ref, null);
                b.setMessage(pinLabel(ref));
            }));
        }
    }

    private boolean canSupplyAll()
    {
        return atHut() && site.resources.stream().anyMatch(ResourcesTab::canGive);
    }

    private void supplyAll()
    {
        if (!atHut())
        {
            return;
        }
        for (final BuildingBuilderResource resource : site.resources)
        {
            if (canGive(resource))
            {
                give(resource);
            }
        }
        supplyAll.active = canSupplyAll();
    }

    private static Component pinLabel(final BuilderRef ref)
    {
        return Component.translatable(PinnedBuilders.get(ref).isPresent() ? "mcvanillatweaks.hut.res.unpin" : "mcvanillatweaks.hut.res.pin");
    }

    @Override
    protected void tick()
    {
        supplyAll.active = canSupplyAll();
        if (++ticks >= holdUntil && ticks % 20 == 0)
        {
            site(BuilderSite.of(building(), module));
        }
    }

    private void site(final BuilderSite site)
    {
        this.site = site;
        final List<Component> found = new ArrayList<>(site.resources.size());
        final int[] tops = new int[site.resources.size() + 1];
        for (int i = 0; i < site.resources.size(); i++)
        {
            final Component text = DomumMaterials.describe(site.resources.get(i).getItemStack());
            found.add(text);
            tops[i + 1] = tops[i] + (text == null ? ROW : MATERIALS_ROW);
        }
        materials = found;
        rowTops = tops;
    }

    /** At the hut: not opened from the scroll, and the player stands by the hut block. */
    private boolean atHut()
    {
        final Player player = Minecraft.getInstance().player;
        return !fromScroll && player != null
                 && player.position().distanceToSqr(Vec3.atCenterOf(building().getPosition())) <= GIVE_DISTANCE * GIVE_DISTANCE;
    }

    private void give(final BuildingBuilderResource resource)
    {
        final int needed = resource.getAmount() - resource.getAvailable();
        if (needed <= 0)
        {
            return;
        }
        new TransferItemsRequestMessage(building(), resource.getItemStack().copyWithCount(1), needed, true).sendToServer();
        // Same local update as MineColonies until the server answers.
        resource.setAvailable(Math.min(resource.getAmount(), resource.getAvailable() + resource.getPlayerAmount()));
        resource.setPlayerAmount(Math.max(0, resource.getPlayerAmount() - needed));
        holdUntil = ticks + 60;
    }

    private static boolean canGive(final BuildingBuilderResource resource)
    {
        final RessourceAvailability status = resource.getAvailabilityStatus();
        return status == RessourceAvailability.NEED_MORE || status == RessourceAvailability.HAVE_ENOUGH;
    }

    /** The Resource HUD's colours. */
    static int colour(final RessourceAvailability status)
    {
        return switch (status)
        {
            case DONT_HAVE -> Gui.RED;
            case NEED_MORE -> Gui.GOLD;
            case IN_DELIVERY -> Gui.YELLOW;
            case HAVE_ENOUGH -> Gui.GREEN;
            case NOT_NEEDED -> Gui.LIST_SOFT;
        };
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void render(final GuiGraphics g, final int mouseX, final int mouseY, final Tooltip tooltip)
    {
        final Component name = site.name();
        final int titleWidth = width - PIN_WIDTH - GAP;
        heading(g, name != null ? name : Component.translatable("hud.mcvanillatweaks.no_work_order"), x, y + 1, titleWidth);
        if (name != null && Gui.isCut(font, name, titleWidth) && Gui.inside(mouseX, mouseY, x, y, titleWidth, 10))
        {
            tooltip.set(name);
        }
        if (site.workOrder != null)
        {
            g.drawString(font, Component.translatable("com.minecolonies.coremod.gui.progress.step", module.getCurrentStage(), module.getTotalStages()),
              x + 1, y + 12, Gui.LABEL_SOFT, false);
        }
        if (pin != null && pin.isHovered())
        {
            tooltip.set(Component.translatable("mcvanillatweaks.hut.res.pin_hint").withStyle(ChatFormatting.GRAY));
        }

        supplyAll.active = canSupplyAll();
        if (supplyAll.isHovered())
        {
            tooltip.set(Component.translatable(atHut() ? "mcvanillatweaks.hut.res.supply_all_hint"
              : "mcvanillatweaks.hut.res.give_at_hut"));
        }

        // Supplied and used, as two villager experience bars.
        final int barsY = y + 27;
        final int half = (width - GAP) / 2;
        progress(g, Component.translatable("mcvanillatweaks.hut.res.supplied", Math.max(0, site.supplied)), site.supplied, x, barsY, half);
        progress(g, Component.translatable("mcvanillatweaks.hut.res.used", Math.max(0, site.used)), site.used, x + half + GAP, barsY, half);
        if (Gui.inside(mouseX, mouseY, x, barsY, half, 16))
        {
            tooltip.set(Component.translatable("mcvanillatweaks.hut.res.supplied_hint"));
        }
        else if (Gui.inside(mouseX, mouseY, x + half + GAP, barsY, half, 16))
        {
            tooltip.set(Component.translatable("mcvanillatweaks.hut.res.used_hint"));
        }

        final List<BuildingBuilderResource> resources = site.resources;
        final int top = barsY + 22;
        list.set(x, top, width, y + height - top - BUTTON_HEIGHT - GAP, rowTops[resources.size()], ROW);
        list.begin(g);
        if (resources.isEmpty())
        {
            final Component empty = Component.translatable(site.workOrder == null ? "mcvanillatweaks.hut.res.no_site" : "mcvanillatweaks.hut.res.none");
            int ty = list.visibleTop() + 6;
            for (final var line : font.split(empty, list.listWidth() - 8))
            {
                g.drawString(font, line, list.listX() + 4, ty, Gui.LIST_SOFT, true);
                ty += 10;
            }
        }
        for (int i = 0; i < resources.size(); i++)
        {
            final int rowY = list.listY() + rowTops[i];
            final int rowHeight = rowTops[i + 1] - rowTops[i];
            if (rowY + rowHeight < list.visibleTop() || rowY > list.visibleBottom())
            {
                continue;
            }
            renderRow(g, resources.get(i), materials.get(i), rowY, rowHeight, mouseX, mouseY, tooltip);
        }
        list.end(g);
    }

    private void progress(final GuiGraphics g, final Component label, final int percent, final int px, final int py, final int w)
    {
        g.drawString(font, Gui.fit(font, label, w), px + 1, py, Gui.LABEL, false);
        Gui.bar(g, px, py + 11, Math.min(w, Gui.BAR_WIDTH), percent < 0 ? 0 : percent / 100.0F);
    }

    private void renderRow(final GuiGraphics g, final BuildingBuilderResource resource, @Nullable final Component materials, final int rowY, final int rowHeight,
      final int mouseX, final int mouseY, final Tooltip tooltip)
    {
        final boolean hovered = list.hover(g, mouseX, mouseY, rowY, rowHeight);
        final RessourceAvailability status = resource.getAvailabilityStatus();
        final int colour = colour(status);
        g.renderItem(resource.getItemStack().copyWithCount(1), list.listX() + 3, rowY + (rowHeight - 16) / 2);

        final int right = list.listX() + list.listWidth() - 4;
        final int giveX = right - GIVE;
        final int giveY = rowY + (rowHeight - GIVE) / 2;
        final boolean atHut = atHut();
        final boolean giveActive = atHut && canGive(resource);
        final boolean overGive = list.overRows(mouseX, mouseY) && Gui.inside(mouseX, mouseY, giveX, giveY, GIVE, GIVE);
        Gui.textButton(g, font, giveX, giveY, GIVE, GIVE, "+", giveActive, overGive && giveActive);

        final String amounts = resource.getAvailable() + " / " + resource.getAmount();
        final int amountsX = giveX - 6 - font.width(amounts);
        // With a materials line, the amounts go up on the name's line so the materials can run under them.
        g.drawString(font, amounts, amountsX, materials != null ? rowY + 3 : rowY + (rowHeight - 8) / 2, colour, true);

        final int textX = list.listX() + 24;
        final int textWidth = amountsX - 6 - textX;
        g.drawString(font, Gui.fit(font, Component.literal(resource.getName()), textWidth), textX, rowY + 3, colour, true);
        int lineY = rowY + 13;
        if (materials != null)
        {
            g.drawString(font, Gui.fit(font, materials, giveX - 6 - textX), textX, lineY, Gui.WHITE, true);
            lineY += 10;
        }
        g.drawString(font, Gui.fit(font, detail(resource, inWarehouse(resource)), textWidth), textX, lineY, Gui.LIST_SOFT, true);

        if (overGive)
        {
            if (!atHut)
            {
                tooltip.set(Component.translatable("mcvanillatweaks.hut.res.give_at_hut").withStyle(ChatFormatting.GRAY));
            }
            else
            {
                tooltip.set(giveActive
                              ? Component.translatable("mcvanillatweaks.hut.res.give", resource.getAmount() - resource.getAvailable())
                              : Component.translatable("mcvanillatweaks.hut.res.give_none").withStyle(ChatFormatting.GRAY));
            }
        }
        else if (hovered)
        {
            // The item's own tooltip first: Domum Ornamentum blocks list there the materials to put in the Architect's Cutter.
            final List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), resource.getItemStack().copyWithCount(1)));
            lines.add(Component.translatable("mcvanillatweaks.hut.res.in_hut", resource.getAvailable(), resource.getAmount()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("mcvanillatweaks.hut.res.in_inventory", resource.getPlayerAmount()).withStyle(ChatFormatting.GRAY));
            if (resource.getAmountInDelivery() > 0)
            {
                lines.add(Component.translatable("mcvanillatweaks.hut.res.in_delivery", resource.getAmountInDelivery()).withStyle(ChatFormatting.YELLOW));
            }
            if (inWarehouse(resource) > 0)
            {
                lines.add(Component.translatable("mcvanillatweaks.hut.res.in_warehouse", inWarehouse(resource)).withStyle(ChatFormatting.AQUA));
            }
            lines.add(Component.translatable("mcvanillatweaks.hut.res.status." + status.name().toLowerCase(Locale.ROOT))
                        .withStyle(style(status)));
            tooltip.setAround(lines, mouseX, rowY, rowY + rowHeight);
        }
    }

    private static ChatFormatting style(final RessourceAvailability status)
    {
        return switch (status)
        {
            case DONT_HAVE -> ChatFormatting.RED;
            case NEED_MORE -> ChatFormatting.GOLD;
            case IN_DELIVERY -> ChatFormatting.YELLOW;
            case HAVE_ENOUGH -> ChatFormatting.GREEN;
            case NOT_NEEDED -> ChatFormatting.GRAY;
        };
    }

    /** What the scroll's warehouse snapshot holds of this resource, keyed like MineColonies' window. */
    private int inWarehouse(final BuildingBuilderResource resource)
    {
        if (warehouse.isEmpty())
        {
            return 0;
        }
        return warehouse.getOrDefault(resource.getItem().getDescriptionId() + "-" + resource.getItemStack().getComponentsPatch().hashCode(), 0);
    }

    /** Second line: what the player carries, what is on its way, and what the warehouse held when the scroll looked. */
    private static Component detail(final BuildingBuilderResource resource, final int inWarehouse)
    {
        final MutableComponent text = Component.translatable("mcvanillatweaks.hut.res.carried", resource.getPlayerAmount());
        if (resource.getAmountInDelivery() > 0)
        {
            text.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
              .append(Component.translatable("mcvanillatweaks.hut.res.coming", resource.getAmountInDelivery()).withStyle(ChatFormatting.YELLOW));
        }
        else if (inWarehouse > 0)
        {
            text.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
              .append(Component.translatable("mcvanillatweaks.hut.res.warehouse", inWarehouse).withStyle(ChatFormatting.AQUA));
        }
        return text;
    }

    @Override
    protected boolean mouseClicked(final double mouseX, final double mouseY, final int button)
    {
        if (list.mouseClicked(mouseX, mouseY))
        {
            return true;
        }
        if (button != 0 || !list.overRows(mouseX, mouseY) || !atHut())
        {
            return false;
        }
        int index = 0;
        while (index < site.resources.size() && mouseY >= list.listY() + rowTops[index + 1])
        {
            index++;
        }
        if (mouseY < list.listY() || index >= site.resources.size())
        {
            return false;
        }
        final BuildingBuilderResource resource = site.resources.get(index);
        final int rowY = list.listY() + rowTops[index];
        final int rowHeight = rowTops[index + 1] - rowTops[index];
        final int giveX = list.listX() + list.listWidth() - 4 - GIVE;
        if (canGive(resource) && Gui.inside(mouseX, mouseY, giveX, rowY + (rowHeight - GIVE) / 2, GIVE, GIVE))
        {
            Gui.clickSound();
            give(resource);
            return true;
        }
        return false;
    }

    @Override
    protected boolean mouseDragged(final double mouseX, final double mouseY)
    {
        return list.mouseDragged(mouseY);
    }

    @Override
    protected void mouseReleased()
    {
        list.mouseReleased();
    }

    @Override
    protected boolean mouseScrolled(final double mouseX, final double mouseY, final double delta)
    {
        return list.mouseScrolled(mouseX, mouseY, delta);
    }
}

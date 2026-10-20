package dev.jellyshort;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;

public final class JellyShort implements ClientModInitializer {
    private record Plant(double x, double y, double z) {}
    private static final List<Plant> plants = new ArrayList<>();
    private static final Set<Integer> hidden = new HashSet<>();
    // Immutable snapshots are also read by asynchronous chunk mesh builders.
    private static volatile Set<Long> hiddenBlocks = Set.of();
    private static String mode = "shorten";
    private static boolean automatic = true;
    private static int height = 2;
    private static Object lastLevel;
    private static int ticks;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != lastLevel) {
                plants.clear(); hidden.clear(); hiddenBlocks = Set.of(); lastLevel = client.level;
            }
            if (++ticks % 5 == 0) refresh();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
            literal("jellyshort").executes(ctx -> say("Full-height Jellybeans are detected automatically. Modes: shorten, hide, off. Other commands: auto, height 1-8, inspect, mark, unmark, clear."))
                .then(literal("mark").executes(ctx -> mark(false)))
                .then(literal("unmark").executes(ctx -> mark(true)))
                .then(literal("clear").executes(ctx -> { plants.clear(); refresh(); return say("Cleared marked plants."); }))
                .then(literal("shorten").executes(ctx -> setMode("shorten")))
                .then(literal("hide").executes(ctx -> setMode("hide")))
                .then(literal("off").executes(ctx -> setMode("off")))
                .then(literal("auto").executes(ctx -> { automatic = !automatic; refresh(); return say("Automatic full-height detection: " + automatic); }))
                .then(literal("height").then(argument("blocks", IntegerArgumentType.integer(1, 8)).executes(ctx -> {
                    height = IntegerArgumentType.getInteger(ctx, "blocks"); refresh(); return say("Visible height: " + height + " blocks.");
                })))
                .then(literal("inspect").executes(ctx -> inspect()))));
    }

    private static int setMode(String value) { mode = value; refresh(); return say("Mode: " + value); }
    private static int say(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Component.literal("[JellyShort] " + message));
        return 1;
    }

    private static int mark(boolean remove) {
        var client = Minecraft.getInstance();
        var hit = client.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) return say("Aim at the lowest plant piece or the soil directly below it.");
        double x, y, z;
        if (hit instanceof EntityHitResult entityHit) {
            var entity = entityHit.getEntity();
            if (!modelPiece(entity)) return say("Aim at a plant display piece or its soil block.");
            x = Math.floor(entity.getX()) + 0.5; y = Math.floor(entity.getY()); z = Math.floor(entity.getZ()) + 0.5;
        } else if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            x = pos.getX() + 0.5; y = pos.getY() + 1; z = pos.getZ() + 0.5;
        } else return 0;
        final double px = x, pz = z;
        boolean existed = plants.removeIf(p -> Math.abs(p.x - px) < 0.5 && Math.abs(p.z - pz) < 0.5);
        if (!remove) plants.add(new Plant(x, y, z));
        refresh();
        return say(remove ? (existed ? "Unmarked plant." : "No marked plant in that column.")
            : "Marked column at " + (int)x + ", " + (int)y + ", " + (int)z + ". Hidden pieces: " + hidden.size());
    }

    private static boolean modelPiece(Entity e) {
        return e instanceof ArmorStand || e instanceof Display.ItemDisplay || e instanceof Display.BlockDisplay;
    }

    private static void refresh() {
        hidden.clear();
        var level = Minecraft.getInstance().level;
        if (level == null || mode.equals("off")) { updateHiddenBlocks(Set.of()); return; }
        List<Plant> caneColumns = new ArrayList<>();
        if (automatic) {
            List<JellybeanFilter.Piece> pieces = new ArrayList<>();
            for (Entity entity : level.entitiesForRendering()) {
                if (entity instanceof ArmorStand stand && JellybeanFilter.matchesName(
                    stand.getItemBySlot(EquipmentSlot.HEAD).getHoverName().getString())) {
                    pieces.add(new JellybeanFilter.Piece(entity.getId(), entity.getX(), entity.getY(), entity.getZ()));
                }
            }
            Set<Integer> fullPlantIds = JellybeanFilter.select(pieces, 0, true);
            hidden.addAll(JellybeanFilter.select(pieces, height, mode.equals("hide")));
            java.util.Map<Long, Plant> columns = new java.util.HashMap<>();
            for (var piece : pieces) {
                if (!fullPlantIds.contains(piece.id())) continue;
                long key = BlockPos.asLong((int)Math.floor(piece.x()), 0, (int)Math.floor(piece.z()));
                Plant previous = columns.get(key);
                if (previous == null || piece.y() < previous.y) columns.put(key, new Plant(piece.x(), piece.y(), piece.z()));
            }
            caneColumns.addAll(columns.values());
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (!modelPiece(entity)) continue;
            for (Plant plant : plants) {
                double dy = entity.getY() - plant.y;
                if (Math.abs(entity.getX() - plant.x) <= 0.7 && Math.abs(entity.getZ() - plant.z) <= 0.7
                    && dy >= (mode.equals("hide") ? -0.5 : height) && dy <= 12) {
                    hidden.add(entity.getId()); break;
                }
            }
        }
        caneColumns.addAll(plants);
        Set<Long> nextBlocks = new HashSet<>();
        for (Plant plant : caneColumns) {
            int x = (int)Math.floor(plant.x), z = (int)Math.floor(plant.z);
            // Find the actual cane base: armor-stand origins sit below their visible heads.
            for (int y : CaneFilter.select((int)Math.floor(plant.y), height, mode.equals("hide"),
                testY -> level.getBlockState(new BlockPos(x, testY, z)).is(Blocks.SUGAR_CANE)))
                nextBlocks.add(BlockPos.asLong(x, y, z));
        }
        updateHiddenBlocks(nextBlocks);
    }

    private static void updateHiddenBlocks(Set<Long> next) {
        Set<Long> previous = hiddenBlocks;
        if (previous.equals(next)) return;
        hiddenBlocks = Set.copyOf(next);
        Set<Long> changed = new HashSet<>(previous);
        changed.addAll(next);
        Set<Long> sections = new HashSet<>();
        for (long packed : changed) {
            if (previous.contains(packed) == next.contains(packed)) continue;
            BlockPos pos = BlockPos.of(packed);
            sections.add(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        }
        var renderer = Minecraft.getInstance().levelRenderer;
        for (long section : sections) renderer.setSectionDirtyWithNeighbors(
            SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
    }

    public static boolean shouldHideBlock(BlockPos pos, BlockState state) {
        return state.is(Blocks.SUGAR_CANE) && hiddenBlocks.contains(pos.asLong());
    }

    public static boolean shouldHide(Entity entity) { return hidden.contains(entity.getId()); }

    private static int inspect() {
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return 0;
        int count = 0;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!modelPiece(e) || e.distanceToSqr(client.player) > 144 || count >= 100) continue;
            say(e.getType().toShortString() + " #" + e.getId() + " at " + String.format(java.util.Locale.ROOT,
                "%.2f %.2f %.2f", e.getX(), e.getY(), e.getZ()) + " name=" + e.getName().getString()
                + (e instanceof ArmorStand stand ? " head=" + stand.getItemBySlot(EquipmentSlot.HEAD).getHoverName().getString() : ""));
            count++;
        }
        return say("Nearby model entities: " + count + "; marked plants: " + plants.size() + "; hidden pieces: " + hidden.size()
            + "; hidden cane blocks: " + hiddenBlocks.size());
    }
}



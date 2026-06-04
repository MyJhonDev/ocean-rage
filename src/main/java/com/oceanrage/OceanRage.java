package com.oceanrage;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mod(OceanRage.MOD_ID)
public class OceanRage {
    public static final String MOD_ID = "oceanrage";
    private static final List<TsunamiWave> WAVES = new ArrayList<>();

    public OceanRage() {
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class Events {
        @SubscribeEvent
        public static void registerCommands(RegisterCommandsEvent event) {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("oceanrage")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.literal("start")
                            .executes(context -> start(context.getSource(), WaveSize.MEDIUM, false))
                            .then(Commands.literal("small").executes(context -> start(context.getSource(), WaveSize.SMALL, false)))
                            .then(Commands.literal("medium").executes(context -> start(context.getSource(), WaveSize.MEDIUM, false)))
                            .then(Commands.literal("giant").executes(context -> start(context.getSource(), WaveSize.GIANT, false))))
                    .then(Commands.literal("toxic")
                            .executes(context -> start(context.getSource(), WaveSize.MEDIUM, true))
                            .then(Commands.literal("small").executes(context -> start(context.getSource(), WaveSize.SMALL, true)))
                            .then(Commands.literal("medium").executes(context -> start(context.getSource(), WaveSize.MEDIUM, true)))
                            .then(Commands.literal("giant").executes(context -> start(context.getSource(), WaveSize.GIANT, true))))
                    .then(Commands.literal("stop").executes(context -> stop(context.getSource())))
                    .then(Commands.literal("status").executes(context -> status(context.getSource())));
            event.getDispatcher().register(root);
        }

        @SubscribeEvent
        public static void serverTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Iterator<TsunamiWave> iterator = WAVES.iterator();
            while (iterator.hasNext()) {
                TsunamiWave wave = iterator.next();
                if (wave.tick()) {
                    iterator.remove();
                }
            }
        }
    }

    private static int start(CommandSourceStack source, WaveSize size, boolean toxic) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Use este comando dentro do jogo."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        WavePlan plan = findWavePlan(level, player, size);
        if (plan == null) {
            source.sendFailure(Component.literal("Ocean Rage: nenhuma praia, rio ou oceano encontrado perto de voce."));
            return 0;
        }
        TsunamiWave wave = new TsunamiWave(level, plan.origin, plan.direction, size, toxic, player.blockPosition());
        WAVES.add(wave);
        String type = toxic ? "tsunami toxico" : "tsunami";
        source.sendSuccess(() -> Component.literal("Ocean Rage: " + type + " iniciado no oceano mais proximo."), true);
        level.playSound(null, player.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 3.0F, 0.5F);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        int count = WAVES.size();
        for (TsunamiWave wave : WAVES) {
            wave.clearAll();
        }
        WAVES.clear();
        source.sendSuccess(() -> Component.literal("Ocean Rage: " + count + " onda(s) removida(s)."), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Ocean Rage: " + WAVES.size() + " onda(s) ativa(s)."), false);
        return 1;
    }

    private static WavePlan findWavePlan(ServerLevel level, ServerPlayer player, WaveSize size) {
        BlockPos playerPos = player.blockPosition();
        WaterTarget best = null;
        int radius = size.searchRadius;
        for (int x = playerPos.getX() - radius; x <= playerPos.getX() + radius; x += 5) {
            for (int z = playerPos.getZ() - radius; z <= playerPos.getZ() + radius; z += 5) {
                BlockPos water = findWaterSurface(level, x, z, playerPos.getY());
                if (water == null) {
                    continue;
                }
                double dx = water.getX() - playerPos.getX();
                double dz = water.getZ() - playerPos.getZ();
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < 28.0D) {
                    continue;
                }
                int waterScore = countWater(level, water, 12);
                double score = waterScore * 10.0D + distance * 0.6D;
                if (best == null || score > best.score) {
                    best = new WaterTarget(water, score);
                }
            }
        }
        if (best == null) {
            return null;
        }
        Vec3 waterCenter = new Vec3(best.pos.getX() + 0.5D, best.pos.getY(), best.pos.getZ() + 0.5D);
        Vec3 playerCenter = new Vec3(playerPos.getX() + 0.5D, playerPos.getY(), playerPos.getZ() + 0.5D);
        Vec3 direction = new Vec3(playerCenter.x - waterCenter.x, 0.0D, playerCenter.z - waterCenter.z);
        if (direction.lengthSqr() < 0.01D) {
            direction = new Vec3(0.0D, 0.0D, 1.0D);
        }
        direction = direction.normalize();
        Vec3 origin = waterCenter.add(direction.scale(-size.deepOceanOffset));
        BlockPos originSurface = findWaterSurface(level, floor(origin.x), floor(origin.z), best.pos.getY());
        if (originSurface != null) {
            origin = new Vec3(originSurface.getX() + 0.5D, originSurface.getY(), originSurface.getZ() + 0.5D);
        }
        return new WavePlan(origin, direction);
    }

    private static BlockPos findWaterSurface(ServerLevel level, int x, int z, int nearY) {
        int top = Math.min(level.getMaxBuildHeight() - 2, nearY + 26);
        int bottom = Math.max(level.getMinBuildHeight() + 2, nearY - 42);
        for (int y = top; y >= bottom; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockState(pos).is(Blocks.WATER) && level.getBlockState(pos.above()).isAir()) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static int countWater(ServerLevel level, BlockPos center, int radius) {
        int count = 0;
        for (int x = -radius; x <= radius; x += 3) {
            for (int z = -radius; z <= radius; z += 3) {
                for (int y = -3; y <= 2; y++) {
                    if (level.getBlockState(center.offset(x, y, z)).is(Blocks.WATER)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static BlockPos blockAt(Vec3 vec) {
        return new BlockPos(floor(vec.x), floor(vec.y), floor(vec.z));
    }

    private static class WavePlan {
        final Vec3 origin;
        final Vec3 direction;

        WavePlan(Vec3 origin, Vec3 direction) {
            this.origin = origin;
            this.direction = direction;
        }
    }

    private static class WaterTarget {
        final BlockPos pos;
        final double score;

        WaterTarget(BlockPos pos, double score) {
            this.pos = pos;
            this.score = score;
        }
    }

    private enum WaveSize {
        SMALL(14, 55, 220, 0.28D, 85, 90, 4.0F, 1.0D, 650),
        MEDIUM(24, 85, 340, 0.22D, 140, 130, 6.0F, 1.45D, 950),
        GIANT(36, 135, 520, 0.16D, 220, 185, 9.0F, 2.0D, 1350);

        final int height;
        final int width;
        final int lifetime;
        final double speed;
        final int drainRadius;
        final int searchRadius;
        final float damage;
        final double push;
        final int deepOceanOffset;

        WaveSize(int height, int width, int lifetime, double speed, int drainRadius, int searchRadius, float damage, double push, int deepOceanOffset) {
            this.height = height;
            this.width = width;
            this.lifetime = lifetime;
            this.speed = speed;
            this.drainRadius = drainRadius;
            this.searchRadius = searchRadius;
            this.damage = damage;
            this.push = push;
            this.deepOceanOffset = deepOceanOffset;
        }
    }

    private static class TsunamiWave {
        private final ServerLevel level;
        private final Vec3 origin;
        private final Vec3 direction;
        private final Vec3 side;
        private final WaveSize size;
        private final boolean toxic;
        private final BlockPos focus;
        private final Set<BlockPos> currentWater = new HashSet<>();
        private final Map<BlockPos, BlockState> recededWater = new HashMap<>();
        private int age;
        private int drainCursorX;
        private int drainCursorZ;

        TsunamiWave(ServerLevel level, Vec3 origin, Vec3 direction, WaveSize size, boolean toxic, BlockPos focus) {
            this.level = level;
            this.origin = origin;
            this.direction = direction.normalize();
            this.side = new Vec3(-this.direction.z, 0.0D, this.direction.x).normalize();
            this.size = size;
            this.toxic = toxic;
            this.focus = focus.immutable();
            this.drainCursorX = -size.drainRadius;
            this.drainCursorZ = -size.drainRadius;
        }

        boolean tick() {
            age++;
            if (age <= 150) {
                drainSea();
                warningStage();
                return false;
            }
            clearCurrentWater();
            int waveAge = age - 150;
            if (waveAge > size.lifetime) {
                clearAll();
                announce("A onda se afastou.");
                return true;
            }
            Vec3 center = origin.add(direction.scale(waveAge * size.speed));
            createWaveWall(center, waveAge);
            affectEntities(center);
            spawnParticles(center, waveAge);
            if (waveAge == 1) {
                announce(toxic ? "Uma onda toxica surgiu no horizonte!" : "Uma onda gigante surgiu no horizonte!");
                level.playSound(null, blockAt(center), SoundEvents.GENERIC_EXPLODE, SoundSource.WEATHER, 3.0F, 0.35F);
            }
            if (waveAge % 80 == 0) {
                level.playSound(null, blockAt(center), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 2.5F, 0.45F);
            }
            return false;
        }

        void clearAll() {
            clearCurrentWater();
            for (Map.Entry<BlockPos, BlockState> entry : recededWater.entrySet()) {
                if (level.getBlockState(entry.getKey()).isAir()) {
                    level.setBlock(entry.getKey(), entry.getValue(), 3);
                }
            }
            recededWater.clear();
        }

        private void warningStage() {
            if (age == 5) {
                announce("O oceano comecou a recuar.");
            }
            if (age == 60) {
                announce("A praia esta secando rapido.");
            }
            if (age == 120) {
                announce("Algo enorme esta se formando no mar.");
            }
            if (age % 35 == 0) {
                level.playSound(null, focus, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 1.8F, 0.55F);
            }
            if (age % 8 == 0) {
                level.sendParticles(ParticleTypes.CLOUD, focus.getX() + 0.5D, focus.getY() + 8.0D, focus.getZ() + 0.5D, 90, size.drainRadius * 0.65D, 6.0D, size.drainRadius * 0.65D, 0.05D);
            }
        }

        private void drainSea() {
            int removed = 0;
            int radius = size.drainRadius;
            while (removed < 520) {
                if (drainCursorX > radius) {
                    drainCursorX = -radius;
                    drainCursorZ += 3;
                }
                if (drainCursorZ > radius) {
                    drainCursorZ = -radius;
                    drainCursorX = -radius;
                    return;
                }
                int x = drainCursorX;
                int z = drainCursorZ;
                drainCursorX += 3;
                if (x * x + z * z > radius * radius) {
                    continue;
                }
                BlockPos surface = findWaterSurface(level, focus.getX() + x, focus.getZ() + z, focus.getY());
                if (surface == null) {
                    continue;
                }
                if (removeWaterColumn(surface)) {
                    removed++;
                }
            }
        }

        private boolean removeWaterColumn(BlockPos surface) {
            boolean changed = false;
            for (int y = 0; y >= -4; y--) {
                BlockPos target = surface.offset(0, y, 0);
                BlockState state = level.getBlockState(target);
                if (state.is(Blocks.WATER) && !recededWater.containsKey(target)) {
                    recededWater.put(target.immutable(), state);
                    level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
                    changed = true;
                }
            }
            if (changed && age % 4 == 0) {
                level.sendParticles(ParticleTypes.SPLASH, surface.getX() + 0.5D, surface.getY() + 0.2D, surface.getZ() + 0.5D, 5, 0.5D, 0.1D, 0.5D, 0.15D);
            }
            return changed;
        }

        private void createWaveWall(Vec3 center, int waveAge) {
            int half = size.width / 2;
            int baseY = floor(origin.y);
            int placed = 0;
            for (int forward = -2; forward <= 3; forward++) {
                Vec3 layerCenter = center.add(direction.scale(forward));
                for (int sideways = -half; sideways <= half; sideways++) {
                    int edgeCurve = Math.abs(sideways) / 7;
                    int realHeight = Math.max(5, size.height - edgeCurve);
                    for (int y = 0; y <= realHeight; y++) {
                        if (y > realHeight - 4 && (sideways + y + waveAge) % 3 == 0) {
                            continue;
                        }
                        Vec3 point = layerCenter.add(side.scale(sideways));
                        BlockPos pos = new BlockPos(floor(point.x), baseY + y, floor(point.z));
                        if (canPlaceTemporaryWater(pos)) {
                            level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
                            currentWater.add(pos.immutable());
                            placed++;
                        }
                        if (placed > 6200) {
                            return;
                        }
                    }
                }
            }
        }

        private boolean canPlaceTemporaryWater(BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.is(Blocks.WATER)) {
                return true;
            }
            Block block = state.getBlock();
            return block == Blocks.GRASS || block == Blocks.TALL_GRASS || block == Blocks.FERN || block == Blocks.LARGE_FERN || block == Blocks.DEAD_BUSH || block == Blocks.SEAGRASS || block == Blocks.KELP || block == Blocks.KELP_PLANT || block == Blocks.SNOW;
        }

        private void clearCurrentWater() {
            for (BlockPos pos : currentWater) {
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.WATER)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
            currentWater.clear();
        }

        private void affectEntities(Vec3 center) {
            AABB box = new AABB(center.x - size.width, origin.y - 8, center.z - size.width, center.x + size.width, origin.y + size.height + 10, center.z + size.width);
            List<Entity> entities = level.getEntities(null, box);
            for (Entity entity : entities) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                Vec3 relative = entity.position().subtract(center);
                double distanceSide = Math.abs(relative.dot(side));
                double distanceFront = Math.abs(relative.dot(direction));
                if (distanceSide > size.width / 2.0D + 8.0D || distanceFront > 11.0D) {
                    continue;
                }
                entity.push(direction.x * size.push, 0.32D, direction.z * size.push);
                living.hurt(level.damageSources().generic(), toxic ? size.damage + 3.0F : size.damage);
                living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, toxic ? 2 : 0));
                if (toxic) {
                    living.addEffect(new MobEffectInstance(MobEffects.POISON, 140, 1));
                    living.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0));
                }
            }
        }

        private void spawnParticles(Vec3 center, int waveAge) {
            int count = toxic ? 180 : 130;
            level.sendParticles(ParticleTypes.SPLASH, center.x, origin.y + size.height * 0.58D, center.z, count, size.width * 0.42D, size.height * 0.24D, 3.0D, 0.28D);
            level.sendParticles(ParticleTypes.CLOUD, center.x, origin.y + size.height * 0.95D, center.z, 95, size.width * 0.35D, 4.0D, 2.5D, 0.06D);
            level.sendParticles(ParticleTypes.FALLING_WATER, center.x, origin.y + size.height + 6.0D, center.z, 160, size.width * 0.45D, 7.0D, 3.0D, 0.25D);
            if (toxic) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x, origin.y + size.height * 0.45D, center.z, 85, size.width * 0.32D, size.height * 0.22D, 2.0D, 0.08D);
            }
            if (waveAge % 30 == 0) {
                double offset = (waveAge * 13) % Math.max(1, size.width) - size.width / 2.0D;
                Vec3 flash = center.add(side.scale(offset));
                BlockPos pos = new BlockPos(floor(flash.x), floor(origin.y + size.height + 8.0D), floor(flash.z));
                level.playSound(null, pos, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 1.5F, 0.7F);
                level.sendParticles(ParticleTypes.CLOUD, pos.getX(), pos.getY(), pos.getZ(), 35, 1.5D, 4.0D, 1.5D, 0.03D);
            }
        }

        private void announce(String message) {
            for (ServerPlayer player : level.players()) {
                double dx = player.getX() - (focus.getX() + 0.5D);
                double dy = player.getY() - (focus.getY() + 0.5D);
                double dz = player.getZ() - (focus.getZ() + 0.5D);
                if (dx * dx + dy * dy + dz * dz < 90000.0D) {
                    player.displayClientMessage(Component.literal("Ocean Rage: " + message), true);
                }
            }
        }
    }
}

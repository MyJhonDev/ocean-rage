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
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.01D) {
            horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        }
        Vec3 spawnDirection = horizontal.normalize();
        Vec3 travelDirection = spawnDirection.scale(-1.0D);
        BlockPos base = player.blockPosition();
        Vec3 origin = new Vec3(base.getX() + 0.5D, Math.max(61, base.getY() - 1), base.getZ() + 0.5D).add(spawnDirection.scale(size.spawnDistance));
        TsunamiWave wave = new TsunamiWave(level, origin, travelDirection, size, toxic);
        WAVES.add(wave);
        String type = toxic ? "tsunami toxico" : "tsunami";
        source.sendSuccess(() -> Component.literal("Ocean Rage: " + type + " iniciado. Olhe para a direcao de onde a onda deve vir."), true);
        level.playSound(null, player.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 2.0F, 0.6F);
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

    private enum WaveSize {
        SMALL(9, 21, 48, 0.75D, 36, 4.0F, 1.0D),
        MEDIUM(14, 33, 64, 0.65D, 48, 6.0F, 1.45D),
        GIANT(22, 51, 88, 0.55D, 60, 9.0F, 2.0D);

        final int height;
        final int width;
        final int lifetime;
        final double speed;
        final int spawnDistance;
        final float damage;
        final double push;

        WaveSize(int height, int width, int lifetime, double speed, int spawnDistance, float damage, double push) {
            this.height = height;
            this.width = width;
            this.lifetime = lifetime;
            this.speed = speed;
            this.spawnDistance = spawnDistance;
            this.damage = damage;
            this.push = push;
        }
    }

    private static class TsunamiWave {
        private final ServerLevel level;
        private final Vec3 origin;
        private final Vec3 direction;
        private final Vec3 side;
        private final WaveSize size;
        private final boolean toxic;
        private final Set<BlockPos> currentWater = new HashSet<>();
        private final Map<BlockPos, BlockState> recededWater = new HashMap<>();
        private int age;

        TsunamiWave(ServerLevel level, Vec3 origin, Vec3 direction, WaveSize size, boolean toxic) {
            this.level = level;
            this.origin = origin;
            this.direction = direction.normalize();
            this.side = new Vec3(-this.direction.z, 0.0D, this.direction.x).normalize();
            this.size = size;
            this.toxic = toxic;
        }

        boolean tick() {
            age++;
            if (age <= 42) {
                recedeWater();
                if (age % 10 == 0) {
                    announce("O mar esta recuando...");
                }
                return false;
            }
            clearCurrentWater();
            int waveAge = age - 42;
            if (waveAge > size.lifetime) {
                clearAll();
                announce("A onda perdeu forca.");
                return true;
            }
            Vec3 center = origin.add(direction.scale(waveAge * size.speed));
            createWaveWall(center);
            affectEntities(center);
            spawnParticles(center);
            if (waveAge == 1) {
                announce(toxic ? "Uma onda toxica esta vindo!" : "Uma onda gigante esta vindo!");
                level.playSound(null, BlockPos.containing(center), SoundEvents.GENERIC_EXPLODE, SoundSource.WEATHER, 2.0F, 0.45F);
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

        private void recedeWater() {
            if (age % 3 != 0) {
                return;
            }
            int radius = size.width / 2;
            int length = 24;
            int removed = 0;
            for (int forward = -length; forward <= length; forward++) {
                for (int sideways = -radius; sideways <= radius; sideways += 2) {
                    Vec3 point = origin.add(direction.scale(forward)).add(side.scale(sideways));
                    BlockPos pos = BlockPos.containing(point.x, origin.y, point.z);
                    for (int dy = -2; dy <= 1; dy++) {
                        BlockPos target = pos.offset(0, dy, 0);
                        BlockState state = level.getBlockState(target);
                        if (state.is(Blocks.WATER) && !recededWater.containsKey(target)) {
                            recededWater.put(target.immutable(), state);
                            level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
                            removed++;
                            if (removed > 180) {
                                return;
                            }
                        }
                    }
                }
            }
        }

        private void createWaveWall(Vec3 center) {
            int half = size.width / 2;
            int baseY = (int) Math.round(origin.y);
            int placed = 0;
            for (int sideways = -half; sideways <= half; sideways++) {
                for (int y = 0; y < size.height; y++) {
                    int curve = Math.abs(sideways) / 7;
                    int realHeight = size.height - curve;
                    if (y > realHeight) {
                        continue;
                    }
                    Vec3 point = center.add(side.scale(sideways));
                    BlockPos pos = BlockPos.containing(point.x, baseY + y, point.z);
                    if (canPlaceTemporaryWater(pos)) {
                        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
                        currentWater.add(pos.immutable());
                        placed++;
                    }
                    if (placed > 1600) {
                        return;
                    }
                }
            }
        }

        private boolean canPlaceTemporaryWater(BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                return true;
            }
            Block block = state.getBlock();
            return block == Blocks.GRASS || block == Blocks.TALL_GRASS || block == Blocks.FERN || block == Blocks.LARGE_FERN || block == Blocks.DEAD_BUSH || block == Blocks.SEAGRASS || block == Blocks.KELP || block == Blocks.KELP_PLANT;
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
            AABB box = new AABB(center.x - size.width, origin.y - 4, center.z - size.width, center.x + size.width, origin.y + size.height + 6, center.z + size.width);
            List<Entity> entities = level.getEntities(null, box);
            for (Entity entity : entities) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                double distanceSide = Math.abs(entity.position().subtract(center).dot(side));
                double distanceFront = Math.abs(entity.position().subtract(center).dot(direction));
                if (distanceSide > size.width / 2.0D + 3.0D || distanceFront > 5.0D) {
                    continue;
                }
                entity.push(direction.x * size.push, 0.45D, direction.z * size.push);
                living.hurt(level.damageSources().generic(), toxic ? size.damage + 3.0F : size.damage);
                living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, toxic ? 2 : 0));
                if (toxic) {
                    living.addEffect(new MobEffectInstance(MobEffects.POISON, 120, 1));
                    living.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0));
                }
            }
        }

        private void spawnParticles(Vec3 center) {
            int count = toxic ? 100 : 70;
            level.sendParticles(ParticleTypes.SPLASH, center.x, origin.y + size.height * 0.65D, center.z, count, size.width * 0.35D, size.height * 0.25D, 1.5D, 0.35D);
            level.sendParticles(ParticleTypes.CLOUD, center.x, origin.y + size.height * 0.95D, center.z, 35, size.width * 0.28D, 2.0D, 1.0D, 0.07D);
            if (toxic) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x, origin.y + size.height * 0.45D, center.z, 45, size.width * 0.28D, size.height * 0.2D, 1.2D, 0.08D);
            }
        }

        private void announce(String message) {
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(origin) < 22000.0D) {
                    player.displayClientMessage(Component.literal("Ocean Rage: " + message), true);
                }
            }
        }
    }
}

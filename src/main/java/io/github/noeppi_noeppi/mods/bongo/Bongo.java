package io.github.noeppi_noeppi.mods.bongo;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.github.noeppi_noeppi.mods.bongo.compat.MineMentionIntegration;
import io.github.noeppi_noeppi.mods.bongo.compat.jei.JeiIntegration;
import io.github.noeppi_noeppi.mods.bongo.config.ClientConfig;
import io.github.noeppi_noeppi.mods.bongo.data.Team;
import io.github.noeppi_noeppi.mods.bongo.data.settings.GameSettings;
import io.github.noeppi_noeppi.mods.bongo.event.*;
import io.github.noeppi_noeppi.mods.bongo.network.BongoMessageType;
import io.github.noeppi_noeppi.mods.bongo.task.Task;
import io.github.noeppi_noeppi.mods.bongo.task.TaskType;
import io.github.noeppi_noeppi.mods.bongo.util.Highlight;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import org.moddingx.libx.codec.CodecHelper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class Bongo extends SavedData {

    public static final String ID = BongoMod.getInstance().modid;

    private static Bongo clientInstance;
    private static Minecraft mc = null;

    public static Bongo get(Level level) {
        if (!level.isClientSide) {
            //noinspection resource
            DimensionDataStorage storage = ((ServerLevel) level).getServer().overworld().getDataStorage();
            Bongo bongo = storage.computeIfAbsent(Bongo::new, Bongo::new, ID);
            bongo.level = ((ServerLevel) level).getServer().overworld();
            return bongo;
        } else {
            return clientInstance == null ? new Bongo() : clientInstance;
        }
    }

    public static void updateClient(Bongo bongo, BongoMessageType bongoMessageType) {
        clientInstance = bongo;
        if (mc == null) {
            mc = Minecraft.getInstance();
        }
        if (bongoMessageType == BongoMessageType.START || bongoMessageType == BongoMessageType.STOP || bongoMessageType == BongoMessageType.FORCE) {
            if (mc.player != null) {
                mc.player.refreshDisplayName();
            }
            if (ClientConfig.addItemTooltips.get()) {
                bongo.updateTooltipPredicate();
            }
            if (ClientConfig.modifyJeiBookamrks.get()) {
                if (bongo.running()) {
                    Set<ItemStack> stacks = new HashSet<>();
                    Set<ResourceLocation> advancements = new HashSet<>();
                    for (Task task : bongo.items) {
                        stacks.addAll(task.highlight().flatMap(Highlight::asItem).map(Highlight::element).toList());
                        advancements.addAll(task.highlight().flatMap(Highlight::asAdvancement).map(Highlight::element).toList());
                    }
                    JeiIntegration.setBookmarks(stacks, advancements);
                } else {
                    JeiIntegration.setBookmarks(ImmutableSet.of(), ImmutableSet.of());
                }
            }
        }
    }

    private ServerLevel level;

    private final Map<DyeColor, Team> teams;
    private final List<Task> items;
    private Predicate<ItemStack> tooltipPredicate;
    private final List<UUID> playersInTcMode = new ArrayList<>();
    private GameSettings settings = GameSettings.DEFAULT;
    private boolean active;
    private boolean running;
    private boolean teamWon;
    private long runningSince = 0;
    private long ranUntil = 0;
    private long runningUntil = 0;
    private int taskAmountOutOfTime = -1;
    private DyeColor winningTeam;
    private int countdownTicks = -1;
    private final Set<UUID> countdownPlayers = new HashSet<>();
    private ServerLevel countdownLevel = null;

    public Bongo() {
        level = null;
        
        ImmutableMap.Builder<DyeColor, Team> teamBuilder = ImmutableMap.builder();
        for (DyeColor dc : DyeColor.values()) {
            teamBuilder.put(dc, new Team(this, dc));
        }
        teams = teamBuilder.build();
        items = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            items.add(Task.EMPTY);
        }
        tooltipPredicate = null;
        active = false;
        running = false;
        teamWon = false;
        winningTeam = null;
    }
    
    public Bongo(CompoundTag nbt) {
        this();
        this.load(nbt);
    }
    

    public Team getTeam(DyeColor color) {
        return teams.get(color);
    }

    @Nullable
    public Team getTeam(Player player) {
        return getTeam(player.getGameProfile().getId());
    }

    @Nullable
    public Team getTeam(UUID uid) {
        for (Team team : teams.values()) {
            if (team.hasPlayer(uid))
                return team;
        }
        return null;
    }

    public Set<Team> getTeams() {
        return Set.copyOf(teams.values());
    }

    public boolean active() {
        return active;
    }

    public void activate() {
        this.active = true;
        this.running = false;
        this.teamWon = false;
        if (level != null) {
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                player.refreshDisplayName();
                player.refreshTabListName();
            }
        }
        setDirty();
    }

    public boolean running() {
        return running;
    }

    public void startCountdown(ServerLevel level, List<ServerPlayer> players) {
        this.countdownTicks = 600; // 30 seconds
        this.countdownLevel = level;
        this.countdownPlayers.clear();

        CountdownOverlay.startCountdown(countdownTicks);

        for (ServerPlayer player : players) {
            this.countdownPlayers.add(player.getUUID());

            player.setGameMode(GameType.ADVENTURE);
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 600, 250, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.JUMP, 600, 250, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 600, 0, false, false));
        }
    }

    public void tickCountdown() {
        if (countdownTicks < 0 || countdownLevel == null) return;

        countdownTicks--;
        if (countdownTicks == 0) {
            List<ServerPlayer> playersToUnfreeze = countdownLevel.getServer().getPlayerList().getPlayers().stream()
                    .filter(p -> countdownPlayers.contains(p.getUUID()))
                    .toList();

            for (ServerPlayer player : playersToUnfreeze) {
                player.removeAllEffects();
                player.setGameMode(GameType.SURVIVAL);
                player.sendSystemMessage(Component.literal("Go!"));
            }

            countdownTicks = -1;
            countdownPlayers.clear();
            countdownLevel = null;
        }
    }

    public void start() {
        this.active = true;
        this.running = true;
        this.teamWon = false;
        this.runningSince = System.currentTimeMillis();
        this.ranUntil = 0;
        if (settings.game().time().limit().isPresent()) {
            this.runningUntil = System.currentTimeMillis() + (1000L * settings.game().time().limit().getAsInt());
        } else {
            this.runningUntil = 0;
        }
        this.taskAmountOutOfTime = -1;
        Set<UUID> uids = new HashSet<>();
        for (Team team : teams.values()) {
            settings.equipment().equip(team, true);
            team.resetCompleted(true);
            team.resetLocked(true);
            team.teleportsLeft(getSettings().game().teleportsPerTeam());
            uids.addAll(team.getPlayers());
        }

        if (level != null) {
            BongoPickLevelEvent event = new BongoPickLevelEvent(this, level);
            MinecraftForge.EVENT_BUS.post(event);
            ServerLevel gameLevel = event.getLevel();
            MinecraftForge.EVENT_BUS.post(new BongoStartEvent.Level(this, gameLevel));
            level.getServer().getPlayerList().getPlayers().forEach(player -> {
                if (uids.contains(player.getGameProfile().getId())) {
                    MinecraftForge.EVENT_BUS.post(new BongoStartEvent.Player(this, gameLevel, player));
                }
            });

            Random random = new Random();
            BlockPos gameCenter = null;
            int tries = 0;
            int maxTries = 100;
            int teamSpreadRadius = 500;

            while (tries < maxTries) {
                int centerX = random.nextInt(1_000_000) - 500_000;
                int centerZ = random.nextInt(1_000_000) - 500_000;

                BlockPos probe = new BlockPos(centerX, 0, centerZ);
                gameLevel.getChunkSource().getChunk(centerX >> 4, centerZ >> 4, ChunkStatus.FULL, true);
                int centerY = gameLevel.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ);

                BlockPos potentialCenter = new BlockPos(centerX, centerY, centerZ);
                ResourceKey<Biome> biomeKey = gameLevel.getBiome(potentialCenter).unwrapKey().orElse(null);

                if (biomeKey != null && !biomeKey.location().getPath().contains("ocean")) {
                    gameCenter = potentialCenter;
                    break;
                }
                tries++;
            }

            if (gameCenter == null) {
                gameCenter = new BlockPos(0, 100, 0);
            }

            // Schedule teleporting to ensure chunks are fully loaded
            BlockPos finalGameCenter = gameCenter;
            level.getServer().execute(() -> {
                for (Team team : getTeams()) {
                    if (!team.isEmpty()) {
                        int x, y, z;

                        // Find valid spawn for this team
                        while (true) {
                            int offsetX = random.nextInt(teamSpreadRadius * 2 + 1) - teamSpreadRadius;
                            int offsetZ = random.nextInt(teamSpreadRadius * 2 + 1) - teamSpreadRadius;
                            x = finalGameCenter.getX() + offsetX;
                            z = finalGameCenter.getZ() + offsetZ;

                            // Load chunk and get height
                            gameLevel.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
                            y = gameLevel.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                            BlockPos posBelow = new BlockPos(x, y - 1, z);

                            if (!gameLevel.getFluidState(posBelow).is(FluidTags.WATER)) {
                                break;
                            }
                        }

                        int finalX = x;
                        int finalY = Math.max(y, 64); // avoid void
                        int finalZ = z;
                        BlockPos teamCenter = new BlockPos(finalX, finalY, finalZ);

                        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                            if (team.hasPlayer(player)) {
                                teleportWhenChunkReady(gameLevel, player, teamCenter);
                                player.setRespawnPosition(
                                        gameLevel.dimension(),
                                        teamCenter,
                                        0.0F,
                                        true,
                                        true
                                );
                            }
                        }
                    }
                }

                // Post-teleport setup
                for (ServerPlayer player : gameLevel.players()) {
                    player.setHealth(player.getMaxHealth());
                    player.getFoodData().setFoodLevel(20);
                    player.getFoodData().setSaturation(5.0F);
                    player.setAbsorptionAmount(0.0F);
                    player.clearFire();
                    player.removeAllEffects(); // Proper removal of all effects
                    player.setGameMode(GameType.ADVENTURE);
                }

                startCountdown(level, gameLevel.players());
            });
        }

        setChanged(true);
        if (level != null) {
            BongoMod.getNetwork().updateBongo(level, BongoMessageType.START);
        }
    }

    public void teleportWhenChunkReady(ServerLevel level, ServerPlayer player, BlockPos pos) {
        BlockPos chunkPos = new BlockPos(pos.getX(), 0, pos.getZ());

        level.getServer().execute(() -> {
            ChunkAccess chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || !(chunk instanceof LevelChunk)) {
                // Not ready yet, retry next tick
                level.getServer().execute(() -> teleportWhenChunkReady(level, player, pos));
                return;
            }

            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
            y = Math.max(y, 64); // avoid void
            BlockPos safePos = new BlockPos(pos.getX(), y, pos.getZ());

            player.teleportTo(level, safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5,
                    player.getYRot(), player.getXRot());
            player.setRespawnPosition(level.dimension(), safePos, 0.0F, true, true);
        });
    }
    
    public void stop() {
        CountdownOverlay.stopCountdown();
        Set<UUID> uids = new HashSet<>();
        for (Team team : teams.values()) {
            uids.addAll(team.getPlayers());
        }
        this.active = false;
        this.running = false;
        this.teamWon = false;
        this.winningTeam = null;
        this.settings = GameSettings.DEFAULT;
        playersInTcMode.clear();
        setChanged(true);
        if (level != null) {
            MinecraftForge.EVENT_BUS.post(new BongoStopEvent.Level(this, this.level));
            for (ServerPlayer player: level.getServer().getPlayerList().getPlayers()) {
                if (player != null) {
                    player.setHealth(player.getMaxHealth());
                    player.getFoodData().setFoodLevel(20);
                    player.getFoodData().setSaturation(5.0F);
                    player.setAbsorptionAmount(0.0F);
                    player.clearFire();
                    player.removeAllEffects();
                    MinecraftForge.EVENT_BUS.post(new BongoStopEvent.Player(this, player.serverLevel(), player));
                    updateMentions(player);
                    player.refreshDisplayName();
                    player.refreshTabListName();
                }
            }
            BongoMod.getNetwork().updateBongo(level, BongoMessageType.STOP);
        }
    }

    public boolean won() {
        return teamWon;
    }

    public Team winningTeam() {
        return teams.get(winningTeam);
    }

    @Nonnull
    @Override
    public CompoundTag save(@Nonnull CompoundTag compound) {
        compound.putBoolean("active", active);
        compound.putBoolean("running", running);
        compound.putBoolean("teamWon", teamWon);
        compound.putLong("runningSince", runningSince);
        compound.putLong("ranUntil", ranUntil);
        compound.putLong("runningUntil", runningUntil);
        compound.putInt("taskAmountOutOfTime", taskAmountOutOfTime);

        if (winningTeam != null) {
            compound.putInt("winningTeam", winningTeam.ordinal());
        }

        safe(() -> CodecHelper.NBT.write(GameSettings.CODEC, settings)).ifPresent(elem -> compound.put("settings", elem));

        for (DyeColor dc : DyeColor.values()) {
            compound.put("team_" + dc.getSerializedName(), getTeam(dc).serializeNBT());
        }

        ListTag itemList = new ListTag();
        for (Task item : items) {
            itemList.add(safe(() -> CodecHelper.NBT.write(Task.CODEC, item)).orElse(StringTag.valueOf("invalid")));
        }
        compound.put("items", itemList);

        ListTag tcPlayers = new ListTag();
        for (UUID uid : playersInTcMode) {
            CompoundTag playerNbt = new CompoundTag();
            playerNbt.putUUID("player", uid);
            tcPlayers.add(playerNbt);
        }
        compound.put("teamchat", tcPlayers);

        return compound;
    }

    public void load(@Nonnull CompoundTag nbt) {
        active = nbt.getBoolean("active");
        running = nbt.getBoolean("running");
        teamWon = nbt.getBoolean("teamWon");
        runningSince = nbt.getLong("runningSince");
        ranUntil = nbt.getLong("ranUntil");
        runningUntil = nbt.getLong("runningUntil");
        taskAmountOutOfTime = nbt.getInt("taskAmountOutOfTime");
        
        if (nbt.contains("winningTeam")) {
            winningTeam = DyeColor.values()[nbt.getInt("winningTeam")];
        } else {
            winningTeam = null;
        }
        
        settings = GameSettings.DEFAULT;
        if (nbt.contains("settings")) {
            settings = safe(() -> CodecHelper.NBT.read(GameSettings.CODEC, nbt.get("settings"))).orElse(GameSettings.DEFAULT);
        }
        
        for (DyeColor dc : DyeColor.values()) {
            if (nbt.contains("team_" + dc.getSerializedName(), Tag.TAG_COMPOUND)) {
                getTeam(dc).deserializeNBT(nbt.getCompound("team_" + dc.getSerializedName()));
            }
        }

        if (nbt.contains("items", Tag.TAG_LIST)) {
            ListTag itemList = nbt.getList("items", Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++) {
                if (i >= itemList.size()) {
                    items.set(i, Task.EMPTY);
                } else if (itemList.get(i) instanceof StringTag && "invalid".equals(itemList.getString(i))) {
                    items.set(i, Task.EMPTY);
                } else {
                    int idx = i;
                    items.set(i, safe(() -> CodecHelper.NBT.read(Task.CODEC, itemList.get(idx))).orElse(Task.EMPTY));
                }
            }
        } else {
            clearItems();
        }

        playersInTcMode.clear();
        if (nbt.contains("teamchat", Tag.TAG_LIST)) {
            ListTag tcPlayers = nbt.getList("teamchat", Tag.TAG_COMPOUND);
            for (int i = 0; i < tcPlayers.size(); i++) {
                CompoundTag playerNbt = tcPlayers.getCompound(i);
                UUID uid = playerNbt.getUUID("player");
                playersInTcMode.add(uid);
            }
        }
    }
    
    private static <T> Optional<T> safe(Callable<T> value) {
        try {
            return Optional.of(value.call());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void reset() {
        for (Team team : teams.values()) {
            team.reset(true);
        }

        active = false;
        running = false;
        runningSince = 0;
        ranUntil = 0;
        teamWon = false;
        winningTeam = null;
        clearItems(true);
        playersInTcMode.clear();
        settings = GameSettings.DEFAULT;
        setDirty();
    }

    public GameSettings getSettings() {
        return settings == null ? GameSettings.DEFAULT : settings;
    }

    public void setSettings(GameSettings settings, boolean suppressBingoSync) {
        this.settings = settings == null ? GameSettings.DEFAULT : settings;
        setChanged(suppressBingoSync);
    }

    public void clearItems() {
        clearItems(false);
    }

    public void clearItems(boolean suppressBingoSync) {
        items.replaceAll(t -> Task.EMPTY);
        setChanged(suppressBingoSync);
    }

    public Task task(int slot) {
        Task task = items.get(slot);
        if (task == null) {
            return Task.EMPTY;
        }
        return task;
    }

    public List<Task> tasks() {
        return Collections.unmodifiableList(items);
    }

    // Checks whether this player can complete tasks. This should be checked before for example looping
    // through evey item in the players inventory.
    public boolean canCompleteTasks(Player player) {
        return running() && getTeam(player) != null;
    }
    
    public <C> void checkCompleted(TaskType<C> type, Player player, C compare) {
        if (!running || player.level().isClientSide) return;
        Team team = getTeam(player);
        if (team != null && player instanceof ServerPlayer serverPlayer) {
            for (int i = 0; i < items.size(); i++) {
                if (!team.completed(i) && !team.locked(i) && task(i).getType() == type) {
                    Task.CompletionState state = items.get(i).shouldComplete(type, serverPlayer, compare);
                    if (state.shouldComplete) {
                        team.complete(i);
                        if (getSettings().game().consumeItems()) {
                            task(i).consume(type, serverPlayer, compare);
                        }
                        if (getSettings().game().lockout()) {
                            for (Team t : teams.values()) {
                                if (!t.completed(i)) {
                                    t.lock(i);
                                }
                            }
                        }
                        MinecraftForge.EVENT_BUS.post(new BongoTaskEvent(this, serverPlayer.serverLevel(), serverPlayer, task(i)));
                    } else if (state.shouldLock) {
                        team.lock(i);
                        // inverted tasks are completed for everyone if the first player fails it
                        if (getSettings().game().lockout()) {
                            for (Team t : teams.values()) {
                                if (!t.locked(i)) {
                                    t.complete(i);
                                }
                            }
                        }
                        MinecraftForge.EVENT_BUS.post(new BongoTaskEvent(this, serverPlayer.serverLevel(), serverPlayer, task(i)));
                    }
                }
            }
        }
        checkWin();
    }

    public void checkWin() {
        if (settings.game().time().limited() && System.currentTimeMillis() > runningUntil) {
            if (taskAmountOutOfTime >= 0) {
                for (Team team : teams.values()) {
                    if (team.completion().count() >= taskAmountOutOfTime) {
                        setWin(team);
                        return;
                    }
                }
            } else {
                int max = 0;
                for (Team team : teams.values()) {
                    int amount = team.completion().count();
                    if (amount > max) max = amount;
                }
                Team winning = null;
                for (Team team : teams.values()) {
                    if (team.completion().count() >= max) {
                        if (winning == null) {
                            winning = team;
                        } else {
                            taskAmountOutOfTime = Math.min(25, max + 1);
                            setDirty();
                            return;
                        }
                    }
                }
                if (winning == null) {
                    taskAmountOutOfTime = Math.min(25, max + 1);
                    setDirty();
                } else {
                    setWin(winning);
                }
                return;
            }
        }
        for (Team team : teams.values()) {
            if (getSettings().game().winCondition().won(team.completion())) {
                setWin(team);
                return;
            }
        }
    }
    
    private void setWin(Team team) {
        running = false;
        teamWon = true;
        winningTeam = team.color;
        if (level != null) {
            MinecraftForge.EVENT_BUS.post(new BongoWinEvent(this, level, team));
        }
        ranUntil = System.currentTimeMillis();
        playersInTcMode.clear();
        setDirty();
    }

    public long runningSince() {
        return runningSince;
    }

    public long ranUntil() {
        return ranUntil;
    }

    public void setChanged(boolean suppressBingoSync) {
        super.setDirty();
        if (level != null && !suppressBingoSync) {
            BongoMod.getNetwork().updateBongo(level);
        }
    }

    public boolean toggleTeamChat(Player player) {
        return toggleTeamChat(player.getGameProfile().getId());
    }

    public boolean toggleTeamChat(UUID uid) {
        if (playersInTcMode.contains(uid)) {
            playersInTcMode.remove(uid);
            setDirty();
            return false;
        } else {
            playersInTcMode.add(uid);
            setDirty();
            return true;
        }
    }

    public boolean teamChat(Player player) {
        return teamChat(player.getGameProfile().getId());
    }

    public boolean teamChat(UUID uid) {
        return playersInTcMode.contains(uid);
    }

    @Override
    public void setDirty() {
        this.setChanged(false);
    }

    public void setTasks(List<Task> tasks) {
        for (int i = 0; i < 25; i++) {
            items.set(i, tasks.get(i));
            if (level != null) {
                tasks.get(i).sync(level.getServer(), null);
            }
        }
        updateTooltipPredicate();
        setChanged(true);
        if (level != null) {
            BongoMod.getNetwork().updateBongo(level, BongoMessageType.CREATE);
        }
    }

    private void updateTooltipPredicate() {
        if (level == null) {
            // We cache the predicates to reduce lag
            List<Predicate<ItemStack>> predicates = this.items.stream()
                    .flatMap(Task::highlight)
                    .flatMap(Highlight::asItem)
                    .map(Highlight.Item::predicate)
                    .toList();
            tooltipPredicate = stack -> {
                for (Predicate<ItemStack> predicate : predicates) {
                    if (predicate.test(stack))
                        return true;
                }
                return false;
            };
        }
    }

    public <T> Stream<T> getElementsOf(TaskType<T> type) {
        return items.stream().flatMap(task -> task.getElement(type).stream());
    }

    public boolean isTooltipStack(ItemStack stack) {
        if (tooltipPredicate == null) {
            updateTooltipPredicate();
        }
        return !stack.isEmpty() && tooltipPredicate.test(stack);
    }
    
    public void updateMentions(UUID player) {
        if (this.level != null) {
            ServerPlayer entity = this.level.getServer().getPlayerList().getPlayer(player);
            if (entity != null) {
                updateMentions(entity);
            }
        }
    }
    
    public void updateMentions(ServerPlayer player) {
        if (ModList.get().isLoaded("minemention")) {
            MineMentionIntegration.availabilityChange(player);
        }
    }
    
    public long runningUntil() {
        return runningUntil;
    }
    
    public int tasksForWin() {
        return taskAmountOutOfTime;
    }
}

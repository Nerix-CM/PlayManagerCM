package com.nerix.playmanagercm;

import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class PlayManagerCM extends JavaPlugin implements Listener {

    private FileConfiguration config;
    private File markersFile;
    private FileConfiguration markersConfig;
    private File ranksFile;
    private FileConfiguration ranksConfig;

    private final Map<UUID, UUID> compassTargets = new HashMap<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();
    private final Map<UUID, Boolean> trackingDeath = new HashMap<>();
    private final Map<UUID, String> trackingMarker = new HashMap<>();
    private final Map<UUID, Long> shiftClickCooldowns = new HashMap<>();
    private final Map<UUID, BossBar> playerBossBars = new HashMap<>();
    private final Map<String, Location> markers = new HashMap<>();
    private final Map<UUID, Long> playerJoinTimes = new HashMap<>();

    // AFK System
    private final Map<UUID, Long> lastMovementTime = new HashMap<>();
    private final Map<UUID, Boolean> isAfk = new HashMap<>();
    private final Map<UUID, Location> lastLocation = new HashMap<>();
    private final Map<UUID, Float> lastYaw = new HashMap<>();
    private final Map<UUID, Float> lastPitch = new HashMap<>();

    // Rank System
    private final Map<String, RankInfo> ranks = new HashMap<>();
    private final Map<UUID, String> playerRanks = new HashMap<>();
    private final Map<UUID, PermissionAttachment> playerPermissions = new HashMap<>();

    private NamespacedKey compassKey;
    private NamespacedKey targetKey;
    private NamespacedKey deathTrackingKey;
    private NamespacedKey markerKey;

    private int compassUpdateInterval;
    private int bossbarUpdateInterval;
    private int actionbarUpdateInterval;

    private int afkTimeSeconds;
    private String afkStatusText;
    private boolean afkEnabled;
    private String afkBecameMessage;
    private String afkLeftMessage;

    private String tabFormat;
    private String chatFormat;
    private String headFormat;
    private String chatSeparator;

    // Private messages settings
    private String privateMessageSenderFormat;
    private String privateMessageReceiverFormat;
    private boolean privateMessageSoundEnabled;
    private String privateMessageSoundType;
    private float privateMessageSoundVolume;
    private float privateMessageSoundPitch;

    @Override
    public void onEnable() {
        // Создаем папку плагина если не существует
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // Инициализация Keys
        compassKey = new NamespacedKey(this, "tracking_compass");
        targetKey = new NamespacedKey(this, "target_player");
        deathTrackingKey = new NamespacedKey(this, "death_tracking");
        markerKey = new NamespacedKey(this, "target_marker");

        // Сохраняем конфиг по умолчанию
        saveDefaultConfig();

        // Перезагружаем конфиг
        reloadConfig();
        config = getConfig();
        loadConfigValues();

        // Загрузка маркеров (создает файл если не существует)
        loadMarkers();

        // Загрузка рангов (создает файл если не существует)
        loadRanks();

        // Регистрация крафтов
        registerRecipes();

        getServer().getPluginManager().registerEvents(this, this);
        startUpdateTasks();
        startAfkCheckTask();

        getLogger().info("PlayManagerCM v2.2 enabled for Minecraft 1.20-1.21.6!");
        getLogger().info("Data folder: " + getDataFolder().getAbsolutePath());
    }

    @Override
    public void onDisable() {
        // Сохраняем маркеры
        saveMarkers();
        saveRanks();

        // Очищаем пермишионы
        for (PermissionAttachment attachment : playerPermissions.values()) {
            attachment.remove();
        }

        compassTargets.clear();
        deathLocations.clear();
        trackingDeath.clear();
        trackingMarker.clear();
        shiftClickCooldowns.clear();
        playerJoinTimes.clear();
        markers.clear();
        lastMovementTime.clear();
        isAfk.clear();
        lastLocation.clear();
        lastYaw.clear();
        lastPitch.clear();
        playerRanks.clear();
        playerPermissions.clear();
        ranks.clear();

        // Удаляем все BossBars
        for (BossBar bossBar : playerBossBars.values()) {
            bossBar.removeAll();
        }
        playerBossBars.clear();

        getLogger().info("PlayManagerCM disabled!");
    }

    private void registerRecipes() {
        if (!config.getBoolean("crafting.experience-bottle.enabled", true)) {
            getLogger().info("Experience bottle crafting is disabled in config");
            return;
        }

        int resultAmount = config.getInt("crafting.experience-bottle.result-amount", 3);

        // Создаем рецепт для бутылочки опыта
        NamespacedKey key = new NamespacedKey(this, "experience_bottle_craft");

        // Проверяем, не зарегистрирован ли уже рецепт
        if (Bukkit.getRecipe(key) != null) {
            Bukkit.removeRecipe(key);
        }

        ShapedRecipe recipe = new ShapedRecipe(key, new ItemStack(Material.EXPERIENCE_BOTTLE, resultAmount));

        // Устанавливаем форму крафта
        recipe.shape("RRR", "LLL", "BBB");

        // Устанавливаем ингредиенты
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('L', Material.LAPIS_LAZULI);
        recipe.setIngredient('B', Material.GLASS_BOTTLE);

        // Регистрируем рецепт
        Bukkit.addRecipe(recipe);

        getLogger().info("Registered experience bottle crafting recipe! Result amount: " + resultAmount);
    }

    private void loadConfigValues() {
        compassUpdateInterval = config.getInt("compass.update-interval", 1) * 20;
        bossbarUpdateInterval = config.getInt("bossbar.update-interval", 1) * 20;
        actionbarUpdateInterval = config.getInt("actionbar.update-interval", 1) * 20;

        // AFK настройки
        afkEnabled = config.getBoolean("afk.enabled", true);
        afkTimeSeconds = config.getInt("afk.afk-time-seconds", 120);
        afkStatusText = ChatColor.translateAlternateColorCodes('&', config.getString("afk.status-text", "&cАФК"));
        afkBecameMessage = ChatColor.translateAlternateColorCodes('&', config.getString("afk.messages.became-afk", "&eВы были признаны АФК"));
        afkLeftMessage = ChatColor.translateAlternateColorCodes('&', config.getString("afk.messages.left-afk", "&aВы больше не АФК"));

        // Форматы отображения
        tabFormat = config.getString("player_tab", "%rank% %name% %status%");
        chatFormat = config.getString("player_chat", "%rank%%name% %status%");
        headFormat = config.getString("player_head", "%rank% %name% %status%");
        chatSeparator = config.getString("chat-separator", "&6>> &f");

        // Настройки личных сообщений
        privateMessageSenderFormat = config.getString("private-messages.format-sender", "&7[&fВы&7 -> &f{target}&7]: &f{message}");
        privateMessageReceiverFormat = config.getString("private-messages.format-receiver", "&7[&f{sender}&7 -> &fВам&7]: &f{message}");
        privateMessageSoundEnabled = config.getBoolean("private-messages.sound-enabled", true);
        privateMessageSoundType = config.getString("private-messages.sound-type", "BLOCK_NOTE_BLOCK_PLING");
        privateMessageSoundVolume = (float) config.getDouble("private-messages.sound-volume", 1.0);
        privateMessageSoundPitch = (float) config.getDouble("private-messages.sound-pitch", 1.0);
    }

    private void loadRanks() {
        ranksFile = new File(getDataFolder(), "rank.yml");

        // Если файл не существует, создаем из ресурса
        if (!ranksFile.exists()) {
            try {
                // Пытаемся скопировать из ресурсов
                InputStream inputStream = getResource("rank.yml");
                if (inputStream != null) {
                    Files.copy(inputStream, ranksFile.toPath());
                    getLogger().info("Created rank.yml from resources");
                } else {
                    // Если нет в ресурсах, создаем пустой
                    ranksFile.createNewFile();
                    getLogger().info("Created empty rank.yml");
                }
            } catch (IOException e) {
                getLogger().warning("Failed to create rank.yml: " + e.getMessage());
                e.printStackTrace();
            }
        }

        ranksConfig = YamlConfiguration.loadConfiguration(ranksFile);

        // Очищаем старые ранги
        ranks.clear();

        if (ranksConfig.contains("ranks")) {
            for (String rankName : ranksConfig.getConfigurationSection("ranks").getKeys(false)) {
                String prefix = ranksConfig.getString("ranks." + rankName + ".prefix", "");
                int rankWeight = ranksConfig.getInt("ranks." + rankName + ".rank", 100);
                List<String> parentRanks = ranksConfig.getStringList("ranks." + rankName + ".parent");
                List<String> permissions = ranksConfig.getStringList("ranks." + rankName + ".permissions");
                List<String> players = ranksConfig.getStringList("ranks." + rankName + ".players");

                RankInfo rankInfo = new RankInfo(rankName, prefix, rankWeight, parentRanks, permissions);
                rankInfo.players.addAll(players);
                ranks.put(rankName, rankInfo);
            }
        }

        // Убедимся что есть дефолтный ранг
        if (!ranks.containsKey("default")) {
            RankInfo defaultRank = new RankInfo("default", "", 100, new ArrayList<>(), new ArrayList<>());
            ranks.put("default", defaultRank);

            // Сохраняем дефолтный ранг в файл
            ranksConfig.set("ranks.default.prefix", "");
            ranksConfig.set("ranks.default.rank", 100);
            ranksConfig.set("ranks.default.parent", new ArrayList<>());
            ranksConfig.set("ranks.default.permissions", new ArrayList<>());
            ranksConfig.set("ranks.default.players", new ArrayList<>());
            try {
                ranksConfig.save(ranksFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        getLogger().info("Loaded " + ranks.size() + " ranks from " + ranksFile.getAbsolutePath());
    }

    private void saveRanks() {
        if (ranksConfig == null) return;

        // Очищаем старые данные
        ranksConfig.set("ranks", null);

        for (Map.Entry<String, RankInfo> entry : ranks.entrySet()) {
            RankInfo rank = entry.getValue();
            ranksConfig.set("ranks." + entry.getKey() + ".prefix", rank.prefix);
            ranksConfig.set("ranks." + entry.getKey() + ".rank", rank.rankWeight);
            ranksConfig.set("ranks." + entry.getKey() + ".parent", rank.parentRanks);
            ranksConfig.set("ranks." + entry.getKey() + ".permissions", rank.permissions);
            ranksConfig.set("ranks." + entry.getKey() + ".players", rank.players);
        }

        try {
            ranksConfig.save(ranksFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setupPlayerRank(Player player) {
        UUID uuid = player.getUniqueId();
        String rankName = playerRanks.get(uuid);

        if (rankName == null) {
            // Проверяем, есть ли игрок в списках рангов
            rankName = "default";
            for (Map.Entry<String, RankInfo> entry : ranks.entrySet()) {
                if (entry.getValue().players.contains(player.getName())) {
                    rankName = entry.getKey();
                    break;
                }
            }
            playerRanks.put(uuid, rankName);
        }

        // Удаляем старые пермишионы
        if (playerPermissions.containsKey(uuid)) {
            playerPermissions.get(uuid).remove();
        }

        // Создаем новое прикрепление
        PermissionAttachment attachment = player.addAttachment(this);
        playerPermissions.put(uuid, attachment);

        // Добавляем пермишионы из ранга и его родителей
        Set<String> allPermissions = new HashSet<>();
        addPermissionsRecursive(rankName, allPermissions);

        for (String permission : allPermissions) {
            if (permission.equals("*")) {
                attachment.setPermission("*", true);
            } else if (!permission.isEmpty()) {
                attachment.setPermission(permission, true);
            }
        }

        // Обновляем отображение
        updatePlayerDisplay(player);
    }

    private void addPermissionsRecursive(String rankName, Set<String> permissionsSet) {
        RankInfo rank = ranks.get(rankName);
        if (rank == null) return;

        permissionsSet.addAll(rank.permissions);

        for (String parentRank : rank.parentRanks) {
            if (!parentRank.isEmpty()) {
                addPermissionsRecursive(parentRank, permissionsSet);
            }
        }
    }

    private String getPlayerRankName(Player player) {
        return playerRanks.getOrDefault(player.getUniqueId(), "default");
    }

    private String getPlayerRankPrefix(Player player) {
        String rankName = getPlayerRankName(player);
        RankInfo rank = ranks.get(rankName);
        if (rank != null && !rank.prefix.isEmpty()) {
            return ChatColor.translateAlternateColorCodes('&', rank.prefix) + " ";
        }
        return "";
    }

    // Основной метод для получения отображаемого имени с цветами
    private String getColoredDisplay(Player player, String format) {
        String rankPrefix = getPlayerRankPrefix(player);
        String name = player.getName();
        String status = "";

        if (isAfk.getOrDefault(player.getUniqueId(), false)) {
            status = afkStatusText;
        }

        String display = format
                .replace("%rank%", rankPrefix)
                .replace("%name%", name)
                .replace("%status%", status);

        return ChatColor.translateAlternateColorCodes('&', display);
    }

    private void updatePlayerDisplay(Player player) {
        // Обновляем отображение в табе - с цветами
        String tabDisplay = getColoredDisplay(player, tabFormat);
        player.setPlayerListName(tabDisplay);

        // Обновляем отображение над головой - с цветами
        String headDisplay = getColoredDisplay(player, headFormat);
        player.setDisplayName(headDisplay);

        // Устанавливаем пользовательское имя
        player.setCustomName(headDisplay);
        player.setCustomNameVisible(true);
    }

    private void startAfkCheckTask() {
        if (!afkEnabled) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();

                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    Long lastMove = lastMovementTime.get(uuid);

                    if (lastMove != null) {
                        long afkTime = (currentTime - lastMove) / 1000;
                        boolean shouldBeAfk = afkTime >= afkTimeSeconds;
                        boolean currentlyAfk = isAfk.getOrDefault(uuid, false);

                        if (shouldBeAfk && !currentlyAfk) {
                            isAfk.put(uuid, true);
                            updatePlayerDisplay(player);
                            player.sendMessage(afkBecameMessage);
                        } else if (!shouldBeAfk && currentlyAfk) {
                            isAfk.put(uuid, false);
                            updatePlayerDisplay(player);
                            player.sendMessage(afkLeftMessage);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 20L * config.getInt("afk.check-interval", 10), 20L * config.getInt("afk.check-interval", 10));
    }

    private void updatePlayerActivity(Player player) {
        if (!afkEnabled) return;

        UUID uuid = player.getUniqueId();
        Location currentLoc = player.getLocation();
        Location lastLoc = lastLocation.get(uuid);
        float currentYaw = player.getEyeLocation().getYaw();
        float currentPitch = player.getEyeLocation().getPitch();
        Float lastYawValue = lastYaw.get(uuid);
        Float lastPitchValue = lastPitch.get(uuid);

        boolean moved = false;

        if (lastLoc == null || currentLoc.getBlockX() != lastLoc.getBlockX() ||
                currentLoc.getBlockY() != lastLoc.getBlockY() || currentLoc.getBlockZ() != lastLoc.getBlockZ()) {
            moved = true;
        }

        if (lastYawValue != null && lastPitchValue != null) {
            if (Math.abs(currentYaw - lastYawValue) > 5 || Math.abs(currentPitch - lastPitchValue) > 5) {
                moved = true;
            }
        } else {
            moved = true;
        }

        if (moved) {
            lastMovementTime.put(uuid, System.currentTimeMillis());
            lastLocation.put(uuid, currentLoc.clone());
            lastYaw.put(uuid, currentYaw);
            lastPitch.put(uuid, currentPitch);

            if (isAfk.getOrDefault(uuid, false)) {
                isAfk.put(uuid, false);
                updatePlayerDisplay(player);
                player.sendMessage(afkLeftMessage);
            }
        }
    }

    private void loadMarkers() {
        markersFile = new File(getDataFolder(), "markers.yml");

        // Создаем файл если не существует
        if (!markersFile.exists()) {
            try {
                markersFile.createNewFile();
                getLogger().info("Created markers.yml");
            } catch (IOException e) {
                getLogger().warning("Failed to create markers.yml: " + e.getMessage());
                e.printStackTrace();
            }
        }

        markersConfig = YamlConfiguration.loadConfiguration(markersFile);

        // Очищаем старые маркеры
        markers.clear();

        if (markersConfig.contains("markers")) {
            for (String markerName : markersConfig.getConfigurationSection("markers").getKeys(false)) {
                String worldName = markersConfig.getString("markers." + markerName + ".world");
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    double x = markersConfig.getDouble("markers." + markerName + ".x");
                    double y = markersConfig.getDouble("markers." + markerName + ".y");
                    double z = markersConfig.getDouble("markers." + markerName + ".z");
                    markers.put(markerName, new Location(world, x, y, z));
                }
            }
        }
        getLogger().info("Loaded " + markers.size() + " markers from " + markersFile.getAbsolutePath());
    }

    private void saveMarkers() {
        if (markersConfig == null) return;

        markersConfig.set("markers", null);
        for (Map.Entry<String, Location> entry : markers.entrySet()) {
            Location loc = entry.getValue();
            markersConfig.set("markers." + entry.getKey() + ".world", loc.getWorld().getName());
            markersConfig.set("markers." + entry.getKey() + ".x", loc.getX());
            markersConfig.set("markers." + entry.getKey() + ".y", loc.getY());
            markersConfig.set("markers." + entry.getKey() + ".z", loc.getZ());
        }

        try {
            markersConfig.save(markersFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startUpdateTasks() {
        new BukkitRunnable() {
            @Override
            public void run() {
                updateAllCompasses();
            }
        }.runTaskTimer(this, 20L, compassUpdateInterval);

        new BukkitRunnable() {
            @Override
            public void run() {
                updateBossBars();
            }
        }.runTaskTimer(this, 20L, bossbarUpdateInterval);

        new BukkitRunnable() {
            @Override
            public void run() {
                updateActionBars();
            }
        }.runTaskTimer(this, 20L, actionbarUpdateInterval);

        new BukkitRunnable() {
            @Override
            public void run() {
                updateTabOrder();
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void updateTabOrder() {
        List<Player> sortedPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        sortedPlayers.sort((p1, p2) -> {
            String rank1 = getPlayerRankName(p1);
            String rank2 = getPlayerRankName(p2);
            int weight1 = ranks.getOrDefault(rank1, new RankInfo("default", "", 100, new ArrayList<>(), new ArrayList<>())).rankWeight;
            int weight2 = ranks.getOrDefault(rank2, new RankInfo("default", "", 100, new ArrayList<>(), new ArrayList<>())).rankWeight;
            return Integer.compare(weight1, weight2);
        });

        for (Player player : sortedPlayers) {
            player.setPlayerListName(getColoredDisplay(player, tabFormat));
        }
    }

    private void updateAllCompasses() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerCompass(player);
        }
    }

    private void updatePlayerCompass(Player player) {
        UUID targetUUID = compassTargets.get(player.getUniqueId());
        String markerName = trackingMarker.get(player.getUniqueId());
        boolean isTrackingDeath = trackingDeath.getOrDefault(player.getUniqueId(), false);

        if (markerName != null && markers.containsKey(markerName)) {
            Location markerLocation = markers.get(markerName);
            if (markerLocation != null) {
                player.setCompassTarget(markerLocation);
                return;
            }
        }

        if (targetUUID != null) {
            if (isTrackingDeath) {
                Location deathLocation = deathLocations.get(targetUUID);
                if (deathLocation != null) {
                    player.setCompassTarget(deathLocation);
                }
            } else {
                Player target = Bukkit.getPlayer(targetUUID);
                if (target != null && target.isOnline()) {
                    player.setCompassTarget(target.getLocation());
                }
            }
        }
    }

    private void updateBossBars() {
        if (!config.getBoolean("bossbar.enabled", true)) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            ItemStack offHand = player.getInventory().getItemInOffHand();

            boolean hasCompassInHand = isTrackingCompass(mainHand) || isTrackingCompass(offHand);

            if (hasCompassInHand) {
                UUID targetUUID = compassTargets.get(player.getUniqueId());
                String markerName = trackingMarker.get(player.getUniqueId());
                boolean isTrackingDeath = trackingDeath.getOrDefault(player.getUniqueId(), false);

                if (markerName != null && markers.containsKey(markerName)) {
                    Location markerLoc = markers.get(markerName);
                    if (markerLoc != null && markerLoc.getWorld() != null) {
                        int distance = (int) player.getLocation().distance(markerLoc);
                        String worldName = getWorldDisplayName(markerLoc.getWorld());

                        String message = config.getString("bossbar.marker-message",
                                        "&fКоординаты метки &6{marker}&f: &6{x} {y} {z} &7(блоков: &6{distance}&7, мир: {world}&7)")
                                .replace("{marker}", markerName)
                                .replace("{x}", String.valueOf(markerLoc.getBlockX()))
                                .replace("{y}", String.valueOf(markerLoc.getBlockY()))
                                .replace("{z}", String.valueOf(markerLoc.getBlockZ()))
                                .replace("{distance}", String.valueOf(distance))
                                .replace("{world}", worldName);

                        updatePlayerBossBar(player, message);
                        continue;
                    }
                }

                if (targetUUID != null) {
                    if (isTrackingDeath) {
                        Location deathLocation = deathLocations.get(targetUUID);
                        if (deathLocation != null) {
                            Player target = Bukkit.getPlayer(targetUUID);
                            if (target != null) {
                                int distance = (int) player.getLocation().distance(deathLocation);
                                String worldName = getWorldDisplayName(deathLocation.getWorld());

                                String message = config.getString("bossbar.death-message",
                                                "&fКоординаты &6смерти &fигрока &6{player}&f: &6{x} {y} {z} &7(блоков: &6{distance}&7, мир: {world}&7)")
                                        .replace("{player}", target.getName())
                                        .replace("{x}", String.valueOf(deathLocation.getBlockX()))
                                        .replace("{y}", String.valueOf(deathLocation.getBlockY()))
                                        .replace("{z}", String.valueOf(deathLocation.getBlockZ()))
                                        .replace("{distance}", String.valueOf(distance))
                                        .replace("{world}", worldName);

                                updatePlayerBossBar(player, message);
                                continue;
                            }
                        }
                    } else {
                        Player target = Bukkit.getPlayer(targetUUID);
                        if (target != null && target.isOnline() && player.canSee(target)) {
                            Location targetLoc = target.getLocation();
                            int distance = (int) player.getLocation().distance(targetLoc);
                            String worldName = getWorldDisplayName(targetLoc.getWorld());

                            String message = config.getString("bossbar.message",
                                            "&fКоординаты игрока &6{player}&f: &6{x} {y} {z} &7(блоков: &6{distance}&7, мир: {world}&7)")
                                    .replace("{player}", target.getName())
                                    .replace("{x}", String.valueOf(targetLoc.getBlockX()))
                                    .replace("{y}", String.valueOf(targetLoc.getBlockY()))
                                    .replace("{z}", String.valueOf(targetLoc.getBlockZ()))
                                    .replace("{distance}", String.valueOf(distance))
                                    .replace("{world}", worldName);

                            updatePlayerBossBar(player, message);
                            continue;
                        }
                    }
                }
            }

            hideBossBar(player);
        }
    }

    private void updateActionBars() {
        if (!config.getBoolean("actionbar.enabled", true)) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            ItemStack offHand = player.getInventory().getItemInOffHand();

            boolean hasCompassInHand = isTrackingCompass(mainHand) || isTrackingCompass(offHand);

            if (hasCompassInHand) {
                Location playerLoc = player.getLocation();
                int x = playerLoc.getBlockX();
                int y = playerLoc.getBlockY();
                int z = playerLoc.getBlockZ();

                String playTime = getPlayTime(player);
                long day = player.getWorld().getFullTime() / 24000L;

                String message = config.getString("actionbar.message",
                                "&6{x} {y} {z} | Наиграно &6{playtime} | День &6{day}")
                        .replace("{x}", String.valueOf(x))
                        .replace("{y}", String.valueOf(y))
                        .replace("{z}", String.valueOf(z))
                        .replace("{playtime}", playTime)
                        .replace("{day}", String.valueOf(day));

                sendActionBar(player, ChatColor.translateAlternateColorCodes('&', message));
            }
        }
    }

    private String getPlayTime(Player player) {
        int playTimeTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long playTimeMinutes = playTimeTicks / (20 * 60);

        if (playTimeMinutes < 60) {
            return playTimeMinutes + " м.";
        } else {
            long playTimeHours = playTimeMinutes / 60;
            return playTimeHours + " ч.";
        }
    }

    private void sendActionBar(Player player, String message) {
        try {
            player.sendActionBar(message);
        } catch (NoSuchMethodError e) {
            // Fallback для старых версий
        }
    }

    private String getWorldDisplayName(World world) {
        String worldName = world.getName();
        String displayName;

        switch (worldName) {
            case "world":
                displayName = "Обычный Мир";
                break;
            case "world_nether":
                displayName = "Нижний Мир";
                break;
            case "world_the_end":
                displayName = "Эндер Мир";
                break;
            default:
                displayName = worldName;
        }

        if (world.getEnvironment() == World.Environment.NETHER) {
            return "&4" + displayName;
        } else if (world.getEnvironment() == World.Environment.THE_END) {
            return "&5" + displayName;
        } else {
            return "&a" + displayName;
        }
    }

    private void updatePlayerBossBar(Player player, String message) {
        BossBar bossBar = playerBossBars.get(player.getUniqueId());

        if (bossBar == null) {
            String colorStr = config.getString("bossbar.color", "GREEN");
            String styleStr = config.getString("bossbar.style", "SOLID");

            BarColor color = BarColor.valueOf(colorStr.toUpperCase());
            BarStyle style = BarStyle.valueOf(styleStr.toUpperCase());

            bossBar = Bukkit.createBossBar(
                    ChatColor.translateAlternateColorCodes('&', message),
                    color,
                    style
            );
            playerBossBars.put(player.getUniqueId(), bossBar);
        } else {
            bossBar.setTitle(ChatColor.translateAlternateColorCodes('&', message));
        }

        bossBar.addPlayer(player);
        bossBar.setVisible(true);
        bossBar.setProgress(1.0);
    }

    private void hideBossBar(Player player) {
        BossBar bossBar = playerBossBars.get(player.getUniqueId());
        if (bossBar != null) {
            bossBar.removePlayer(player);
            bossBar.setVisible(false);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        lastMovementTime.put(player.getUniqueId(), System.currentTimeMillis());
        lastLocation.put(player.getUniqueId(), player.getLocation().clone());
        lastYaw.put(player.getUniqueId(), player.getEyeLocation().getYaw());
        lastPitch.put(player.getUniqueId(), player.getEyeLocation().getPitch());
        isAfk.put(player.getUniqueId(), false);

        String playerRank = null;
        for (Map.Entry<String, RankInfo> entry : ranks.entrySet()) {
            if (entry.getValue().players.contains(player.getName())) {
                playerRank = entry.getKey();
                break;
            }
        }

        if (playerRank == null) {
            playerRank = "default";
        }

        playerRanks.put(player.getUniqueId(), playerRank);
        setupPlayerRank(player);

        giveCompassIfNotExists(player);
        playerJoinTimes.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        hideBossBar(player);
        playerBossBars.remove(playerUUID);
        playerJoinTimes.remove(playerUUID);

        lastMovementTime.remove(playerUUID);
        isAfk.remove(playerUUID);
        lastLocation.remove(playerUUID);
        lastYaw.remove(playerUUID);
        lastPitch.remove(playerUUID);

        if (playerPermissions.containsKey(playerUUID)) {
            playerPermissions.get(playerUUID).remove();
            playerPermissions.remove(playerUUID);
        }

        compassTargets.values().removeIf(uuid -> uuid != null && uuid.equals(playerUUID));
        trackingDeath.remove(playerUUID);
        trackingMarker.remove(playerUUID);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        updatePlayerActivity(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        updatePlayerActivity(player);

        // Получаем форматированный префикс
        String prefix = getColoredDisplay(player, chatFormat);

        // Формируем итоговый формат сообщения
        String separator = ChatColor.translateAlternateColorCodes('&', chatSeparator);
        event.setFormat(prefix + separator + "%2$s");
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        updatePlayerActivity(event.getPlayer());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (config.getBoolean("death.save-location", true)) {
            deathLocations.put(player.getUniqueId(), player.getLocation());
        }

        if (config.getBoolean("item.prevent-death-drop", true)) {
            event.getDrops().removeIf(this::isTrackingCompass);
        }

        hideBossBar(player);

        String deathMessage = config.getString("death.message",
                        "&fИгрок &6{player} &fумер на координатах: &6{x} {y} {z}")
                .replace("{player}", player.getName())
                .replace("{x}", String.valueOf(player.getLocation().getBlockX()))
                .replace("{y}", String.valueOf(player.getLocation().getBlockY()))
                .replace("{z}", String.valueOf(player.getLocation().getBlockZ()));

        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', deathMessage));
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!config.getBoolean("shift-click.enabled", true)) return;
        updatePlayerActivity(event.getPlayer());

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (player.isSneaking() && item != null && isTrackingCompass(item)) {

                long cooldownTime = config.getInt("shift-click.cooldown", 3) * 1000L;
                long lastUse = shiftClickCooldowns.getOrDefault(player.getUniqueId(), 0L);

                if (System.currentTimeMillis() - lastUse < cooldownTime) {
                    return;
                }

                Player targetPlayer = getTargetPlayer(player);
                if (targetPlayer != null && !targetPlayer.equals(player)) {
                    setCompassTarget(player, targetPlayer, false, null);
                    shiftClickCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
                } else {
                    String errorMessage = config.getString("shift-click.error-message",
                            "&cНе удалось переключиться на игрока: слишком далеко или не наведён прицел!");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', errorMessage));
                }
            }
        }
    }

    private Player getTargetPlayer(Player player) {
        double maxDistance = config.getDouble("shift-click.max-distance", 5.0);
        List<Entity> nearbyEntities = player.getNearbyEntities(maxDistance, maxDistance, maxDistance);

        for (Entity entity : nearbyEntities) {
            if (entity instanceof Player target && !target.equals(player)) {
                if (isLookingAt(player, target)) {
                    return target;
                }
            }
        }

        return null;
    }

    private boolean isLookingAt(Player player, Entity target) {
        Location eye = player.getEyeLocation();
        Vector toEntity = target.getLocation().add(0, target.getHeight() / 2, 0).toVector().subtract(eye.toVector());
        double dot = toEntity.normalize().dot(eye.getDirection());
        return dot > 0.99D;
    }

    private void setCompassTarget(Player player, Player target, boolean trackDeath, String markerName) {
        if (markerName != null) {
            compassTargets.put(player.getUniqueId(), null);
            trackingDeath.put(player.getUniqueId(), false);
            trackingMarker.put(player.getUniqueId(), markerName);
        } else if (target != null) {
            compassTargets.put(player.getUniqueId(), target.getUniqueId());
            trackingDeath.put(player.getUniqueId(), trackDeath);
            trackingMarker.put(player.getUniqueId(), null);
        }

        updateCompassMeta(player, target, trackDeath, markerName);

        if (markerName != null && markers.containsKey(markerName)) {
            String successMessage = config.getString("command.success-marker",
                            "&fКомпас направлен на метку &6{marker}")
                    .replace("{marker}", markerName);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', successMessage));

            Location markerLoc = markers.get(markerName);
            if (markerLoc != null) {
                player.setCompassTarget(markerLoc);
            }
        } else if (trackDeath) {
            Location deathLocation = deathLocations.get(target.getUniqueId());
            if (deathLocation == null) {
                String noDeathMessage = config.getString("command.no-death-location",
                        "&cЭтот игрок еще не умирал.");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', noDeathMessage));
                return;
            }

            if (player.equals(target)) {
                String successMessage = config.getString("command.success-self-death",
                        "&fКомпас направлен на &6вашу смерть");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', successMessage));
            } else {
                String successMessage = config.getString("command.success-death",
                                "&fКомпас направлен на &6смерть &fигрока &6{player}")
                        .replace("{player}", target.getName());
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', successMessage));
            }
            player.setCompassTarget(deathLocation);
        } else if (target != null) {
            String successMessage = config.getString("command.success",
                            "&fКомпас направлен к игроку &6{player}")
                    .replace("{player}", target.getName());
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', successMessage));
            player.setCompassTarget(target.getLocation());
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> giveCompassIfNotExists(player), 5L);
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        if (config.getBoolean("item.prevent-drop", true) &&
                isTrackingCompass(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    private void giveCompassIfNotExists(Player player) {
        boolean hasCompass = Arrays.stream(player.getInventory().getContents())
                .anyMatch(this::isTrackingCompass);

        if (!hasCompass) {
            ItemStack compass = createCompass(player);
            player.getInventory().addItem(compass).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    private ItemStack createCompass(Player owner) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();

        if (meta != null) {
            String compassName = config.getString("compass.name", "&fКомпас к &6{player}")
                    .replace("{player}", owner.getName());

            List<String> lore = config.getStringList("compass.lore");
            if (lore.isEmpty()) {
                lore = Arrays.asList("&7Компас который показывает", "&7Путь к игроку или метке");
            }

            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(ChatColor.translateAlternateColorCodes('&', line));
            }

            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', compassName));
            meta.setLore(coloredLore);

            if (config.getBoolean("item.unbreakable", true)) {
                meta.setUnbreakable(true);
            }

            meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(targetKey,
                    PersistentDataType.STRING, owner.getUniqueId().toString());

            compass.setItemMeta(meta);
        }

        return compass;
    }

    private boolean isTrackingCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("compass")) {
            return handleCompassCommand(sender, args);
        } else if (command.getName().equalsIgnoreCase("ping")) {
            return handlePingCommand(sender, args);
        } else if (command.getName().equalsIgnoreCase("msg")) {
            return handleMsgCommand(sender, args);
        } else if (command.getName().equalsIgnoreCase("rank")) {
            return handleRankCommand(sender, args);
        } else if (command.getName().equalsIgnoreCase("playmanagercm")) {
            return handleAdminCommand(sender, args);
        }
        return false;
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("playmanager.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Использование: /playmanagercm <reload>");
            sender.sendMessage(ChatColor.YELLOW + "Доступные подкоманды:");
            sender.sendMessage(ChatColor.WHITE + "  /playmanagercm reload - перезагрузить конфигурацию плагина");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("reload")) {
            long startTime = System.currentTimeMillis();

            // Перезагружаем конфиг
            reloadConfig();
            config = getConfig();
            loadConfigValues();

            // Перезагружаем ранги
            loadRanks();

            // Перезагружаем маркеры
            loadMarkers();

            // Обновляем отображение у всех игроков
            for (Player player : Bukkit.getOnlinePlayers()) {
                setupPlayerRank(player);
                updatePlayerDisplay(player);
            }

            // Перерегистрируем рецепты
            registerRecipes();

            long reloadTime = System.currentTimeMillis() - startTime;

            sender.sendMessage(ChatColor.GREEN + "✓ Плагин PlayManagerCM v2.2 успешно перезагружен!");
            sender.sendMessage(ChatColor.GRAY + "Время перезагрузки: " + reloadTime + "ms");
            sender.sendMessage(ChatColor.GRAY + "Загружено рангов: " + ranks.size());
            sender.sendMessage(ChatColor.GRAY + "Загружено маркеров: " + markers.size());

            getLogger().info("Plugin reloaded by " + sender.getName() + " in " + reloadTime + "ms");
            return true;
        } else {
            sender.sendMessage(ChatColor.RED + "Неизвестная подкоманда: " + subCommand);
            sender.sendMessage(ChatColor.YELLOW + "Используйте: /playmanagercm reload");
            return true;
        }
    }

    private boolean handleRankCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("playmanager.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на использование этой команды!");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Использование: /rank <set|remove|reload> [player] [rank]");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "set":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Использование: /rank set <player> <rank>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Игрок не найден!");
                    return true;
                }
                String rankName = args[2];
                if (!ranks.containsKey(rankName)) {
                    sender.sendMessage(ChatColor.RED + "Ранг не найден!");
                    return true;
                }
                playerRanks.put(target.getUniqueId(), rankName);
                setupPlayerRank(target);
                sender.sendMessage(ChatColor.GREEN + "Игроку " + target.getName() + " установлен ранг " + rankName);
                break;

            case "remove":
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Использование: /rank remove <player>");
                    return true;
                }
                Player targetRemove = Bukkit.getPlayer(args[1]);
                if (targetRemove == null) {
                    sender.sendMessage(ChatColor.RED + "Игрок не найден!");
                    return true;
                }
                playerRanks.put(targetRemove.getUniqueId(), "default");
                setupPlayerRank(targetRemove);
                sender.sendMessage(ChatColor.GREEN + "Ранг игрока " + targetRemove.getName() + " сброшен на default");
                break;

            case "reload":
                loadRanks();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    setupPlayerRank(player);
                }
                sender.sendMessage(ChatColor.GREEN + "Ранги перезагружены!");
                break;

            default:
                sender.sendMessage(ChatColor.RED + "Неизвестная подкоманда! Используйте: set, remove, reload");
                break;
        }

        return true;
    }

    private boolean handleCompassCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            String consoleError = config.getString("command.console-error", "&cЭта команда только для игроков!");
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', consoleError));
            return true;
        }

        if (args.length < 1) {
            sendCompassUsage(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "addmarker":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Использование: /compass addmarker <название_метки>");
                    return true;
                }
                String markerName = args[1];
                if (markerName.contains(" ")) {
                    player.sendMessage(ChatColor.RED + "Название метки не должно содержать пробелов!");
                    return true;
                }
                if (markers.containsKey(markerName)) {
                    player.sendMessage(ChatColor.RED + "Метка с таким названием уже существует!");
                    return true;
                }
                markers.put(markerName, player.getLocation().clone());
                saveMarkers();
                player.sendMessage(ChatColor.GREEN + "Метка '" + markerName + "' успешно создана на ваших координатах!");
                player.sendMessage(ChatColor.GRAY + "X: " + player.getLocation().getBlockX() +
                        " Y: " + player.getLocation().getBlockY() +
                        " Z: " + player.getLocation().getBlockZ());
                return true;

            case "delmarker":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Использование: /compass delmarker <название_метки>");
                    return true;
                }
                String delMarkerName = args[1];
                if (!markers.containsKey(delMarkerName)) {
                    player.sendMessage(ChatColor.RED + "Метка с таким названием не найдена!");
                    return true;
                }
                if (!player.hasPermission("playmanager.admin")) {
                    player.sendMessage(ChatColor.RED + "У вас нет прав на удаление меток!");
                    return true;
                }
                markers.remove(delMarkerName);
                saveMarkers();
                player.sendMessage(ChatColor.GREEN + "Метка '" + delMarkerName + "' успешно удалена!");
                return true;

            case "marker":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Использование: /compass marker <название_метки>");
                    return true;
                }
                String targetMarker = args[1];
                if (!markers.containsKey(targetMarker)) {
                    player.sendMessage(ChatColor.RED + "Метка с таким названием не найдена!");
                    return true;
                }
                setCompassTarget(player, null, false, targetMarker);
                return true;

            case "death":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Использование: /compass death <игрок>");
                    return true;
                }
                Player targetDeath = Bukkit.getPlayer(args[1]);
                if (targetDeath == null) {
                    String notFoundMessage = config.getString("command.player-not-found", "&cИгрок не найден или не в сети!");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', notFoundMessage));
                    return true;
                }
                setCompassTarget(player, targetDeath, true, null);
                return true;

            default:
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    String notFoundMessage = config.getString("command.player-not-found", "&cИгрок не найден или не в сети!");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', notFoundMessage));
                    return true;
                }

                if (target.equals(player)) {
                    String selfTargetMessage = config.getString("command.self-target", "&cНельзя выбрать себя!");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', selfTargetMessage));
                    return true;
                }

                setCompassTarget(player, target, false, null);
                return true;
        }
    }

    private void sendCompassUsage(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== Команды компаса ===");
        player.sendMessage(ChatColor.YELLOW + "/compass <игрок>" + ChatColor.WHITE + " - направить компас на игрока");
        player.sendMessage(ChatColor.YELLOW + "/compass death <игрок>" + ChatColor.WHITE + " - направить компас на смерть игрока");
        player.sendMessage(ChatColor.YELLOW + "/compass addmarker <название>" + ChatColor.WHITE + " - создать метку на текущих координатах");
        player.sendMessage(ChatColor.YELLOW + "/compass marker <название>" + ChatColor.WHITE + " - направить компас на метку");
        if (player.hasPermission("playmanager.admin")) {
            player.sendMessage(ChatColor.YELLOW + "/compass delmarker <название>" + ChatColor.WHITE + " - удалить метку (только для админов)");
        }

        if (!markers.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "Существующие метки: " + String.join(", ", markers.keySet()));
        }
    }

    private boolean handlePingCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Только игроки могут использовать эту команду без аргументов!");
                return true;
            }
            Player player = (Player) sender;
            int ping = player.getPing();
            player.sendMessage(ChatColor.GREEN + "Ваш пинг: " + ChatColor.YELLOW + ping + " мс");
        } else {
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Игрок не найден или не в сети!");
                return true;
            }
            int ping = target.getPing();
            sender.sendMessage(ChatColor.GREEN + "Пинг игрока " + ChatColor.YELLOW + target.getName() +
                    ChatColor.GREEN + ": " + ChatColor.YELLOW + ping + " мс");
        }
        return true;
    }

    private boolean handleMsgCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только игроки могут использовать эту команду!");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Использование: /msg <игрок> <сообщение>");
            return true;
        }

        Player player = (Player) sender;
        Player target = Bukkit.getPlayer(args[0]);

        if (target == null) {
            player.sendMessage(ChatColor.RED + "Игрок не найден или не в сети!");
            return true;
        }

        if (target.equals(player)) {
            player.sendMessage(ChatColor.RED + "Нельзя отправить сообщение самому себе!");
            return true;
        }

        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        // Форматируем сообщения с использованием настроек из конфига
        String senderFormatted = privateMessageSenderFormat
                .replace("{target}", target.getName())
                .replace("{message}", message);

        String receiverFormatted = privateMessageReceiverFormat
                .replace("{sender}", player.getName())
                .replace("{message}", message);

        // Отправка сообщений
        target.sendMessage(ChatColor.translateAlternateColorCodes('&', receiverFormatted));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', senderFormatted));

        // Звуковой эффект
        if (privateMessageSoundEnabled) {
            try {
                Sound sound = Sound.valueOf(privateMessageSoundType);
                target.playSound(target.getLocation(), sound, privateMessageSoundVolume, privateMessageSoundPitch);
            } catch (IllegalArgumentException e) {
                // Если звук не найден, используем стандартный
                try {
                    target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                } catch (Exception ex) {
                    // Игнорируем
                }
            } catch (Exception e) {
                // Игнорируем ошибки звука
            }
        }

        return true;
    }

    private void updateCompassMeta(Player owner, Player target, boolean trackDeath, String markerName) {
        for (ItemStack item : owner.getInventory().getContents()) {
            if (isTrackingCompass(item) && item.hasItemMeta()) {
                CompassMeta meta = (CompassMeta) item.getItemMeta();
                if (meta != null) {
                    String compassName;
                    if (markerName != null) {
                        compassName = "&fКомпас к метке &6" + markerName;
                    } else if (trackDeath) {
                        compassName = "&fКомпас к &6смерти &fигрока &6" + target.getName();
                    } else {
                        compassName = config.getString("compass.name", "&fКомпас к &6{player}")
                                .replace("{player}", target.getName());
                    }

                    meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', compassName));

                    if (target != null) {
                        meta.getPersistentDataContainer().set(targetKey,
                                PersistentDataType.STRING, target.getUniqueId().toString());
                    } else {
                        meta.getPersistentDataContainer().remove(targetKey);
                    }

                    if (markerName != null) {
                        meta.getPersistentDataContainer().set(markerKey,
                                PersistentDataType.STRING, markerName);
                    } else {
                        meta.getPersistentDataContainer().remove(markerKey);
                    }

                    meta.getPersistentDataContainer().set(deathTrackingKey,
                            PersistentDataType.BYTE, (byte) (trackDeath ? 1 : 0));

                    item.setItemMeta(meta);
                }
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("compass")) {
            List<String> completions = new ArrayList<>();

            if (args.length == 1) {
                completions.addAll(Arrays.asList("addmarker", "delmarker", "marker", "death"));
                for (Player player : Bukkit.getOnlinePlayers()) {
                    completions.add(player.getName());
                }
            } else if (args.length == 2) {
                String subCommand = args[0].toLowerCase();
                if (subCommand.equals("delmarker") || subCommand.equals("marker")) {
                    completions.addAll(markers.keySet());
                } else if (subCommand.equals("death")) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        completions.add(player.getName());
                    }
                }
            }

            String lastArg = args[args.length - 1].toLowerCase();
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(lastArg))
                    .collect(Collectors.toList());
        } else if (command.getName().equalsIgnoreCase("ping") && args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                completions.add(player.getName());
            }
            String lastArg = args[0].toLowerCase();
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(lastArg))
                    .collect(Collectors.toList());
        } else if (command.getName().equalsIgnoreCase("msg") && args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (sender instanceof Player && !player.equals(sender)) {
                    completions.add(player.getName());
                }
            }
            String lastArg = args[0].toLowerCase();
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(lastArg))
                    .collect(Collectors.toList());
        } else if (command.getName().equalsIgnoreCase("rank") && args.length <= 3) {
            List<String> completions = new ArrayList<>();
            if (args.length == 1) {
                completions.addAll(Arrays.asList("set", "remove", "reload"));
            } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    completions.add(player.getName());
                }
            } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                completions.addAll(ranks.keySet());
            } else if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    completions.add(player.getName());
                }
            }
            String lastArg = args[args.length - 1].toLowerCase();
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(lastArg))
                    .collect(Collectors.toList());
        } else if (command.getName().equalsIgnoreCase("playmanagercm") && args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("reload");
            String lastArg = args[0].toLowerCase();
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(lastArg))
                    .collect(Collectors.toList());
        }

        return super.onTabComplete(sender, command, alias, args);
    }

    private static class RankInfo {
        String name;
        String prefix;
        int rankWeight;
        List<String> parentRanks;
        List<String> permissions;
        List<String> players;

        RankInfo(String name, String prefix, int rankWeight, List<String> parentRanks, List<String> permissions) {
            this.name = name;
            this.prefix = prefix;
            this.rankWeight = rankWeight;
            this.parentRanks = parentRanks;
            this.permissions = permissions;
            this.players = new ArrayList<>();
        }
    }
}
package io.github.andrewiankidd.personaskins;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.event.skin.SkinApplyEvent;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Renders Bedrock players' 3D skins for Java players: when a Bedrock player
 * joins with a persona or custom-geometry skin, flattens it onto a Java skin,
 * has MineSkin sign it, and applies that in place of the one Floodgate made.
 * Each result is cached by the Bedrock skin's hash, so a skin is rendered and
 * uploaded once.
 */
public final class PersonaSkinsPlugin extends JavaPlugin implements Listener {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Rendered skins by Bedrock skin key. */
    private final Map<String, MineSkin.Signed> cache = new ConcurrentHashMap<>();
    /** Each online Bedrock player's skin key, once read. */
    private final Map<UUID, String> playerKeys = new ConcurrentHashMap<>();
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private ExecutorService worker;
    private MineSkin mineSkin;
    private Path cacheDir;
    private boolean renderAll;
    private boolean keepPngs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        renderAll = "all".equalsIgnoreCase(getConfig().getString("render", "custom"));
        keepPngs = getConfig().getBoolean("keep-pngs", true);
        String key = getConfig().getString("mineskin.api-key", "");
        if (key == null || key.isBlank()) {
            key = System.getenv("MINESKIN_API_KEY");
        }
        mineSkin = new MineSkin(key, getConfig().getString("mineskin.visibility", "unlisted"),
                getPluginMeta().getVersion());
        cacheDir = getDataFolder().toPath().resolve("cache");
        loadCache();
        worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "PersonaSkins"));

        FloodgateApi.getInstance().getEventBus().subscribe(SkinApplyEvent.class, this::onFloodgateSkin);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Loaded " + cache.size() + " rendered skins; rendering "
                + (renderAll ? "every Bedrock skin" : "persona and custom-geometry skins"));
    }

    @Override
    public void onDisable() {
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    // ---- events ----

    /**
     * Floodgate applies the skin it fetched from GeyserMC's global API, on join
     * and whenever that arrives; replace it with ours when we have one.
     */
    private void onFloodgateSkin(SkinApplyEvent event) {
        String key = playerKeys.get(event.player().getCorrectUniqueId());
        MineSkin.Signed ours = key == null ? null : cache.get(key);
        if (ours != null) {
            event.newSkin(new SkinData(ours.value(), ours.signature()));
        }
    }

    private record SkinData(@NonNull String value, @NonNull String signature) implements SkinApplyEvent.SkinData {
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) {
            handle(player, false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerKeys.remove(event.getPlayer().getUniqueId());
    }

    private void handle(Player player, boolean force) {
        BedrockSkin skin = readSkin(player);
        if (skin == null) {
            return;
        }
        String key = skin.key();
        playerKeys.put(player.getUniqueId(), key);
        if (!force && !renderAll && !skin.hasCustomGeometry()) {
            return;
        }
        MineSkin.Signed known = force ? null : cache.get(key);
        if (known != null) {
            apply(player, known);
            return;
        }
        if (inFlight.putIfAbsent(key, true) != null) {
            return;
        }
        UUID id = player.getUniqueId();
        String name = player.getName();
        worker.submit(() -> {
            try {
                int[] pixels = skin.render();
                byte[] png = Png.encode(pixels, 64, 64);
                MineSkin.Signed signed = mineSkin.generate(png, skin.slim(), "bedrock-" + key.substring(0, 12));
                store(key, signed, keepPngs ? png : null);
                getLogger().info("Rendered " + name + "'s " + (skin.persona() ? "persona" : "custom") + " skin ("
                        + key.substring(0, 12) + ")");
                Bukkit.getScheduler().runTask(this, () -> {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && key.equals(playerKeys.get(id))) {
                        apply(p, signed);
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Could not render " + name + "'s skin", e);
            } finally {
                inFlight.remove(key);
            }
        });
    }

    private BedrockSkin readSkin(Player player) {
        GeyserConnection connection = GeyserApi.api().connectionByUuid(player.getUniqueId());
        if (connection == null) {
            return null;
        }
        try {
            return BedrockSkin.read(connection);
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().log(Level.WARNING, "Could not read " + player.getName() + "'s Bedrock skin", e);
            return null;
        }
    }

    /** Sets the skin on the player's profile; Paper re-sends the player to everyone who can see them. */
    private void apply(Player player, MineSkin.Signed skin) {
        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty("textures");
        profile.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
        player.setPlayerProfile(profile);
    }

    // ---- cache ----

    private void loadCache() {
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        try (var files = Files.list(cacheDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (n.endsWith(".json")) {
                    MineSkin.Signed s = GSON.fromJson(Files.readString(f), MineSkin.Signed.class);
                    if (s != null && s.value() != null && s.signature() != null) {
                        cache.put(n.substring(0, n.length() - 5), s);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.WARNING, "Could not read the skin cache", e);
        }
    }

    private void store(String key, MineSkin.Signed signed, byte[] png) throws IOException {
        cache.put(key, signed);
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve(key + ".json"), GSON.toJson(signed), StandardCharsets.UTF_8);
        if (png != null) {
            Files.write(cacheDir.resolve(key + ".png"), png);
        }
    }

    // ---- command ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 2 || !List.of("refresh", "dump").contains(args[0])) {
            return false;
        }
        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null || !FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) {
            sender.sendMessage("No Bedrock player called " + args[1] + " is online.");
            return true;
        }
        if (args[0].equals("refresh")) {
            handle(player, true);
            sender.sendMessage("Re-rendering " + player.getName() + "'s skin.");
            return true;
        }
        BedrockSkin skin = readSkin(player);
        if (skin == null) {
            sender.sendMessage("Could not read " + player.getName() + "'s skin.");
            return true;
        }
        Path dir = getDataFolder().toPath().resolve("dumps").resolve(player.getName());
        try {
            Files.createDirectories(dir);
            int[] raw = new int[skin.texture().width() * skin.texture().height()];
            for (int y = 0; y < skin.texture().height(); y++) {
                for (int x = 0; x < skin.texture().width(); x++) {
                    raw[y * skin.texture().width() + x] = skin.texture().argb(x, y);
                }
            }
            Files.write(dir.resolve("bedrock.png"), Png.encode(raw, skin.texture().width(), skin.texture().height()));
            Files.writeString(dir.resolve("geometry.json"), skin.geometryJson());
            Files.writeString(dir.resolve("info.txt"), "geometry: " + skin.geometryName() + "\npersona: " + skin.persona()
                    + "\nslim: " + skin.slim() + "\ncustom: " + skin.hasCustomGeometry()
                    + "\nskin colour: " + Integer.toHexString(skin.skinColour()) + "\nkey: " + skin.key() + "\n");
            Files.write(dir.resolve("java.png"), Png.encode(skin.render(), 64, 64));
            sender.sendMessage("Dumped to " + getDataFolder().getName() + "/dumps/" + player.getName());
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.WARNING, "Could not dump " + player.getName() + "'s skin", e);
            sender.sendMessage("Dump failed: " + e.getMessage());
        }
        return true;
    }
}

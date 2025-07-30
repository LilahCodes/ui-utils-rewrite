package com.ui_utils;

import java.awt.Color;
import java.awt.Font;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;

import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;

public class MainClient implements ClientModInitializer {
    // ui styling constants for swing components
    public static Font monospace;
    public static Color darkWhite;
    
    // keybind for restoring saved gui screens
    public static KeyBinding restoreScreenKey;
    
    // main logger and minecraft client instance
    public static Logger LOGGER = LoggerFactory.getLogger("ui-utils");
    public static MinecraftClient mc = MinecraftClient.getInstance();
    
    @Override
    public void onInitializeClient() {
        // check for mod updates on startup
        UpdateUtils.checkForUpdates();

        // register v key to restore previously saved gui screens
        restoreScreenKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("Restore Screen", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "UI Utils")
        );

        // handle restore screen key presses each client tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (restoreScreenKey.wasPressed()) {
                if (SharedVariables.storedScreen != null && SharedVariables.storedScreenHandler != null && client.player != null) {
                    client.setScreen(SharedVariables.storedScreen);
                    client.player.currentScreenHandler = SharedVariables.storedScreenHandler;
                }
            }
        });

        // setup swing components if not on mac since awt headless causes issues
        if (!MinecraftClient.IS_SYSTEM_MAC) {
            System.setProperty("java.awt.headless", "false");
            monospace = new Font(Font.MONOSPACED, Font.PLAIN, 10);
            darkWhite = new Color(220, 220, 220);
        }
    }

    // draws sync id and revision info on screen for debugging
    public static void createText(MinecraftClient mc, DrawContext context, TextRenderer textRenderer) {
        context.drawText(textRenderer, "Sync Id: " + mc.player.currentScreenHandler.syncId, 200, 5, Color.WHITE.getRGB(), false);
        context.drawText(textRenderer, "Revision: " + mc.player.currentScreenHandler.getRevision(), 200, 35, Color.WHITE.getRGB(), false);
    }

    // adds all the ui utility buttons to handled screens
    public static void createWidgets(MinecraftClient mc, Screen screen) {
        // button to close gui without sending close packet to server
        screen.addDrawableChild(ButtonWidget.builder(Text.of("Close without packet"), button -> 
            mc.setScreen(null)
        ).size(115, 20).position(5, 5).build());

        // button to close gui server side while keeping it open client side
        screen.addDrawableChild(ButtonWidget.builder(Text.of("De-sync"), button -> {
            if (mc.getNetworkHandler() != null && mc.player != null) {
                mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
            } else {
                LOGGER.warn("network handler or player null during desync");
            }
        }).size(115, 20).position(5, 35).build());

        // toggle whether gui packets should be sent or blocked
        screen.addDrawableChild(ButtonWidget.builder(Text.of("Send packets: " + SharedVariables.sendUIPackets), button -> {
            SharedVariables.sendUIPackets = !SharedVariables.sendUIPackets;
            button.setMessage(Text.of("Send packets: " + SharedVariables.sendUIPackets));
        }).size(115, 20).position(5, 65).build());

        // toggle packet delay mode and send all delayed packets when turned off
        screen.addDrawableChild(ButtonWidget.builder(Text.of("Delay packets: " + SharedVariables.delayUIPackets), button -> {
            SharedVariables.delayUIPackets = !SharedVariables.delayUIPackets;
            button.setMessage(Text.of("Delay packets: " + SharedVariables.delayUIPackets));
            
            // send all delayed packets when turning delay mode off
            if (!SharedVariables.delayUIPackets && !SharedVariables.delayedUIPackets.isEmpty() && mc.getNetworkHandler() != null) {
                SharedVariables.delayedUIPackets.forEach(mc.getNetworkHandler()::sendPacket);
                if (mc.player != null) {
                    mc.player.sendMessage(Text.of("Sent " + SharedVariables.delayedUIPackets.size() + " packets."), false);
                }
                SharedVariables.delayedUIPackets.clear();
            }
        }).size(115, 20).position(5, 95).build());

        // save current gui and screen handler for later restoration
        screen.addDrawableChild(ButtonWidget.builder(Text.of("Save GUI"), button -> {
            if (mc.player != null) {
                SharedVariables.storedScreen = mc.currentScreen;
                SharedVariables.storedScreenHandler = mc.player.currentScreenHandler;
            }
        }).size(115, 20).position(5, 125).build());

        // send all delayed packets then disconnect from server
        screen.addDrawableChild(ButtonWidget.builder(Text.of("Disconnect and send packets"), button -> {
            SharedVariables.delayUIPackets = false;
            if (mc.getNetworkHandler() != null) {
                SharedVariables.delayedUIPackets.forEach(mc.getNetworkHandler()::sendPacket);
                mc.getNetworkHandler().getConnection().disconnect(Text.of("Disconnecting (UI-UTILS)"));
            } else {
                LOGGER.warn("network handler null during disconnect");
            }
            SharedVariables.delayedUIPackets.clear();
        }).size(160, 20).position(5, 155).build());

        // button to open packet fabrication gui
        ButtonWidget fabricateButton = ButtonWidget.builder(Text.of("Fabricate packet"), button -> 
            createPacketSelectionGui()
        ).size(115, 20).position(5, 185).build();
        fabricateButton.active = !MinecraftClient.IS_SYSTEM_MAC; // disabled on mac due to awt issues
        screen.addDrawableChild(fabricateButton);

        // button to unload chunks around player
        screen.addDrawableChild(ButtonWidget.builder(Text.of("Unload Chunks"), button -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("Unloading chunks in 3 seconds...").formatted(Formatting.YELLOW), false);
                
                queueTask(() -> {
                    if (mc.world != null && mc.player != null) {
                        var chunkManager = mc.world.getChunkManager();
                        var playerChunkPos = mc.player.getChunkPos();
                        int renderDistance = mc.options.getViewDistance().getValue();
                        
                        // unload chunks in a radius around the player
                        for (int x = playerChunkPos.x - renderDistance; x <= playerChunkPos.x + renderDistance; x++) {
                            for (int z = playerChunkPos.z - renderDistance; z <= playerChunkPos.z + renderDistance; z++) {
                                chunkManager.unload(new ChunkPos(x, z));
                            }
                        }

                         // timeout the player after unloading chunks
                        if (mc.getNetworkHandler() != null) {
                            mc.getNetworkHandler().getConnection().disconnect(Text.of("Timed out (UI Utils)"));
                            LOGGER.info("player timed out after chunk unloading");
                        } else {
                            LOGGER.warn("network handler null during timeout");
                        }
                        
                        mc.player.sendMessage(Text.literal("Chunks unloaded successfully!").formatted(Formatting.GREEN), false);
                        LOGGER.info("unloaded chunks around player at {}", playerChunkPos);
                    } else {
                        LOGGER.warn("world or player null during chunk unloading");
                    }
                }, 3000L);
            }
        }).size(115, 20).position(5, 215).build());

    }


    

    // tries to get registry manager from server world or network handler
    private static net.minecraft.registry.DynamicRegistryManager getRegistryManager() {
        var server = mc.getServer();
        if (server != null) return server.getRegistryManager();
        
        if (mc.world != null && mc.world.getRegistryManager() != null) return mc.world.getRegistryManager();
        
        if (mc.getNetworkHandler() != null && mc.getNetworkHandler().getRegistryManager() != null) {
            return mc.getNetworkHandler().getRegistryManager();
        }
        
        return null;
    }

    // creates the main packet selection gui window
    private static void createPacketSelectionGui() {
        JFrame frame = new JFrame("Choose Packet");
        frame.setBounds(0, 0, 450, 100);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setLayout(null);

        // button to open click slot packet gui
        JButton clickSlotButton = createPacketButton("Click Slot");
        clickSlotButton.setBounds(100, 25, 110, 20);
        clickSlotButton.addActionListener(e -> {
            frame.setVisible(false);
            createClickSlotGui();
        });

        // button to open button click packet gui
        JButton buttonClickButton = createPacketButton("Button Click");
        buttonClickButton.setBounds(250, 25, 110, 20);
        buttonClickButton.addActionListener(e -> {
            frame.setVisible(false);
            createButtonClickGui();
        });

        frame.add(clickSlotButton);
        frame.add(buttonClickButton);
        frame.setVisible(true);
    }

    // creates gui for fabricating click slot packets
    private static void createClickSlotGui() {
        JFrame frame = new JFrame("Click Slot Packet");
        frame.setBounds(0, 0, 450, 300);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setLayout(null);

        // input fields for packet parameters
        JTextField syncIdField = createTextField(125, 25);
        JTextField revisionField = createTextField(125, 50);
        JTextField slotField = createTextField(125, 75);
        JTextField buttonField = createTextField(125, 100);
        JTextField timesToSendField = new JTextField("1");
        timesToSendField.setFont(monospace);
        timesToSendField.setBounds(125, 190, 100, 20);

        // dropdown for slot action types
        JComboBox<String> actionField = new JComboBox<>(new Vector<>(ImmutableList.of(
            "PICKUP", "QUICK_MOVE", "SWAP", "CLONE", "THROW", "QUICK_CRAFT", "PICKUP_ALL"
        )));
        styleComboBox(actionField, 125, 125);

        // checkbox to delay packet sending
        JCheckBox delayBox = createCheckBox("Delay", 115, 150);
        
        // status label for feedback
        JLabel statusLabel = createStatusLabel(210, 150);

        // send button to create and send the packet
        JButton sendButton = createSendButton(25, 150);
        sendButton.addActionListener(e -> {
            if (validateClickSlotInputs(syncIdField, revisionField, slotField, buttonField, timesToSendField, actionField)) {
                sendClickSlotPackets(syncIdField, revisionField, slotField, buttonField, actionField, delayBox, timesToSendField, statusLabel);
            } else {
                showError(statusLabel, "invalid arguments");
            }
        });

        // add all components to frame
        addClickSlotComponents(frame, syncIdField, revisionField, slotField, buttonField, actionField, delayBox, timesToSendField, sendButton, statusLabel);
        frame.setVisible(true);
    }

    // creates gui for fabricating button click packets
    private static void createButtonClickGui() {
        JFrame frame = new JFrame("Button Click Packet");
        frame.setBounds(0, 0, 450, 250);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setLayout(null);

        // input fields for packet parameters
        JTextField syncIdField = createTextField(125, 25);
        JTextField buttonIdField = createTextField(125, 50);
        JTextField timesToSendField = new JTextField("1");
        timesToSendField.setFont(monospace);
        timesToSendField.setBounds(125, 130, 100, 20);

        // checkbox to delay packet sending
        JCheckBox delayBox = createCheckBox("Delay", 115, 95);
        
        // status label for feedback
        JLabel statusLabel = createStatusLabel(210, 95);

        // send button to create and send the packet
        JButton sendButton = createSendButton(25, 95);
        sendButton.addActionListener(e -> {
            if (validateButtonClickInputs(syncIdField, buttonIdField, timesToSendField)) {
                sendButtonClickPackets(syncIdField, buttonIdField, delayBox, timesToSendField, statusLabel);
            } else {
                showError(statusLabel, "invalid arguments");
            }
        });

        // add all components to frame
        addButtonClickComponents(frame, syncIdField, buttonIdField, timesToSendField, delayBox, sendButton, statusLabel);
        frame.setVisible(true);
    }

    // helper method to create styled packet option buttons
    @NotNull
    private static JButton createPacketButton(String label) {
        JButton button = new JButton(label);
        button.setFocusable(false);
        button.setBorder(BorderFactory.createEtchedBorder());
        button.setBackground(darkWhite);
        button.setFont(monospace);
        return button;
    }

    // helper method to create styled text fields
    private static JTextField createTextField(int x, int y) {
        JTextField field = new JTextField(1);
        field.setFont(monospace);
        field.setBounds(x, y, 100, 20);
        return field;
    }

    // helper method to create styled combo boxes
    private static void styleComboBox(JComboBox<String> comboBox, int x, int y) {
        comboBox.setFocusable(false);
        comboBox.setEditable(false);
        comboBox.setBorder(BorderFactory.createEmptyBorder());
        comboBox.setBackground(darkWhite);
        comboBox.setFont(monospace);
        comboBox.setBounds(x, y, 100, 20);
    }

    // helper method to create styled checkboxes
    private static JCheckBox createCheckBox(String text, int x, int y) {
        JCheckBox checkBox = new JCheckBox(text);
        checkBox.setBounds(x, y, 85, 20);
        checkBox.setSelected(false);
        checkBox.setFont(monospace);
        checkBox.setFocusable(false);
        return checkBox;
    }

    // helper method to create status labels
    private static JLabel createStatusLabel(int x, int y) {
        JLabel label = new JLabel();
        label.setVisible(false);
        label.setFocusable(false);
        label.setFont(monospace);
        label.setBounds(x, y, 190, 20);
        return label;
    }

    // helper method to create send buttons
    private static JButton createSendButton(int x, int y) {
        JButton button = new JButton("Send");
        button.setFocusable(false);
        button.setBounds(x, y, 75, 20);
        button.setBorder(BorderFactory.createEtchedBorder());
        button.setBackground(darkWhite);
        button.setFont(monospace);
        return button;
    }

    // validates click slot packet input fields
    private static boolean validateClickSlotInputs(JTextField syncId, JTextField revision, JTextField slot, 
                                                  JTextField button, JTextField times, JComboBox<String> action) {
        return isInteger(syncId.getText()) && isInteger(revision.getText()) && 
               isInteger(slot.getText()) && isInteger(button.getText()) && 
               isInteger(times.getText()) && action.getSelectedItem() != null;
    }

    // validates button click packet input fields
    private static boolean validateButtonClickInputs(JTextField syncId, JTextField buttonId, JTextField times) {
        return isInteger(syncId.getText()) && isInteger(buttonId.getText()) && isInteger(times.getText());
    }

    // sends click slot packets based on input parameters
    private static void sendClickSlotPackets(JTextField syncIdField, JTextField revisionField, JTextField slotField,
                                           JTextField buttonField, JComboBox<String> actionField, JCheckBox delayBox,
                                           JTextField timesToSendField, JLabel statusLabel) {
        try {
            int syncId = Integer.parseInt(syncIdField.getText());
            int revision = Integer.parseInt(revisionField.getText());
            int slot = Integer.parseInt(slotField.getText());
            int button = Integer.parseInt(buttonField.getText());
            SlotActionType action = stringToSlotActionType(actionField.getSelectedItem().toString());
            int timesToSend = Integer.parseInt(timesToSendField.getText());

            if (action != null) {
                ClickSlotC2SPacket packet = new ClickSlotC2SPacket(syncId, revision, (short) slot, (byte) button, action, 
                                                                   new Int2ObjectArrayMap<>(), ItemStackHash.EMPTY);
                Runnable sender = createPacketSender(delayBox.isSelected(), packet);
                
                for (int i = 0; i < timesToSend; i++) {
                    sender.run();
                }
                
                showSuccess(statusLabel);
            } else {
                showError(statusLabel, "invalid action type");
            }
        } catch (Exception e) {
            showError(statusLabel, "you must be connected to a server");
        }
    }

    // sends button click packets based on input parameters
    private static void sendButtonClickPackets(JTextField syncIdField, JTextField buttonIdField, JCheckBox delayBox,
                                             JTextField timesToSendField, JLabel statusLabel) {
        try {
            int syncId = Integer.parseInt(syncIdField.getText());
            int buttonId = Integer.parseInt(buttonIdField.getText());
            int timesToSend = Integer.parseInt(timesToSendField.getText());

            ButtonClickC2SPacket packet = new ButtonClickC2SPacket(syncId, buttonId);
            Runnable sender = createPacketSender(delayBox.isSelected(), packet);
            
            for (int i = 0; i < timesToSend; i++) {
                sender.run();
            }
            
            showSuccess(statusLabel);
        } catch (Exception e) {
            showError(statusLabel, "you must be connected to a server");
        }
    }

    // creates packet sender runnable that either delays or sends immediately
    @NotNull
    private static Runnable createPacketSender(boolean delay, Packet<?> packet) {
        if (delay) {
            return () -> {
                if (mc.getNetworkHandler() != null) {
                    SharedVariables.delayedUIPackets.add(packet);
                } else {
                    LOGGER.warn("network handler null while queuing delayed packets");
                }
            };
        } else {
            return () -> {
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(packet);
                } else {
                    LOGGER.warn("network handler null while sending packets");
                }
            };
        }
    }

    // shows success message on status label
    private static void showSuccess(JLabel statusLabel) {
        statusLabel.setVisible(true);
        statusLabel.setForeground(Color.GREEN.darker());
        statusLabel.setText("sent successfully");
        queueTask(() -> {
            statusLabel.setVisible(false);
            statusLabel.setText("");
        }, 1500L);
    }

    // shows error message on status label
    private static void showError(JLabel statusLabel, String message) {
        statusLabel.setVisible(true);
        statusLabel.setForeground(Color.RED.darker());
        statusLabel.setText(message);
        queueTask(() -> {
            statusLabel.setVisible(false);
            statusLabel.setText("");
        }, 1500L);
    }

    // adds all components to click slot gui frame
    private static void addClickSlotComponents(JFrame frame, JTextField syncIdField, JTextField revisionField,
                                             JTextField slotField, JTextField buttonField, JComboBox<String> actionField,
                                             JCheckBox delayBox, JTextField timesToSendField, JButton sendButton, JLabel statusLabel) {
        // labels for input fields
        frame.add(createLabel("Sync Id:", 25, 25));
        frame.add(createLabel("Revision:", 25, 50));
        frame.add(createLabel("Slot:", 25, 75));
        frame.add(createLabel("Button:", 25, 100));
        frame.add(createLabel("Action:", 25, 125));
        frame.add(createLabel("Times to send:", 25, 190));
        
        // input components
        frame.add(syncIdField);
        frame.add(revisionField);
        frame.add(slotField);
        frame.add(buttonField);
        frame.add(actionField);
        frame.add(timesToSendField);
        frame.add(delayBox);
        frame.add(sendButton);
        frame.add(statusLabel);
    }

    // adds all components to button click gui frame
    private static void addButtonClickComponents(JFrame frame, JTextField syncIdField, JTextField buttonIdField,
                                               JTextField timesToSendField, JCheckBox delayBox, JButton sendButton, JLabel statusLabel) {
        // labels for input fields
        frame.add(createLabel("Sync Id:", 25, 25));
        frame.add(createLabel("Button Id:", 25, 50));
        frame.add(createLabel("Times to send:", 25, 130));
        
        // input components
        frame.add(syncIdField);
        frame.add(buttonIdField);
        frame.add(timesToSendField);
        frame.add(delayBox);
        frame.add(sendButton);
        frame.add(statusLabel);
    }

    // helper method to create styled labels
    private static JLabel createLabel(String text, int x, int y) {
        JLabel label = new JLabel(text);
        label.setFocusable(false);
        label.setFont(monospace);
        label.setBounds(x, y, 100, 20);
        return label;
    }

    // checks if string can be parsed as integer
    public static boolean isInteger(String string) {
        try {
            Integer.parseInt(string);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // converts string to corresponding slot action type enum
    public static SlotActionType stringToSlotActionType(String string) {
        return switch (string) {
            case "PICKUP" -> SlotActionType.PICKUP;
            case "QUICK_MOVE" -> SlotActionType.QUICK_MOVE;
            case "SWAP" -> SlotActionType.SWAP;
            case "CLONE" -> SlotActionType.CLONE;
            case "THROW" -> SlotActionType.THROW;
            case "QUICK_CRAFT" -> SlotActionType.QUICK_CRAFT;
            case "PICKUP_ALL" -> SlotActionType.PICKUP_ALL;
            default -> null;
        };
    }

    // schedules task to run on main thread after delay
    public static void queueTask(Runnable runnable, long delayMs) {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                MinecraftClient.getInstance().send(runnable);
            }
        }, delayMs);
    }

    // gets version string for specified mod id
    public static String getModVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("null");
    }
}
package com.ui_utils.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.ui_utils.SharedVariables;

import net.minecraft.network.ClientConnection;
// We no longer need PacketCallbacks, so the import can be removed.
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;

@Mixin(ClientConnection.class)
public class ClientConnectionMixin {

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
        
        // Checks for if packets should be sent and if the packet is a GUI-related packet
        if (!SharedVariables.sendUIPackets && (packet instanceof ClickSlotC2SPacket || packet instanceof ButtonClickC2SPacket)) {
            ci.cancel();
            return;
        }

        // Checks for if packets should be delayed and adds them to a list
        if (SharedVariables.delayUIPackets && (packet instanceof ClickSlotC2SPacket || packet instanceof ButtonClickC2SPacket)) {
            SharedVariables.delayedUIPackets.add(packet);
            ci.cancel();
            return;
        }

        // Cancels sign update packets if sign editing is disabled
        if (!SharedVariables.shouldEditSign && (packet instanceof UpdateSignC2SPacket)) {
            SharedVariables.shouldEditSign = true;
            ci.cancel();
        }
    }
}
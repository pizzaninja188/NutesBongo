package io.github.noeppi_noeppi.mods.bongo;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.sounds.SoundEvents;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class CountdownOverlay {

    private static int ticksRemaining = 0;

    public static void startCountdown(int ticks) {
        ticksRemaining = ticks;
    }

    public static void stopCountdown() {
        ticksRemaining = 0;
    }

    public static boolean isActive() {
        return ticksRemaining > -20;
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Pre event) {
        if (ticksRemaining > -20) {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;

            PoseStack poseStack = event.getGuiGraphics().pose();
            String text;
            int alpha = 255;

            if (ticksRemaining > 20) {
                text = Integer.toString(ticksRemaining / 20);
            } else {
                text = "GO!";
                alpha = (int) ((ticksRemaining + 20) / 20f * 255);
                alpha = Math.max(0, Math.min(255, alpha));
            }

            int width = mc.getWindow().getGuiScaledWidth();
            int height = mc.getWindow().getGuiScaledHeight();

            float x = (width - font.width(text)) / 2f;
            float y = height / 3f;

            // 30 to 11 seconds white
            // 10 to 4 seconds yellow
            // 3 to 0 seconds red
            int color;
            if (ticksRemaining >= 220) color = 0xFFFFFF;
            else if (ticksRemaining >= 80) color = 0xFFFF00;
            else color = 0xFF0000;

            // Draw with shadow
            event.getGuiGraphics().drawString(
                    font,
                    text,
                    (int) x,
                    (int) y,
                    (alpha << 24) | color, // Fade white text
                    false // no shadow
            );

            // Required to flush the draw
            event.getGuiGraphics().bufferSource().endBatch();
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && Minecraft.getInstance().player != null) {
            if (ticksRemaining >= -20) {
                // Play advancement sound when "GO!" first shows (ticksRemaining == 20)
                if (ticksRemaining == 20) {
                    Minecraft.getInstance().player.playSound(
                            SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                            1.0F,
                            1.0F
                    );
                }

                // Play click every second, on whole second boundaries (every 20 ticks)
                if (ticksRemaining > 20 && ticksRemaining <= 220 && ticksRemaining % 20 == 0) {
                    Minecraft.getInstance().player.playSound(
                            SoundEvents.DISPENSER_FAIL,
                            0.7F,
                            1.0F
                    );
                }

                ticksRemaining--;
            }
        }
    }
}
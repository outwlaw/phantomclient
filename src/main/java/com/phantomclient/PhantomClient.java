package com.phantomclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PhantomClient implements ClientModInitializer {
    public static final String MOD_ID = "phantomclient";
    private static final int ZOOM_FOV = 30;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

    private static KeyBinding zoomKey, hudKey, sprintKey, flyKey, speedKey,
            fullbrightKey, stepKey, auraKey;

    // estados
    private static boolean hudEnabled = true;
    private static boolean autoSprint, fly, speed, fullbright, step, aura;
    private static boolean zooming;
    private static int savedFov = 70;
    private static int auraCooldown;

    private static KeyBinding bind(String name, int key) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.phantomclient." + name, InputUtil.Type.KEYSYM, key, CATEGORY));
    }

    @Override
    public void onInitializeClient() {
        zoomKey       = bind("zoom", GLFW.GLFW_KEY_C);
        hudKey        = bind("hud", GLFW.GLFW_KEY_H);
        sprintKey     = bind("sprint", GLFW.GLFW_KEY_V);
        flyKey        = bind("fly", GLFW.GLFW_KEY_B);
        speedKey      = bind("speed", GLFW.GLFW_KEY_N);
        fullbrightKey = bind("fullbright", GLFW.GLFW_KEY_G);
        stepKey       = bind("step", GLFW.GLFW_KEY_M);
        auraKey       = bind("aura", GLFW.GLFW_KEY_K);

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register(this::renderHud);
    }

    private void onTick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            stopZoom(mc);
            return;
        }

        while (hudKey.wasPressed())        { hudEnabled = !hudEnabled; msg(p, "HUD", hudEnabled); }
        while (sprintKey.wasPressed())     { autoSprint = !autoSprint; msg(p, "Auto Sprint", autoSprint); }
        while (flyKey.wasPressed())        { fly = !fly; msg(p, "Fly", fly); if (!fly) disableFly(p); }
        while (speedKey.wasPressed())      { speed = !speed; msg(p, "Speed", speed); }
        while (fullbrightKey.wasPressed()) { fullbright = !fullbright; msg(p, "Fullbright", fullbright);
                                             if (!fullbright) p.removeStatusEffect(StatusEffects.NIGHT_VISION); }
        while (stepKey.wasPressed())       { step = !step; msg(p, "Step", step); if (!step) setStep(p, 0.6); }
        while (auraKey.wasPressed())       { aura = !aura; msg(p, "MobAura", aura); }

        // Zoom
        if (zoomKey.isPressed() && mc.currentScreen == null) {
            if (!zooming) { savedFov = mc.options.getFov().getValue(); zooming = true; }
            mc.options.getFov().setValue(ZOOM_FOV);
        } else {
            stopZoom(mc);
        }

        // Auto sprint
        if (autoSprint && mc.options.forwardKey.isPressed() && !p.isUsingItem()
                && !p.horizontalCollision && p.getHungerManager().getFoodLevel() > 6) {
            p.setSprinting(true);
        }

        // Fly
        if (fly) {
            p.getAbilities().allowFlying = true;
            p.getAbilities().setFlySpeed(0.08f);
        }

        // Speed (multiplica velocidade horizontal com limite)
        if (speed && p.isOnGround()
                && (mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed())) {
            Vec3d v = p.getVelocity();
            double h = Math.hypot(v.x, v.z);
            if (h > 0 && h < 0.45) {
                p.setVelocity(v.x * 1.25, v.y, v.z * 1.25);
            }
        }

        // Fullbright (night vision local)
        if (fullbright && !p.hasStatusEffect(StatusEffects.NIGHT_VISION)) {
            p.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.NIGHT_VISION, 1_000_000, 0, false, false, false));
        }

        // Step
        if (step) setStep(p, 1.0);

        // MobAura: ataca o mob hostil mais próximo a cada ~10 ticks
        if (aura) {
            if (auraCooldown > 0) {
                auraCooldown--;
            } else {
                List<HostileEntity> mobs = new ArrayList<>(mc.world.getEntitiesByClass(
                        HostileEntity.class, p.getBoundingBox().expand(4.0),
                        e -> e.isAlive() && e.distanceTo(p) <= 4.0f));
                mobs.stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)))
                        .ifPresent(t -> {
                            mc.interactionManager.attackEntity(p, t);
                            p.swingHand(Hand.MAIN_HAND);
                        });
                auraCooldown = 10;
            }
        }
    }

    private static void disableFly(ClientPlayerEntity p) {
        if (!p.isCreative() && !p.isSpectator()) {
            p.getAbilities().allowFlying = false;
            p.getAbilities().flying = false;
        }
        p.getAbilities().setFlySpeed(0.05f);
    }

    private static void setStep(ClientPlayerEntity p, double value) {
        EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
        if (a != null) a.setBaseValue(value);
    }

    private static void stopZoom(MinecraftClient mc) {
        if (zooming) {
            mc.options.getFov().setValue(savedFov);
            zooming = false;
        }
    }

    private static void msg(ClientPlayerEntity p, String name, boolean on) {
        p.sendMessage(Text.literal(name + ": " + (on ? "§aLIGADO" : "§cDESLIGADO")), true);
    }

    private void renderHud(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!hudEnabled || mc.player == null || mc.options.hudHidden) return;

        var tr = mc.textRenderer;
        int x = 4, y = 4, s = 11;

        ctx.drawTextWithShadow(tr, "§5Phantom §dClient §7v1.1", x, y, 0xFFFFFF); y += s;
        ctx.drawTextWithShadow(tr, "FPS: " + mc.getCurrentFps(), x, y, 0xFFFFFF); y += s;
        ctx.drawTextWithShadow(tr, String.format("XYZ: %.1f / %.1f / %.1f",
                mc.player.getX(), mc.player.getY(), mc.player.getZ()), x, y, 0xFFFFFF); y += s;
        ctx.drawTextWithShadow(tr, "Direção: " + mc.player.getHorizontalFacing().asString(),
                x, y, 0xFFFFFF); y += s + 2;

        if (autoSprint)  { ctx.drawTextWithShadow(tr, "§a+ Sprint", x, y, 0xFFFFFF); y += s; }
        if (fly)         { ctx.drawTextWithShadow(tr, "§a+ Fly", x, y, 0xFFFFFF); y += s; }
        if (speed)       { ctx.drawTextWithShadow(tr, "§a+ Speed", x, y, 0xFFFFFF); y += s; }
        if (fullbright)  { ctx.drawTextWithShadow(tr, "§a+ Fullbright", x, y, 0xFFFFFF); y += s; }
        if (step)        { ctx.drawTextWithShadow(tr, "§a+ Step", x, y, 0xFFFFFF); y += s; }
        if (aura)        { ctx.drawTextWithShadow(tr, "§a+ MobAura", x, y, 0xFFFFFF); }
    }
}

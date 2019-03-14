/*
 ** 2013 April 15
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.minecraft.mineshot.client;

import info.ata4.minecraft.mineshot.Mineshot;
import info.ata4.minecraft.mineshot.client.gui.GuiCamera;
import info.ata4.minecraft.mineshot.client.wrapper.Projection;
import info.ata4.minecraft.mineshot.client.wrapper.ToggleableClippingHelper;
import info.ata4.minecraft.mineshot.util.reflection.PrivateAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;

import java.text.DecimalFormat;

import static org.lwjgl.opengl.GL11.GL_MODELVIEW;
import static org.lwjgl.opengl.GL11.GL_PROJECTION;

/**
 * Key handler for keys that control the orthographic camera.
 * 
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
@Mod.EventBusSubscriber(modid = Mineshot.MOD_ID)
public class OrthoViewHandler implements PrivateAccessor {

    private static final float ZOOM_DEFAULT = 8f;
    private static final float ZOOM_STEP = 0.5f;
    private static final float ZOOM_MIN = 0.5f;
    private static final float ZOOM_MAX = 512f;
    private static final float XROT_DEFAULT = 30f;
    private static final float YROT_DEFAULT = 315f;
    private static final float ROTATE_STEP = 15f;
    private static final float ROTATE_SPEED = 4f;
    private static final float SECONDS_PER_TICK = 1f/20f;

    private final String keyCategory = "key.categories.mineshot";
    private final KeyBinding keyToggle = new KeyBinding("key.mineshot.ortho.toggle", 296, keyCategory);
    private final KeyBinding keyGui = new KeyBinding("key.mineshot.ortho.gui", 297, keyCategory);
    private final KeyBinding keyPreset = new KeyBinding("key.mineshot.ortho.preset", 92, keyCategory);
    private final KeyBinding keyZoomIn = new KeyBinding("key.mineshot.ortho.zoom_in", 93, keyCategory);
    private final KeyBinding keyZoomOut = new KeyBinding("key.mineshot.ortho.zoom_out", 91, keyCategory);
    private final KeyBinding keyRotateLeft = new KeyBinding("key.mineshot.ortho.rotate_left", 263, keyCategory);
    private final KeyBinding keyRotateRight = new KeyBinding("key.mineshot.ortho.rotate_right", 262, keyCategory);
    private final KeyBinding keyRotateUp = new KeyBinding("key.mineshot.ortho.rotate_up", 265, keyCategory);
    private final KeyBinding keyRotateDown = new KeyBinding("key.mineshot.ortho.rotate_down", 264, keyCategory);

    private final DecimalFormat valueDisplay = new DecimalFormat("0.000");

    private final Minecraft mc = Minecraft.getInstance();
    private final ToggleableClippingHelper clippingHelper = ToggleableClippingHelper.getInstance();
    private boolean clippingEnabled;
    
    private boolean enabled;
    private boolean heldItemTooltipsSetting;
    private boolean freeCam;
    private boolean clip;
    private boolean textIsActive;
    
    private float zoom;
    private float xRot;
    private float yRot;
    
    private int tick;
    private int tickPrevious;
    private int preset;
    private double partialPrevious;

    private final int[][] angles = {{0, 0}, {0, 1}, {0, 2}, {0, 3}, {1, 0}, {3, 0}};
    
    public OrthoViewHandler() {
        ClientRegistry.registerKeyBinding(keyToggle);
        ClientRegistry.registerKeyBinding(keyPreset);
        ClientRegistry.registerKeyBinding(keyGui);
        ClientRegistry.registerKeyBinding(keyZoomIn);
        ClientRegistry.registerKeyBinding(keyZoomOut);
        ClientRegistry.registerKeyBinding(keyRotateLeft);
        ClientRegistry.registerKeyBinding(keyRotateRight);
        ClientRegistry.registerKeyBinding(keyRotateUp);
        ClientRegistry.registerKeyBinding(keyRotateDown);
        heldItemTooltipsSetting = mc.gameSettings.heldItemTooltips;
        reset();
        zoom = ZOOM_DEFAULT;
        xRot = XROT_DEFAULT;
        yRot = YROT_DEFAULT;
    }
 
    private void reset() {
        freeCam = false;
        clip = false;
        tick = 0;
        tickPrevious = 0;
        preset = 0;
        partialPrevious = 0;
    }

    public boolean isEnabled() {

        return enabled;
    }
    
    public void enable() {
        // disable in multiplayer
        // Of course, programmers could just delete this check and abuse the
        // orthographic camera, but at least the official build won't support it
        if (!mc.isSingleplayer()) {
            mc.player.sendStatusMessage(new TextComponentTranslation("mineshot.ortho.mp"), true);
            return;
        }
        
        if (!enabled) {
            clippingEnabled = clippingHelper.isEnabled();
            clippingHelper.setEnabled(false);
            reset();
        }
        mc.gameSettings.heldItemTooltips = false;
        enabled = true;
    }
    
    public void disable() {
        if (enabled) {
            clippingHelper.setEnabled(clippingEnabled);
        }
        mc.gameSettings.heldItemTooltips = heldItemTooltipsSetting;
        enabled = false;
    }

    public void toggle() {
        if (enabled) {
            disable();
        } else {
            enable();
        }
    }
    
    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent evt) {
        if (GuiScreen.isCtrlKeyDown() && keyToggle.isKeyDown() && !freeCam) {
            zoom = ZOOM_DEFAULT;
            xRot = XROT_DEFAULT;
            yRot = YROT_DEFAULT;
            if (!enabled) {
                toggle();
            }
        } else if (keyToggle.isKeyDown()) {
            toggle();
        } else if (keyPreset.isKeyDown() && !freeCam && enabled) {
            // snap to preset depending on current values, doesn't trigger if a preset is already set, ignores presets using xRot != 0
            // change counter, direction depends on modifier key, includes checks to ensure array angles doesn't run out of bounds
            if (yRot / 90f - Math.floor(yRot / 90f) > 0 || xRot / 90f - Math.floor(xRot / 90f) > 0) {
                preset = GuiScreen.isCtrlKeyDown() ? ((int) Math.floor(yRot / 90f) % 4 + 4) % 4 : ((int) Math.ceil(yRot / 90f) % 4 + 4) % 4;
            } else {
                preset = GuiScreen.isCtrlKeyDown() ? ((preset - 1) % 6 + 6) % 6 : (preset + 1) % 6;
            }
            xRot = angles[preset][0] * 90f;
            yRot = angles[preset][1] * 90f;
        } else if (keyGui.isKeyDown() && enabled) {
            GuiCamera gui = new GuiCamera(this, mc.currentScreen, zoom, xRot, yRot, freeCam, clip, textIsActive);
            mc.displayGuiScreen(gui);
        }

        // snap values to step units
        // note: the smooth controls are handled in onFogDensity, since they need to be executed on every frame
        if (GuiScreen.isCtrlKeyDown()) {
            updateZoomAndRotation(1);

            xRot = Math.round(xRot / ROTATE_STEP) * ROTATE_STEP;
            yRot = Math.round(yRot / ROTATE_STEP) * ROTATE_STEP;
            zoom = Math.round(zoom / ZOOM_STEP) * ZOOM_STEP;
        }
    }

    public float fixValue(float value) {
        value = (value % 360f + 360f) % 360f;
        return value;
    }

    public float fixValue(float value, float minValue, float maxValue) {
        if (value < minValue) {
            value = minValue;
        } else if (value > maxValue) {
            value = maxValue;
        }
        return value;
    }
    
    private void updateZoomAndRotation(double multi) {
        if (keyZoomIn.isKeyDown()) {
            zoom *= 1 - ZOOM_STEP * multi;
        }
        if (keyZoomOut.isKeyDown()) {
            zoom *= 1 + ZOOM_STEP * multi;
        }

        if (keyRotateLeft.isKeyDown()) {
            yRot += (int) fixValue(zoom, 8f, 32f) * multi;
        }
        if (keyRotateRight.isKeyDown()) {
            yRot -= (int) fixValue(zoom, 8f, 32f) * multi;
        }

        if (keyRotateUp.isKeyDown()) {
            xRot += (int) fixValue(zoom, 8f, 32f) * multi;
        }
        if (keyRotateDown.isKeyDown()) {
            xRot -= (int) fixValue(zoom, 8f, 32f) * multi;
        }
    }
    
    @SubscribeEvent
    public void onTick(ClientTickEvent evt) {
        if (!enabled || evt.phase != Phase.START) {
            return;
        }
        
        tick++;
    }

    /**
     * Cancels rendering of most parts of the game overlay when camera mode is active.
     */
    @SubscribeEvent
    public void onRenderGameOverlay(RenderGameOverlayEvent.Pre evt) {
        boolean requiredEvents = evt.getType() == RenderGameOverlayEvent.ElementType.TEXT || evt.getType() == RenderGameOverlayEvent.ElementType.DEBUG || evt.getType() == RenderGameOverlayEvent.ElementType.FPS_GRAPH || evt.getType() == RenderGameOverlayEvent.ElementType.CHAT;
        if (!requiredEvents && enabled && evt.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            evt.setCanceled(true);
        }
    }

    /**
     * Cancels rendering of the player hand when camera mode is active.
     */
    @SubscribeEvent
    public void onRenderHand(RenderHandEvent evt) {
        if (enabled) {
            evt.setCanceled(true);
        }
    }

    /**
     * Cancels highlighting of focused block when camera mode is active.
     */
    @SubscribeEvent
    public void onDrawBlockHighlight(DrawBlockHighlightEvent evt) {
        if (enabled) {
            evt.setCanceled(true);
        }
    }

    /**
     * Disable and reset when leaving the world.
     */
   @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent evt) {
       zoom = ZOOM_DEFAULT;
       xRot = XROT_DEFAULT;
       yRot = YROT_DEFAULT;
       disable();
    }

    /**
     * Disable when changing dimensions.
     */
    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent evt) {
        disable();
    }
    
    @SubscribeEvent
    public void onFogDensity(EntityViewRenderEvent.FogDensity evt) {
        if (!enabled) {
            return;
        }
        
        // update zoom and rotation
        if (!GuiScreen.isCtrlKeyDown()) {
            int ticksElapsed = tick - tickPrevious;
            double partial = evt.getRenderPartialTicks();
            double elapsed = ticksElapsed + (partial - partialPrevious);
            elapsed *= SECONDS_PER_TICK * ROTATE_SPEED;
            updateZoomAndRotation(elapsed);
            
            tickPrevious = tick;
            partialPrevious = partial;
        }

        // rotate the orthographic camera with the player view
        if (freeCam) {
            xRot = mc.player.rotationPitch;
            yRot = mc.player.rotationYaw - 180f;
        }

        zoom = fixValue(zoom, ZOOM_MIN, ZOOM_MAX);
        xRot = fixValue(xRot);
        yRot = fixValue(yRot);

        float width = zoom * (mc.display / (float) mc.displayHeight);
        float height = zoom;

        // override projection matrix
        GlStateManager.matrixMode(GL_PROJECTION);
        GlStateManager.loadIdentity();

        // + 0.9 so the camera is centered on the player and not on its feet, but has to be + 0 for top / bottom view to be centered
        Projection.ortho(-width, width, -height + MathHelper.cos((float) Math.toRadians(2 * xRot)) * 0.45f + 0.45f, height + MathHelper.cos((float) Math.toRadians(2 * xRot)) * 0.45f + 0.45f, clip ? 0 : -9999, 9999);
        
        // override camera view matrix
        GlStateManager.matrixMode(GL_MODELVIEW);
        GlStateManager.loadIdentity();
        GlStateManager.rotatef(xRot, 1, 0, 0);
        GlStateManager.rotatef(yRot, 0, 1, 0);
        
        // fix particle rotation if the camera isn't following the player view
        if (!freeCam) {
            float pitch = xRot;
            float yaw = yRot + 180f;
            setRotationX(MathHelper.cos(yaw * (float) Math.PI / 180f));
            setRotationZ(MathHelper.sin(yaw * (float) Math.PI / 180f));
            setRotationYZ(-ActiveRenderInfo.getRotationZ() * MathHelper.sin(pitch * (float) Math.PI / 180f));
            setRotationXY(ActiveRenderInfo.getRotationX() * MathHelper.sin(pitch * (float) Math.PI / 180f));
            setRotationXZ(MathHelper.cos(pitch * (float) Math.PI / 180f));
        }
    }

    /**
     * Display various stats in debug menu, only active when the ortographic camera is.
     */
    @SubscribeEvent
    public void onTextRender(RenderGameOverlayEvent.Text evt)
    {
        if (evt.getType() != RenderGameOverlayEvent.ElementType.TEXT) {
            return;
        }

        if (mc.gameSettings.showDebugInfo && enabled) {
            evt.getRight().add("");
            evt.getRight().add("\u00A7nMineshot\u00A7r");
            evt.getRight().add("Zoom: " + valueDisplay.format(zoom));
            evt.getRight().add("X-Rotation: " + valueDisplay.format(xRot));
            evt.getRight().add("Y-Rotation: " + valueDisplay.format(yRot));
        }
    }

    /**
     * Accessed by GuiCamera for updating values when it finishes.
     */
    public void updateFromGui(float z, float x, float y, boolean free, boolean clipping, boolean text) {
        zoom = z;
        xRot = x;
        yRot = y;
        freeCam = free;
        clip = clipping;
        textIsActive = text;
    }

    /**
     * Accessed by GuiCamera for updating values when it finishes.
     */
    public void updateFromGui(float z, float x, float y) {
        zoom = z;
        xRot = x;
        yRot = y;
    }
}

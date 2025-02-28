/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 * Copyright (C) 2023 CinemaMod Group
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 * USA
 */

package net.ccbluex.liquidbounce.mcef.cef;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.ccbluex.liquidbounce.mcef.MCEFPlatform;
import net.ccbluex.liquidbounce.mcef.glfw.MCEFGlfwCursorHelper;
import net.ccbluex.liquidbounce.mcef.listeners.MCEFCursorChangeListener;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.callback.CefDragData;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.misc.CefCursorType;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.awt.*;
import java.nio.ByteBuffer;

import static net.ccbluex.liquidbounce.mcef.MCEF.mc;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * An instance of an "Off-screen rendered" Chromium web browser.
 * Complete with a renderer, keyboard and mouse inputs, optional
 * browser control shortcuts, cursor handling, drag & drop support.
 */
public class MCEFBrowser extends CefBrowserOsr {
    /**
     * The renderer for the browser.
     */
    private final MCEFRenderer renderer;
    /**
     * Stores information about drag & drop.
     */
    private final MCEFDragContext dragContext = new MCEFDragContext();
    /**
     * A listener that defines that happens when a cursor changes in the browser.
     * E.g. when you've hovered over a button, an input box, are selecting text, etc...
     * A default listener is created in the constructor that sets the cursor type to
     * the appropriate cursor based on the event.
     */
    private MCEFCursorChangeListener cursorChangeListener;
    /**
     * Used to track when a full repaint should occur.
     */
    private int lastWidth = 0, lastHeight = 0;
    /**
     * A bitset representing what mouse buttons are currently pressed.
     * CEF is a bit odd and implements mouse buttons as a part of modifier flags.
     */
    private int btnMask = 0;

    // Data relating to popups and graphics
    // Marked as protected in-case a mod wants to extend MCEFBrowser and override the repaint logic
    protected ByteBuffer popupGraphics;
    protected Rectangle popupSize;
    protected boolean showPopup = false;
    protected boolean popupDrawn = false;
    private long lastClickTime = 0;
    private int clicks;
    private int mouseButton;

    private final boolean isMacOs = MCEFPlatform.getPlatform().isMacOS();

    public MCEFBrowser(MCEFClient client, String url, boolean transparent, int frameRate) {
        super(client.getHandle(), url, transparent, null, new MCEFBrowserSettings(frameRate));
        renderer = new MCEFRenderer(transparent);
        cursorChangeListener = (cefCursorID) -> setCursor(CefCursorType.fromId(cefCursorID));

        mc.submit(renderer::initialize);
    }

    public MCEFRenderer getRenderer() {
        return renderer;
    }

    public MCEFCursorChangeListener getCursorChangeListener() {
        return cursorChangeListener;
    }

    public void setCursorChangeListener(MCEFCursorChangeListener cursorChangeListener) {
        this.cursorChangeListener = cursorChangeListener;
    }

    public MCEFDragContext getDragContext() {
        return dragContext;
    }

    // Popups
    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        super.onPopupShow(browser, show);
        showPopup = show;
        if (!show) popupDrawn = false;
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        super.onPopupSize(browser, size);
        popupSize = size;
        this.popupGraphics = ByteBuffer.allocateDirect(
                size.width * size.height * 4
        );
    }

    // Graphics
    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        // nothing to update
        if (dirtyRects.length == 0)
            return;

        if (!popup) {
            if (lastWidth != width || lastHeight != height) {
                lastWidth = width;
                lastHeight = height;
                // upload full texture
                // this also sets up the texture size and creates the texture
                renderer.onPaint(buffer, width, height);
            } else {
                if (renderer.getTextureID() == 0) return;
                RenderSystem.bindTexture(renderer.getTextureID());
                RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, width);
                for (Rectangle dirtyRect : dirtyRects) {
                    GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, dirtyRect.x);
                    GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, dirtyRect.y);
                    renderer.onPaint(buffer, dirtyRect.x, dirtyRect.y, dirtyRect.width, dirtyRect.height);
                }
                if ((popupDrawn || showPopup) && popupSize != null) {
                    // interpret where the popup was as a dirty rect
                    if (!showPopup) {
                        // if the popup is not visible, just draw the contents of the buffer
                        GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, popupSize.width);
                        GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, popupSize.height);
                        renderer.onPaint(buffer, popupSize.x, popupSize.y, popupSize.width, popupSize.height);
                        popupGraphics = null;
                        popupSize = null;
                    } else if (popupDrawn) {
                        // else, a use copy of the popup graphics, as it needs to remain visible
                        // and for some reason that I do not for the life of me understand, chromium does not seem to keep this data in memory outside of the paint loop, meaning it has to be copied around, which wastes performance
                        RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, popupSize.width);
                        GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
                        GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
                        renderer.onPaint(popupGraphics, popupSize.x, popupSize.y, popupSize.width, popupSize.height);
                    }
                }
            }
        } else {
            if (renderer.getTextureID() == 0) return;
            RenderSystem.bindTexture(renderer.getTextureID());
            int start = buffer.capacity();
            int end = 0;
            for (Rectangle dirtyRect : dirtyRects) {
                RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, popupSize.width);
                GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, dirtyRect.x);
                GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, dirtyRect.y);
                renderer.onPaint(buffer, popupSize.x + dirtyRect.x, popupSize.y + dirtyRect.y, dirtyRect.width, dirtyRect.height);

                int rectStart = (dirtyRect.x + ((dirtyRect.y) * popupSize.width)) << 2;
                if (rectStart < start) start = rectStart;

                int rectEnd = ((dirtyRect.x + dirtyRect.width) + ((dirtyRect.y + popupSize.height) * dirtyRect.width)) << 2;
                if (rectEnd > end) end = rectEnd;
            }
            if (start < 0) start = 0;
            if (end > buffer.capacity()) end = buffer.capacity();

            if (end > start) {
                // TODO: check if it's more performant to go for row-wise copies or if it's better to just copy the updated region
                if (this.popupGraphics != null) {
                    long addrFrom = MemoryUtil.memAddress(buffer);
                    long addrTo = MemoryUtil.memAddress(popupGraphics);
                    MemoryUtil.memCopy(
                            addrFrom + start,
                            addrTo + start,
                            (end - start)
                    );
                }
            }

            popupDrawn = true;
        }
    }

    public void resize(int width, int height) {
        browser_rect_.setBounds(0, 0, width, height);
        wasResized(width, height);
    }

    // Inputs
    public void sendKeyPress(int keyCode, long scanCode, int modifiers) {
        if (modifiers == GLFW_MOD_CONTROL && keyCode == GLFW_KEY_R) {
            reload();
            return;
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_PRESS, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) {
        if (modifiers == GLFW_MOD_CONTROL && keyCode == GLFW_KEY_R) {
            return;
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_RELEASE, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyTyped(char c, int modifiers) {
        if (modifiers == GLFW_MOD_CONTROL && (int) c == GLFW_KEY_R) {
            return;
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_TYPE, c, c, modifiers);
        sendKeyEvent(e);
    }

    public void sendMouseMove(int mouseX, int mouseY) {
        sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, mouseX, mouseY, clicks, mouseButton,
                dragContext.getVirtualModifiers(btnMask)));

        if (dragContext.isDragging()) {
            this.dragTargetDragOver(new Point(mouseX, mouseY), 0, dragContext.getMask());
        }
    }

    public void sendMousePress(int mouseX, int mouseY, int button) {
        button = swapButton(button);

        if (button == 0) {
            btnMask |= CefMouseEvent.BUTTON1_MASK;
        } else if (button == 1) {
            btnMask |= CefMouseEvent.BUTTON2_MASK;
        } else if (button == 2) {
            btnMask |= CefMouseEvent.BUTTON3_MASK;
        }

        // Double click handling
        var time = System.currentTimeMillis();
        clicks = time - lastClickTime < 500 ? 2 : 1;

        sendMouseEvent(new CefMouseEvent(GLFW_PRESS, mouseX, mouseY, clicks, button, btnMask));

        this.lastClickTime = time;
        this.mouseButton = button;
    }

    // TODO: it may be necessary to add modifiers here
    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        button = swapButton(button);

        if (button == 0 && (btnMask & CefMouseEvent.BUTTON1_MASK) != 0) {
            btnMask ^= CefMouseEvent.BUTTON1_MASK;
        } else if (button == 1 && (btnMask & CefMouseEvent.BUTTON2_MASK) != 0) {
            btnMask ^= CefMouseEvent.BUTTON2_MASK;
        } else if (button == 2 && (btnMask & CefMouseEvent.BUTTON3_MASK) != 0) {
            btnMask ^= CefMouseEvent.BUTTON3_MASK;
        }

        // drag & drop
        if (dragContext.isDragging()) {
            if (button == 0) {
                finishDragging(mouseX, mouseY);
            }
        }

        sendMouseEvent(new CefMouseEvent(GLFW_RELEASE, mouseX, mouseY, clicks, button, btnMask));
        this.mouseButton = 0;
    }

    public void sendMouseWheel(int mouseX, int mouseY, double amount) {
        // macOS generally has a slow scroll speed that feels more natural with their magic mice / trackpads
        if (!isMacOs) {
            // This removes the feeling of "smooth scroll"
            if (amount < 0) {
                amount = Math.floor(amount);
            } else {
                amount = Math.ceil(amount);
            }

            // This feels about equivalent to chromium with smooth scrolling disabled -ds58
            amount = amount * 3;
        }

        var event = new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, mouseX, mouseY, amount, 0);
        sendMouseWheelEvent(event);
    }

    // Drag & drop
    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        dragContext.startDragging(dragData, mask);
        this.dragTargetDragEnter(dragContext.getDragData(), new Point(x, y), btnMask, dragContext.getMask());
        // Indicates to CEF to not handle the drag event natively
        // reason: native drag handling doesn't work with off-screen rendering
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        if (dragContext.updateCursor(operation)) {
            // If the cursor to display for the drag event changes, then update the cursor
            this.onCursorChange(this, dragContext.getVirtualCursor(dragContext.getActualCursor()));
        }

        super.updateDragCursor(browser, operation);
    }

    // Expose drag & drop functions
    public void startDragging(CefDragData dragData, int mask, int x, int y) {
        // Overload since the JCEF method requires a browser, which then goes unused
        startDragging(dragData, mask, x, y);
    }

    public void finishDragging(int x, int y) {
        dragTargetDrop(new Point(x, y), btnMask);
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    public void cancelDrag() {
        dragTargetDragLeave();
        dragContext.stopDragging();
        this.onCursorChange(this, dragContext.getActualCursor());
    }

    // Closing
    public void close() {
        renderer.cleanup();
        cursorChangeListener.onCursorChange(0);
        super.close(true);
    }

    @Override
    protected void finalize() throws Throwable {
        mc.submit(renderer::cleanup);
        super.finalize();
    }

    // Cursor handling
    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        cursorType = dragContext.getVirtualCursor(cursorType);
        cursorChangeListener.onCursorChange(cursorType);
        return super.onCursorChange(browser, cursorType);
    }

    public void setCursor(CefCursorType cursorType) {
        var windowHandle = mc.getWindow().getHandle();

        // We do not want to change the cursor state since Minecraft does this for us.
        if (cursorType == CefCursorType.NONE) return;

        GLFW.glfwSetCursor(windowHandle, MCEFGlfwCursorHelper.getGLFWCursorHandle(cursorType));
    }

    /**
     * For some reason, middle and right are swapped in MC
     *
     * @param button the button to swap
     * @return the swapped button
     */
    private int swapButton(int button) {
        if (button == 1) {
            return 2;
        } else if (button == 2) {
            return 1;
        }

        return button;
    }

}

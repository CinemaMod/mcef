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

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.ccbluex.liquidbounce.mcef.MCEF;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.util.Identifier;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.UUID;

import static net.ccbluex.liquidbounce.mcef.MCEF.mc;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;

public class MCEFRenderer implements Closeable {

    private final boolean transparent;
    private @Nullable GpuTexture texture = null;
    private @Nullable GpuTexture sharedTexture = null;
    private int textureWidth = 0;
    private int textureHeight = 0;

    // ResourceLocation for this renderer's texture
    private final Identifier identifier;
    private MCEFDirectTexture directTexture;
    private boolean textureRegistered = false;

    private boolean isBGRA = false;
    private boolean unpainted = true;
    private boolean isAccelerated = false;

    protected MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
        // Generate a unique ResourceLocation for this renderer
        String uniqueId = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        this.identifier = Identifier.of("mcef", "browser_" + uniqueId);
    }

    /**
     * Initializes the renderer by generating a texture ID and setting up the texture parameters.
     */
    public void initialize() {
        // Create and register the direct texture wrapper with Minecraft's TextureManager
        directTexture = new MCEFDirectTexture();
        mc.getTextureManager().registerTexture(identifier, directTexture);
        textureRegistered = true;
    }

    /**
     * Returns the texture ID for the renderer. If accelerated rendering is enabled, it returns the shared texture ID.
     * @return OpenGL texture ID
     */
    @Deprecated(since = "1.21.5")
    public int getTextureID() {
        var texture = getTexture();
        return !(texture instanceof GlTexture) ? 0 : ((GlTexture) texture).getGlId();
    }

    /**
     * Returns the texture for the renderer. If accelerated rendering is enabled, it returns the shared texture.
     * @return GpuTexture
     */
    public @Nullable GpuTexture getTexture() {
        if (isAccelerated) {
            return sharedTexture;
        } else {
            return texture;
        }
    }

    /**
     * Gets the Identifier that can be used with GuiGraphics and other Minecraft rendering methods.
     * This Identifier is registered with the TextureManager and points to the browser's texture.
     */
    public Identifier getIdentifier() {
        return identifier;
    }

    /**
     * Check if the texture is ready for rendering with GuiGraphics
     */
    public boolean isTextureReady() {
        return isAccelerated ? sharedTexture != null : texture != null && textureRegistered && directTexture != null;
    }

    /**
     * Checks if the texture is unpainted. A texture is considered unpainted if it has not been painted yet,
     * which means no paint calls have been made since the last initialization or cleanup.
     */
    public boolean isUnpainted() {
        if (isAccelerated && sharedTexture == null) {
            return false;
        }

        if (texture == null) {
            return false;
        }

        return unpainted;
    }

    public int getTextureWidth() {
        return textureWidth;
    }

    public int getTextureHeight() {
        return textureHeight;
    }

    /**
     * Determines if the renderer is transparent.
     */
    public boolean isTransparent() {
        return transparent;
    }

    /**
     * Checks if the renderer is using accelerated rendering. This is true when CEF calls
     * [onAcceleratedPaint] with a valid {@link CefAcceleratedPaintInfo} object, instead of
     * [onPaint] with a ByteBuffer.
     * @return true if the renderer is using accelerated rendering, false otherwise.
     */
    public boolean isAccelerated() {
        return isAccelerated;
    }

    /**
     * Checks if the texture format is BGRA. This is the case when we use [onAcceleratedPaint] with
     * {@link CefAcceleratedPaintInfo} as it uses the BGRA format for shared textures.
     *
     * @return true if the texture format is BGRA, false otherwise
     */
    public boolean isBGRA() {
        return isBGRA;
    }

    /**
     * Handles accelerated paint events from CEF. This method is called when CEF provides a shared texture
     * for accelerated rendering. On Windows, this texture is a D3D11 shared texture handle.
     * <p>
     * TODO: For other platforms, we have no support yet.
     *
     * @param info   The CefAcceleratedPaintInfo containing the shared texture handle and other information.
     * @param width  The width of the texture.
     * @param height The height of the texture.
     */
    protected void onAcceleratedPaint(CefAcceleratedPaintInfo info, int width, int height) {
        RenderSystem.assertOnRenderThread();

        if (transparent) {
            GlStateManager._enableBlend();
        }

        // Create a new texture that we can copy the shared texture into. Unfortunately, textures are immutable,
        // so we have to create a new one
        var sharedTextureId = glGenTextures();

        // Create the memory object handle
        var memoryObject = glCreateMemoryObjectsEXT();
        if (memoryObject == 0) {
            MCEF.INSTANCE.LOGGER.error("Failed to create memory object for shared texture.");
            glDeleteTextures(sharedTextureId);
            return;
        }

        // The size of the texture we get from CEF. The CEF format is CEF_COLOR_TYPE_BGRA_8888
        // It has 4 bytes per pixel. The mem object requires this to be multiplied with 2
        var size = (long) width * height * 4 * 2;

        // Cef uses the GL_HANDLE_TYPE_D3D11_IMAGE_EXT handle for their shared texture
        // Import the shared texture to the memory object
        glImportMemoryWin32HandleEXT(memoryObject,
                size,
                GL_HANDLE_TYPE_D3D11_IMAGE_EXT,
                info.shared_texture_handle
        );

        GlStateManager._bindTexture(sharedTextureId);

        // Allocate immutable storage for the texture for the data from the memory object
        // Use GL_RGBA8 since it is 4 bytes
        glTexStorageMem2DEXT(
                GL_TEXTURE_2D,      // Target (not texture ID)
                1,                  // Mip levels
                GL_RGBA8,           // Internal format
                width,
                height,
                memoryObject,
                0                   // Offset
        );
        glFinish();

        if (this.sharedTexture != null) {
            this.sharedTexture.close();
        }

        glDeleteMemoryObjectsEXT(memoryObject);

        var sharedTexture = new MCEFDirectTexture();
        sharedTexture.setDirectTextureId(sharedTextureId, width, height);
        this.sharedTexture = sharedTexture.getGlTexture();

        isAccelerated = true;
        unpainted = false;
        isBGRA = true;

        GlStateManager._bindTexture(0);
    }

    /**
     * Paints the texture with the provided ByteBuffer data.
     * This method is called when CEF provides a ByteBuffer for painting.
     *
     * @param buffer The ByteBuffer containing the pixel data to paint.
     * @param width  The width of the texture.
     * @param height The height of the texture.
     */
    protected void onPaint(ByteBuffer buffer, int width, int height) {
        RenderSystem.assertOnRenderThread();

        // Create or recreate texture if size changed
        if (texture == null || textureWidth != width || textureHeight != height) {
            if (texture != null) {
                texture.close();
            }

            // Create new GpuTexture using the device
            String label = "MCEF Browser Texture " + width + "x" + height;
            texture = RenderSystem.getDevice().createTexture(
                    label,
                    TextureFormat.RGBA8,
                    width,
                    height,
                    1  // mipLevels
            );

            // Configure texture parameters
            texture.setTextureFilter(FilterMode.LINEAR, FilterMode.LINEAR, false);
            texture.setAddressMode(com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE);

            textureWidth = width;
            textureHeight = height;

            // Update the direct texture wrapper to point to our new texture
            if (directTexture != null && texture instanceof GlTexture glTexture) {
                directTexture.setDirectTextureId(glTexture.getGlId(), width, height);
            }
        }

        if (transparent) {
            GlStateManager._enableBlend();
        }

        if (texture instanceof GlTexture glTexture) {
            // Bind the texture directly using its GL ID
            GlStateManager._bindTexture(glTexture.getGlId());
            GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);

            // Upload the full texture
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);

            isBGRA = false;
            unpainted = false;
        }
    }

    /**
     * Paints a sub-region of the texture with the provided ByteBuffer data.
     * This method is called when CEF provides a ByteBuffer for painting a specific area.
     *
     * @param buffer The ByteBuffer containing the pixel data to paint.
     * @param x      The x-coordinate of the sub-region to paint.
     * @param y      The y-coordinate of the sub-region to paint.
     * @param width  The width of the sub-region to paint.
     * @param height The height of the sub-region to paint.
     */
    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        RenderSystem.assertOnRenderThread();

        if (texture instanceof GlTexture glTexture) {
            // Bind and update sub-region
            GlStateManager._bindTexture(glTexture.getGlId());
            glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA,
                    GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        }
    }

    /**
     * Clears the texture by binding it and filling it with transparent pixels.
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();

        if (texture != null) {
            texture.close();
        }

        if (sharedTexture != null) {
            sharedTexture.close();
        }

        // Unregister from TextureManager
        if (textureRegistered && identifier != null) {
            mc.getTextureManager().destroyTexture(identifier);
            textureRegistered = false;
        }

        isAccelerated = false;
    }

}
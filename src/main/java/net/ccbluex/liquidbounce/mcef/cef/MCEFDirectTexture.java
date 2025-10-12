/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package net.ccbluex.liquidbounce.mcef.cef;

import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;

/**
 * A more efficient texture implementation that directly wraps an existing OpenGL texture ID.
 * This bypasses the normal texture creation pipeline and allows us to use an existing texture
 * directly with Minecraft's rendering system.
 */
public class MCEFDirectTexture extends AbstractTexture {
    private int width;
    private int height;
    
    public MCEFDirectTexture() {
        this.bilinear = false;
    }
    
    /**
     * Directly set the texture to an existing OpenGL texture ID.
     * This is more efficient than creating a new texture and copying data.
     * 
     * @param textureId The OpenGL texture ID to wrap
     * @param width The width of the texture
     * @param height The height of the texture
     */
    public void setDirectTextureId(int textureId, int width, int height) {
        // If we already have a texture and it's not the same ID, don't close it
        // (we don't own these textures, MCEFRenderer does)
        
        if (textureId > 0) {
            // Create a custom GlTexture that wraps the existing ID
            this.glTexture = new DirectGlTexture(textureId, width, height);
            this.width = width;
            this.height = height;
        } else {
            this.glTexture = null;
        }
    }
    
    public int getWidth() {
        return width;
    }
    
    public int getHeight() {
        return height;
    }
    
    @Override
    public void close() {
        // Don't close the texture - we don't own it
        this.glTexture = null;
    }
    
    /**
     * Custom GlTexture implementation that wraps an existing OpenGL texture ID
     * without managing its lifecycle.
     */
    private static class DirectGlTexture extends GlTexture {
        private final int width;
        private final int height;
        
        protected DirectGlTexture(int textureId, int width, int height) {
            // Call parent constructor with dummy values, then override
            super("MCEF Direct Texture", TextureFormat.RGBA8, width, height, 1, textureId);
            this.width = width;
            this.height = height;
            // Mark as not closed
            this.closed = false;
        }
        
        @Override
        public void close() {
            // Don't actually delete the texture - we don't own it
            this.closed = true;
        }
        
        @Override
        public int getWidth(int mipLevel) {
            return width >> mipLevel;
        }
        
        @Override
        public int getHeight(int mipLevel) {
            return height >> mipLevel;
        }
    }
}

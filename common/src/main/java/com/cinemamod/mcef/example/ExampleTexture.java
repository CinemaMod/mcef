package com.cinemamod.mcef.example;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jetbrains.annotations.NotNull;

public class ExampleTexture extends AbstractTexture {
    protected final ExampleGlTexture glTexture;

    public ExampleTexture(int id, @NotNull String label) {
        this.glTexture = new ExampleGlTexture(5, label, TextureFormat.RGBA8, 100, 100, 1, 1, id);
        this.glTexture.setTextureFilter(FilterMode.NEAREST, false);
        this.texture = this.glTexture;
        GpuDevice device = RenderSystem.getDevice();
        this.textureView = device.createTextureView(this.texture);
    }

    @Override
    public void setFilter(boolean $$0, boolean $$1) {
        // do nothing
    }

    @Override
    public void setClamp(boolean $$0) {
        // do nothing
    }

    public void setId(int id) {
        this.glTexture.setGlId(id);
    }

    public void setWidth(int width) {
        this.glTexture.setWidth(width);
    }

    public void setHeight(int height) {
        this.glTexture.setHeight(height);
    }

}

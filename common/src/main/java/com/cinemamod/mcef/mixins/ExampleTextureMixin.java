package com.cinemamod.mcef.mixins;

import com.mojang.blaze3d.opengl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlTexture.class)
public interface ExampleTextureMixin {
    @Accessor("id")
    @Mutable
    void setId(int id);
}

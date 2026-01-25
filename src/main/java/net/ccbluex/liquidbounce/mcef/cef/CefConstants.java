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

import org.lwjgl.egl.EXTImageDMABufImport;
import org.lwjgl.egl.EXTImageDMABufImportModifiers;

final class CefConstants {

    static final int CEF_COLOR_TYPE_RGBA_8888 = 0;
    static final int CEF_COLOR_TYPE_BGRA_8888 = 1;

    static final int DRM_FORMAT_ARGB8888 = fourCC('A', 'R', '2', '4');
    static final int DRM_FORMAT_ABGR8888 = fourCC('A', 'B', '2', '4');

    static final int[] DMA_BUF_PLANE_FD_ATTRS = new int[]{
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE0_FD_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE1_FD_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE2_FD_EXT
    };
    static final int[] DMA_BUF_PLANE_OFFSET_ATTRS = new int[]{
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE0_OFFSET_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE1_OFFSET_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE2_OFFSET_EXT
    };
    static final int[] DMA_BUF_PLANE_PITCH_ATTRS = new int[]{
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE0_PITCH_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE1_PITCH_EXT,
            EXTImageDMABufImport.EGL_DMA_BUF_PLANE2_PITCH_EXT
    };
    static final int[] DMA_BUF_PLANE_MODIFIER_LO_ATTRS = new int[]{
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE0_MODIFIER_LO_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE1_MODIFIER_LO_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE2_MODIFIER_LO_EXT
    };
    static final int[] DMA_BUF_PLANE_MODIFIER_HI_ATTRS = new int[]{
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE0_MODIFIER_HI_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE1_MODIFIER_HI_EXT,
            EXTImageDMABufImportModifiers.EGL_DMA_BUF_PLANE2_MODIFIER_HI_EXT
    };

    private CefConstants() {}

    private static int fourCC(char a, char b, char c, char d) {
        return (a & 0xFF)
                | ((b & 0xFF) << 8)
                | ((c & 0xFF) << 16)
                | ((d & 0xFF) << 24);
    }
}

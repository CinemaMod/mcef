/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
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

package net.ccbluex.liquidbounce.mcef.listeners;

public interface MCEFProgressListener {

    /**
     * Progress update for general tasks
     *
     * @param task Task name
     * @param progress Progress
     */
    void onProgressUpdate(String task, float progress);

    /**
     * If everything is complete
     */
    void onComplete();

    /**
     * File download or extraction start
     * @param task Task name
     */
    void onFileStart(String task);

    /**
     * File download or extraction progress
     * @param task Task name
     * @param bytesRead Bytes read
     * @param contentLength Total bytes
     * @param done Is download or extraction done
     */
    void onFileProgress(String task, long bytesRead, long contentLength, boolean done);

    /**
     * File download or extraction end
     * @param task Task name
     */
    void onFileEnd(String task);

}

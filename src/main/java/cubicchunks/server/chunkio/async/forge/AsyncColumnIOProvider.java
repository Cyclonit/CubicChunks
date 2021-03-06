/*
 * Minecraft Forge
 * Copyright (c) 2016.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package cubicchunks.server.chunkio.async.forge;

import java.io.IOException;

import cubicchunks.CubicChunks;
import cubicchunks.server.chunkio.CubeIO;
import cubicchunks.world.column.Column;

/**
 * Async loading of columns. Roughly equivalent to Forge's ChunkIOProvider
 */
class AsyncColumnIOProvider extends AsyncIOProvider<Column> {
	private final CubeIO loader;
	private Column column; // The target
	private final QueuedColumn colInfo;

	AsyncColumnIOProvider(QueuedColumn colInfo, CubeIO loader) {
		this.loader = loader;
		this.colInfo = colInfo;
	}

	@Override void runSynchronousPart() {
		if (column != null) {
			//TODO: ChunkDataEvent.Load and Save
			//MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Load(column, this.nbt)); // Don't call ChunkDataEvent.Load async
			column.setLastSaveTime(this.colInfo.world.getTotalWorldTime());
		}
		runCallbacks();
	}

	@Override Column get() {
		return column;
	}

	@Override public void run() {
		synchronized (this) {
			try {
				this.column = this.loader.loadColumn(this.colInfo.x, this.colInfo.z);
			} catch (IOException e) {
				CubicChunks.LOGGER.error("Could not load column in {} @ ({}, {})", this.colInfo.world, this.colInfo.x, this.colInfo.z, e);
			}

			this.finished = true;
			this.notifyAll();
		}
	}
}

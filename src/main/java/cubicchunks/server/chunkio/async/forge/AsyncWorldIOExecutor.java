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

import com.google.common.collect.Maps;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import cubicchunks.CubicChunks;
import cubicchunks.server.CubeProviderServer;
import cubicchunks.server.chunkio.CubeIO;
import cubicchunks.world.ICubicWorld;
import cubicchunks.world.IProviderExtras;
import cubicchunks.world.column.Column;
import cubicchunks.world.cube.Cube;

/**
 * Brazenly copied from Forge and Sponge and reimplemented to suit our needs: Load cubes and columns outside the main
 * thread, then synchronize at the start of the next tick
 */
public class AsyncWorldIOExecutor {

	private static final int BASE_THREADS = 1;
	private static final int PLAYERS_PER_THREAD = 50;

	private static final Map<QueuedCube, AsyncCubeIOProvider> cubeTasks = Maps.newConcurrentMap();
	private static final Map<QueuedColumn, AsyncColumnIOProvider> columnTasks = Maps.newConcurrentMap();

	private static final AtomicInteger threadCounter = new AtomicInteger();
	private static final ThreadPoolExecutor pool = new ThreadPoolExecutor(BASE_THREADS, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
		new LinkedBlockingQueue<>(),

		// Sponge start: Use lambda
		r -> {
			Thread thread = new Thread(r, "Cube I/O Thread #" + threadCounter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
		// Sponge end
	);

	/**
	 * Load a cube, directly.
	 *
	 * @param world The world in which the cube lies
	 * @param loader The file loader for cubes
	 * @param cache the cube cache used to load cubes and columns
	 * @param cubeX X coordinate of the cube to load
	 * @param cubeY Y coordinate of the cube to load
	 * @param cubeZ Z coordinate of the cube to load
	 *
	 * @return The loaded cube, or null if either not present or the load failed
	 */
	@Nullable
	public static Cube syncCubeLoad(ICubicWorld world, CubeIO loader, CubeProviderServer cache, int cubeX, int cubeY, int cubeZ) {
		Column column = cache.loadChunk(cubeX, cubeZ);
		QueuedCube key = new QueuedCube(cubeX, cubeY, cubeZ, world);
		AsyncCubeIOProvider task = cubeTasks.remove(key); // Remove task because we will call the sync callbacks directly
		if (task != null) {
			runTask(task);
		} else {
			task = new AsyncCubeIOProvider(key, loader);
			task.setColumn(column);
			task.run();
		}
		task.runSynchronousPart();
		return task.get();
	}

	/**
	 * Load a column, directly
	 *
	 * @param world The world in which the column lies
	 * @param loader The file loader for columns
	 * @param x column x position
	 * @param z column z position
	 *
	 * @return The loaded column
	 */
	public static Column syncColumnLoad(ICubicWorld world, CubeIO loader, int x, int z) {
		QueuedColumn key = new QueuedColumn(x, z, world);
		AsyncColumnIOProvider task = columnTasks.remove(key); // Remove task because we will call the sync callbacks directly
		if (task != null) {
			runTask(task);
		} else {
			task = new AsyncColumnIOProvider(key, loader);
			task.run();
		}
		task.runSynchronousPart();
		return task.get();
	}

	/**
	 * Runs the async part in current thread or blocks until already running async part is finished
	 */
	private static void runTask(AsyncIOProvider task) {
		if (!pool.remove(task)) // If it wasn't in the pool, and run hasn't isFinished, then wait for the async thread.
		{
			synchronized (task) // Warn incorrect - task shared via map
			{
				while (!task.isFinished()) {
					try {
						task.wait();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new RuntimeException("Failed to wait for cube/column load", e);
					}
				}
			}
		} else {
			// If the task was not run yet we still need to load the Cube
			task.run();
		}
	}

	//Queue the Cube to be loaded, and call the runnable when isFinished
	// Sponge: Runnable -> Consumer<Cube>

	/**
	 * Queue a cube load, running the specified callback when the load has finished. This may cause a two tick delay
	 * if the column has to be loaded, too! If you need it faster, consider sync loading either column or both
	 * cube and column.
	 *
	 * @param world The world of the cube
	 * @param loader The file loader for this world
	 * @param cache The server cube cache
	 * @param x cube x position
	 * @param y cube y position
	 * @param z cube z position
	 * @param runnable The callback
	 */
	public static void queueCubeLoad(ICubicWorld world, CubeIO loader, CubeProviderServer cache, int x, int y, int z, Consumer<Cube> runnable) {

		QueuedCube key = new QueuedCube(x, y, z, world);
		AsyncCubeIOProvider task = cubeTasks.get(key);

		if (task == null) {
			task = new AsyncCubeIOProvider(key, loader);
			task.addCallback(runnable); // Add before calling execute for thread safety
			cubeTasks.put(key, task);
			pool.execute(task);
		} else {
			task.addCallback(runnable);
		}

		Column loadedColumn;
		if ((loadedColumn = cache.getLoadedColumn(x, z)) == null) {
			cache.asyncGetColumn(x, z, IProviderExtras.Requirement.LIGHT, task::setColumn);
		} else {
			//it's already there, tell the task to use it
			task.setColumn(loadedColumn);
		}

	}

	/**
	 * Queue a column load, running the specified callback when the load has finished
	 *
	 * @param world The world of the column
	 * @param loader The file loader for this world
	 * @param x column x position
	 * @param z column z position
	 * @param runnable The callback
	 */
	public static void queueColumnLoad(ICubicWorld world, CubeIO loader, int x, int z, Consumer<Column> runnable) {
		QueuedColumn key = new QueuedColumn(x, z, world);
		AsyncColumnIOProvider task = columnTasks.get(key);
		if (task == null) {
			task = new AsyncColumnIOProvider(key, loader);
			task.addCallback(runnable); // Add before calling execute for thread safety
			columnTasks.put(key, task);
			pool.execute(task);
		} else {
			task.addCallback(runnable);
		}
	}

	/**
	 * Notify the loader that this cube isn't needed anymore
	 *
	 * @param world The world
	 * @param x cube x position
	 * @param y cube y position
	 * @param z cube z position
	 * @param runnable The runnable that should be dropped
	 */
	public static void dropQueuedCubeLoad(ICubicWorld world, int x, int y, int z, Consumer<Cube> runnable) {
		QueuedCube key = new QueuedCube(x, y, z, world);
		AsyncCubeIOProvider task = cubeTasks.get(key);
		if (task == null) {
			CubicChunks.LOGGER.warn("Attempting to drop cube that wasn't queued in {} @ ({}, {}, {})", world, x, y, z);
			return;
		}

		task.removeCallback(runnable);

		// TODO this is not threadsafe
		if (!task.hasCallbacks()) {
			cubeTasks.remove(key);
			pool.remove(task);
		}
	}

	/**
	 * Notify the loader that this column isn't needed anymore
	 *
	 * @param world The world
	 * @param x column x position
	 * @param z column z postion
	 * @param runnable The runnable that should be dropped
	 */
	public static void dropQueuedColumnLoad(ICubicWorld world, int x, int z, Consumer<Column> runnable) {
		QueuedColumn key = new QueuedColumn(x, z, world);
		AsyncColumnIOProvider task = columnTasks.get(key);
		if (task == null) {
			CubicChunks.LOGGER.warn("Attempting to drop column that wasn't queued in {} @ ({}, {})", world, x, z);
			return;
		}

		task.removeCallback(runnable);

		if (!task.hasCallbacks()) {
			columnTasks.remove(key);
			pool.remove(task);
		}

		//TODO: remove all queued cube tasks for that column
	}

	/**
	 * Run a synchronous tick, finishing the loading process for load tasks that are ready
	 */
	public static void tick() {
		Iterator<AsyncCubeIOProvider> cubeItr = cubeTasks.values().iterator();
		while (cubeItr.hasNext()) {
			AsyncCubeIOProvider task = cubeItr.next();
			if (task.isFinished()) {
				task.runSynchronousPart();

				cubeItr.remove();
			}
		}

		Iterator<AsyncColumnIOProvider> columnIter = columnTasks.values().iterator();
		while (columnIter.hasNext()) {
			AsyncColumnIOProvider task = columnIter.next();
			if (task.isFinished()) {
				task.runSynchronousPart();

				columnIter.remove();
			}
		}
	}

	/**
	 * Resize async loading pool thread count when players join or leave
	 *
	 * @param players New player count
	 */
	private static void adjustPoolSize(int players) {
		pool.setCorePoolSize(Math.max(BASE_THREADS, players/PLAYERS_PER_THREAD));
	}

	public static void registerListeners() {
		MinecraftForge.EVENT_BUS.register(new Object() {

			// Resize thread pool based on player count
			@SubscribeEvent
			public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent evt) {
				MinecraftServer server = evt.player.getServer();
				if (server != null) adjustPoolSize(server.getCurrentPlayerCount());
			}

			@SubscribeEvent
			public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent evt) {
				MinecraftServer server = evt.player.getServer();
				if (server != null) adjustPoolSize(server.getCurrentPlayerCount());
			}

			// Sync completion of loading
			@SubscribeEvent
			public void onWorldTick(TickEvent.WorldTickEvent evt) {
				tick();
			}
		});
	}
}

package io.github.opencubicchunks.cubicchunks.chunk.heightmap;

import io.github.opencubicchunks.cubicchunks.chunk.IBigCube;
import io.github.opencubicchunks.cubicchunks.chunk.util.CubePos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

public class LightSurfaceTrackerSection extends SurfaceTrackerSection {
    public LightSurfaceTrackerSection() {
        this(MAX_SCALE, 0, null);
    }

    public LightSurfaceTrackerSection(int scale, int scaledY, SurfaceTrackerSection parent) {
        // type shouldn't actually matter here
        super(scale, scaledY, parent, Heightmap.Types.WORLD_SURFACE);
    }

    public LightSurfaceTrackerSection(int scale, int scaledY, SurfaceTrackerSection parent, IBigCube cube) {
        super(scale, scaledY, parent, cube, Heightmap.Types.WORLD_SURFACE);
    }

    private LightSurfaceTrackerSection getRoot() {
        SurfaceTrackerSection section = this;
        while (section.parent != null) {
            section = section.parent;
        }
        return (LightSurfaceTrackerSection) section;
    }

    @Nullable
    @Override
    protected SurfaceTrackerSection loadNode(int newScaledY, int sectionScale, IBigCube newCube, boolean create) {
        // TODO: loading from disk
        if (!create) {
            return null;
        }
        if (sectionScale == 0) {
            return new LightSurfaceTrackerSection(sectionScale, newScaledY, this, newCube);
        }
        return new LightSurfaceTrackerSection(sectionScale, newScaledY, this);
    }

    @Nullable
    private LightSurfaceTrackerSection getSectionAbove() {
        if (scale != 0) {
            throw new IllegalArgumentException("TODO put an actual error message here - also this probably shouldn't be an IllegalArgumentException");
        }
        // TODO this can be optimized - don't need to go to the root every time, just the lowest node that is a parent of both this node and the node above.
        return (LightSurfaceTrackerSection) this.getRoot().getCubeNode(scaledY + 1);
    }

    @Override
    public int getHeight(int x, int z) {
        int idx = index(x, z);
        if (!isDirty(idx)) {
            int relativeY = heights.get(idx);
            return relToAbsY(relativeY, scaledY, scale);
        }

        synchronized (this) {
            int maxY = Integer.MIN_VALUE;
            if (scale == 0) {
                IBigCube cube = (IBigCube) cubeOrNodes;
                CubePos cubePos = cube.getCubePos();

                LightSurfaceTrackerSection sectionAbove = this.getSectionAbove();

                int dy = IBigCube.DIAMETER_IN_BLOCKS - 1;

                // TODO unknown behavior for occlusion on a loading boundary (i.e. sectionAbove == null)
                BlockState above = sectionAbove == null ? Blocks.AIR.defaultBlockState() : ((IBigCube) sectionAbove.cubeOrNodes).getBlockState(x, 0, z);
                BlockState state = cube.getBlockState(x, dy, z);

                // note that this BlockPos relies on `cubePos.blockY` returning correct results when the local coord is not inside the cube
                VoxelShape voxelShapeAbove = sectionAbove == null ? Shapes.empty() : this.getShape(above, new BlockPos(cubePos.blockX(x), cubePos.blockY(dy + 1), cubePos.blockZ(z)), Direction.DOWN);
                VoxelShape voxelShape = this.getShape(state, new BlockPos(cubePos.blockX(x), cubePos.blockY(dy), cubePos.blockZ(z)), Direction.UP);

                while (dy >= 0) {
                    int lightBlock = state.getLightBlock(cube, new BlockPos(cubePos.blockX(x), cubePos.blockY(dy), cubePos.blockZ(z)));
                    if (lightBlock > 0 || Shapes.faceShapeOccludes(voxelShapeAbove, voxelShape)) {
                        int minY = scaledY * IBigCube.DIAMETER_IN_BLOCKS;
                        maxY = minY + dy;
                        break;
                    }
                    dy--;
                    if (dy >= 0) {
                        above = state;
                        state = cube.getBlockState(x, dy, z);
                        voxelShapeAbove = this.getShape(above, new BlockPos(cubePos.blockX(x), cubePos.blockY(dy + 1), cubePos.blockZ(z)), Direction.DOWN);
                        voxelShape = this.getShape(state, new BlockPos(cubePos.blockX(x), cubePos.blockY(dy), cubePos.blockZ(z)), Direction.UP);
                    }
                }
            } else {
                SurfaceTrackerSection[] nodes = (SurfaceTrackerSection[]) cubeOrNodes;
                for (int i = nodes.length - 1; i >= 0; i--) {
                    SurfaceTrackerSection node = nodes[i];
                    if (node == null) {
                        continue;
                    }
                    int y = node.getHeight(x, z);
                    if (y != Integer.MIN_VALUE) {
                        maxY = y;
                        break;
                    }
                }
            }
            heights.set(idx, absToRelY(maxY, scaledY, scale));
            clearDirty(idx);
            return maxY;
        }
    }

    protected VoxelShape getShape(BlockState blockState, BlockPos pos, Direction facing) {
        return blockState.canOcclude() && blockState.useShapeForLightOcclusion() ? blockState.getFaceOcclusionShape((IBigCube) this.cubeOrNodes, pos, facing) : Shapes.empty();
    }
}

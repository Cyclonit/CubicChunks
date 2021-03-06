/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package cubicchunks.asm.mixin.noncritical.common.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.ref.WeakReference;

import cubicchunks.world.ICubicWorld;

import static cubicchunks.asm.JvmNames.COMMAND_BASE_PARSE_DOUBLE;

@Mixin(CommandBase.class)
public class MixinCommandBase {
	//I hope there are no threads involved...
	private static WeakReference<ICubicWorld> commandWorld;

	//get command sender, can't fail (inject at HEAD)
	@Inject(method = "parseBlockPos", at = @At(value = "HEAD"))
	private static void parseBlockPosPre(ICommandSender sender, String[] args, int startIndex, boolean centerBlock, CallbackInfoReturnable<?> cbi) {
		commandWorld = new WeakReference<>((ICubicWorld) sender.getEntityWorld());
	}

	//modify parseDouble min argument
	@ModifyArg(method = "parseBlockPos",
	           at = @At(value = "INVOKE", target = COMMAND_BASE_PARSE_DOUBLE, ordinal = 1),
	           index = 2)
	private static int getMinY(int original) {
		if (commandWorld == null) {
			return original;
		}
		ICubicWorld world = commandWorld.get();
		if (world == null) {
			return original;
		}
		return world.getMinHeight();
	}

	//modify parseDouble max argument
	@ModifyArg(method = "parseBlockPos",
	           at = @At(value = "INVOKE", target = COMMAND_BASE_PARSE_DOUBLE, ordinal = 1),
	           index = 3)
	private static int getMaxY(int original) {
		ICubicWorld world = commandWorld.get();
		if (world == null) {
			return original;
		}
		return world.getMaxHeight();
	}
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import java.util.Collections;
import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

final class RemixCommand extends CommandBase {
    private final ClientEvents client;
    RemixCommand(ClientEvents client) { this.client = client; }
    @Override public String getName() { return "remix"; }
    @Override public String getUsage(ICommandSender sender) { return "/remix <on|off|status>"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) throw new WrongUsageException(getUsage(sender));
        switch (args[0]) {
            case "on": client.setEnabled(true); break;
            case "off": client.setEnabled(false); break;
            case "status": client.message(client.status()); break;
            default: throw new WrongUsageException(getUsage(sender));
        }
    }
    @Override public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
            String[] args, BlockPos position) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "on", "off", "status")
                : Collections.emptyList();
    }
}
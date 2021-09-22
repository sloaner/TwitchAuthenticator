package com.jsloane.plugin

import com.jsloane.twitch_authenticator.TwitchAuthenticator
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class CommandStart(
    private val authenticator: TwitchAuthenticator
) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        authenticator.promptAuthorize(sender)
        return true
    }
}
package com.jsloane.plugin

import com.jsloane.twitch_authenticator.TwitchAuthenticator
import org.bukkit.plugin.java.JavaPlugin

class SamplePlugin : JavaPlugin() {
    private lateinit var authenticator: TwitchAuthenticator
    override fun onEnable() {
        super.onEnable()
        saveDefaultConfig()
        authenticator = TwitchAuthenticator(this)

        getCommand("twitch_on")?.setExecutor(CommandStart(authenticator))
    }

    override fun onDisable() {
        super.onDisable()
    }
}
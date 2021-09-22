package com.jsloane.twitch_authenticator

import io.javalin.Javalin
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.ssl.SslContextFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore


class TwitchAuthenticator(
    private val plugin: JavaPlugin
) {
    //region Public API
    fun promptToAuthorize(caller: CommandSender) {
        caller.spigot().sendMessage(
            TextComponent("Click Here").apply {
                isUnderlined = true
                color = ChatColor.DARK_AQUA
                hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text("hover!"))
                clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, "$authTokenUrl&state=$urlHash")
            }
        )
        startRedirectHandlerServer()
    }

    fun clearAuthorization() {
        plugin.config.set("twitchAuth.auth_token", "")
        plugin.config.set("twitchAuth.refresh_token", "")
        plugin.saveConfig()
    }
    //endregion

    //region Redirect Handler Server
    private val webserver: Javalin = Javalin.create { config ->
        config.enforceSsl = true
        config.server {
            Server().apply {
                connectors = arrayOf(ServerConnector(this, sslContextFactory).apply { port = redirectPort })
            }
        }
    }.get("/auth_code") { ctx ->
        println(ctx.req.getParameter("code"))
        ctx.json("ok")
    }

    private val sslContextFactory: SslContextFactory
        get() = SslContextFactory.Server().apply {
            javaClass.getResourceAsStream("/keystore.jks")?.let { resource ->
                keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(resource, "Password1".toCharArray())
                }
            }
            setKeyManagerPassword("Password1")
            setKeyStorePassword("Password1")
        }
    //endregion

    //region Config Values
    private val redirectHost: String
        get() = plugin.server.ip.ifBlank {
            BufferedReader(InputStreamReader(URL("https://api.ipify.org").openStream())).readLine()
        }

    private val redirectPort: Int
        get() = plugin.config.getInt("twitchAuth.port", 8126)

    private val clientId: String
        get() = URLEncoder.encode(plugin.config.getString("twitchAuth.clientId").orEmpty(), "utf-8")

    private val clientSecret: String
        get() = URLEncoder.encode(plugin.config.getString("twitchAuth.clientSecret").orEmpty(), "utf-8")

    private val scope: String
        get() = URLEncoder.encode(
            plugin.config.getStringList("twitchAuth.scope").joinToString(separator = " "),
            "utf-8"
        )
    //endregion

    private val authTokenUrl: String
        get() = """
            |https://id.twitch.tv/oauth2/authorize
                |?client_id=$clientId
                |&redirect_uri=https://$redirectHost:$redirectPort/auth_code
                |&response_type=code
                |&scope=$scope
        """.trimMargin().replace("\n", "")

    private val urlHash: String
        get() = authTokenUrl.hashCode().toString()

    private fun startRedirectHandlerServer() {
        val cachedClassLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = plugin.javaClass.classLoader

        webserver.start()

        Thread.currentThread().contextClassLoader = cachedClassLoader
    }
}
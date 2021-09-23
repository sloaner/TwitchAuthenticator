package com.jsloane.twitch_authenticator

import com.jsloane.twitch_authenticator.model.Token
import com.jsloane.twitch_authenticator.model.UserInfo
import com.squareup.moshi.Moshi
import io.javalin.Javalin
import io.javalin.core.util.JavalinLogger
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.StatisticsHandler
import org.eclipse.jetty.util.ssl.SslContextFactory
import java.io.BufferedReader
import java.io.IOException
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
                clickEvent =
                    ClickEvent(ClickEvent.Action.OPEN_URL, "$twitchAuthorizeUrl&state=${twitchAuthorizeUrl.hashCode()}")
            }
        )
        startRedirectHandlerServer()
        pendingAuth = true
    }

    fun clearAuthorization() {
        authToken = null
        refreshToken = null
        username = null
        channelId = null
        pendingAuth = false
    }

    @JvmOverloads
    fun validateAuthorization(callback: ((validated: Boolean) -> Unit)? = null) {
        validateToken { isValid ->
            callback?.invoke(isValid)
        }
    }

    @JvmOverloads
    fun refreshAuthorization(callback: ((validated: Boolean) -> Unit)? = null) {
        refreshToken { isValid ->
            callback?.invoke(isValid)
        }
    }

    var authToken: String? = plugin.config.getString(ConfigKeys.AUTH_TOKEN.value)
        set(value) {
            field = value
            plugin.config.set(ConfigKeys.AUTH_TOKEN.value, value)
            plugin.saveConfig()
        }

    var refreshToken: String? = plugin.config.getString(ConfigKeys.REFRESH_TOKEN.value)
        set(value) {
            field = value
            plugin.config.set(ConfigKeys.REFRESH_TOKEN.value, value)
            plugin.saveConfig()
        }

    var username: String? = plugin.config.getString(ConfigKeys.USERNAME.value)
        set(value) {
            field = value
            plugin.config.set(ConfigKeys.USERNAME.value, value)
            plugin.saveConfig()
        }

    var channelId: String? = plugin.config.getString(ConfigKeys.CHANNEL_ID.value)
        set(value) {
            field = value
            plugin.config.set(ConfigKeys.CHANNEL_ID.value, value)
            plugin.saveConfig()
        }
    //endregion

    //region Config Values
    private val redirectHost: String
        get() = plugin.server.ip.ifBlank {
            BufferedReader(InputStreamReader(URL("https://api.ipify.org").openStream())).readLine()
        }

    private val redirectPort: Int
        get() = plugin.config.getInt(ConfigKeys.PORT.value, 8126)

    private val clientId: String
        get() = URLEncoder.encode(plugin.config.getString(ConfigKeys.CLIENT_ID.value).orEmpty(), "utf-8")

    private val clientSecret: String
        get() = URLEncoder.encode(plugin.config.getString(ConfigKeys.CLIENT_SECRET.value).orEmpty(), "utf-8")

    private val scope: String
        get() = URLEncoder.encode(
            plugin.config.getStringList(ConfigKeys.SCOPE.value).joinToString(separator = " "),
            "utf-8"
        )
    //endregion

    //region Private instance variables
    private val httpClient = OkHttpClient()
    private val moshi = Moshi.Builder().build()
    private val webserver: Javalin = createRedirectHandlerServer()

    private var pendingAuth = false

    private val twitchAuthorizeUrl: String
        get() = """
            |https://id.twitch.tv/oauth2/authorize
                |?client_id=$clientId
                |&redirect_uri=https://$redirectHost:$redirectPort/auth_code
                |&response_type=code
                |&scope=$scope
        """.trimMargin().replace("\n", "")

    private val twitchTokenUrl: String
        get() = """
            |https://id.twitch.tv/oauth2/token
                |?client_id=$clientId
                |&client_secret=$clientSecret
                |&redirect_uri=https://$redirectHost:$redirectPort/auth_code
                |&grant_type=authorization_code
        """.trimMargin().replace("\n", "")

    private val twitchRefreshUrl: String
        get() = """
            |https://id.twitch.tv/oauth2/token
                |?client_id=$clientId
                |&client_secret=$clientSecret
                |&refresh_token=$refreshToken
                |&grant_type=refresh_token
        """.trimMargin().replace("\n", "")

    private val twitchValidateUrl: String
        get() = "https://id.twitch.tv/oauth2/validate"

    private val sslContextFactory: SslContextFactory.Server
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

    //region Private utility functions
    private fun createRedirectHandlerServer(): Javalin {
        JavalinLogger.enabled = false
        return Javalin.create { config ->
            config.enforceSsl = true
            config.server {
                Server().apply {
                    handler = StatisticsHandler()
                    stopTimeout = 30000
                    connectors = arrayOf(ServerConnector(this, sslContextFactory).apply { port = redirectPort })
                }
            }
        }.get("/auth_code") { ctx ->
            ctx.html("<html><body><h1 style=\"text-align: center;\">Success!</h1><p>You can now close this window</p></body></html>")

            ctx.req.getParameter("code").let {
                println(it)
                handleRedirectSuccess(it)
            }
        }
    }

    private fun startRedirectHandlerServer() {
        if (webserver.jettyServer()?.started == true) return

        val cachedClassLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = plugin.javaClass.classLoader

        webserver.start()

        Thread.currentThread().contextClassLoader = cachedClassLoader
    }

    private fun handleRedirectSuccess(code: String) {
        plugin.server.scheduler.runTaskLaterAsynchronously(plugin, Runnable { webserver.stop() }, 20 * 30L)

        val request = Request.Builder()
            .url("${twitchTokenUrl}&code=${code}")
            .post(byteArrayOf().toRequestBody(null))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                println("Unable to get auth token")
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    println(body)
                    moshi.adapter(Token::class.java).fromJson(body)?.let { token ->
                        authToken = token.accessToken
                        refreshToken = token.refreshToken
                    }
                }
            }
        })
    }

    private fun validateToken(callback: (success: Boolean) -> Unit) {
        val request = Request.Builder()
            .header("Authorization", "Bearer $authToken")
            .url(twitchValidateUrl)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.invoke(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    moshi.adapter(UserInfo::class.java).fromJson(body)?.let { userInfo ->
                        username = userInfo.username
                        channelId = userInfo.channelId

                        callback.invoke(true)
                    }
                } ?: callback.invoke(false)
            }
        })
    }

    private fun refreshToken(callback: (success: Boolean) -> Unit) {
        val request = Request.Builder()
            .url(twitchRefreshUrl)
            .post(byteArrayOf().toRequestBody(null))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.invoke(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { body ->
                    moshi.adapter(Token::class.java).fromJson(body)?.let { token ->
                        authToken = token.accessToken
                        refreshToken = token.refreshToken

                        callback.invoke(true)
                    }
                } ?: callback.invoke(false)
            }
        })
    }
    //endregion

    enum class ConfigKeys(val value: String) {
        CLIENT_ID("twitchAuth.clientId"),
        CLIENT_SECRET("twitchAuth.clientSecret"),
        PORT("twitchAuth.port"),
        SCOPE("twitchAuth.scope"),

        AUTH_TOKEN("twitchAuth.auth_token"),
        REFRESH_TOKEN("twitchAuth.refresh_token"),

        USERNAME("twitchAuth.username"),
        CHANNEL_ID("twitchAuth.channel_id"),
    }
}
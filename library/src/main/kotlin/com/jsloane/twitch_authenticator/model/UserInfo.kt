package com.jsloane.twitch_authenticator.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UserInfo(
    @field:Json(name = "client_id") val clientId: String?,
    @field:Json(name = "login") val username: String?,
    @field:Json(name = "user_id") val channelId: String?,
    @field:Json(name = "expires_in") val expiresIn: Int = 3600,
)
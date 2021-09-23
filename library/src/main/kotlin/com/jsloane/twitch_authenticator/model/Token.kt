package com.jsloane.twitch_authenticator.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Token(
    @field:Json(name = "access_token") val accessToken: String?,
    @field:Json(name = "refresh_token") val refreshToken: String?,
    @field:Json(name = "expires_in") val expiresIn: Int = 3600,
    @field:Json(name = "token_type") val tokenType: String = "Bearer",
)
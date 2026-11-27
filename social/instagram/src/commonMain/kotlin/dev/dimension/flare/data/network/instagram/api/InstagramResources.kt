package dev.dimension.flare.data.network.instagram.api

import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Query
import io.ktor.client.statement.HttpResponse

internal interface InstagramApiResources {
    @GET("accounts/current_user/")
    suspend fun currentUser(
        @Query("edit") edit: Boolean = true,
    ): HttpResponse

    @GET("feed/timeline/")
    suspend fun home(
        @Query("max_id") cursor: String?,
    ): HttpResponse

    @GET("users/{id}/info/")
    suspend fun user(
        @Path("id") id: String,
    ): HttpResponse

    @GET("users/web_profile_info/")
    suspend fun profile(
        @Query("username") handle: String,
    ): HttpResponse

    @GET("feed/user/{id}/")
    suspend fun posts(
        @Path("id") id: String,
        @Query("max_id") cursor: String?,
        @Query("count") count: Int = 20,
    ): HttpResponse

    @GET("media/{id}/info/")
    suspend fun media(
        @Path("id") id: String,
    ): HttpResponse

    @GET("media/{id}/comments/")
    suspend fun comments(
        @Path("id") id: String,
        @Query("min_id") min: String?,
        @Query("max_id") max: String?,
        @Query("can_support_threading") threading: Boolean = true,
    ): HttpResponse
}

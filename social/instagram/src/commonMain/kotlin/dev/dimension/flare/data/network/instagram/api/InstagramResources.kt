package dev.dimension.flare.data.network.instagram.api

import io.ktor.client.statement.HttpResponse
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Header
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Query
import io.ktor.client.request.forms.FormDataContent

internal interface InstagramApiResources {
    @GET("users/{id}/info/")
    suspend fun user(@Path("id") id: String): HttpResponse

    @GET("users/web_profile_info/")
    suspend fun profile(@Query("username") handle: String): HttpResponse

    @GET("feed/user/{id}/")
    suspend fun posts(@Path("id") id: String, @Query("max_id") cursor: String?, @Query("count") count: Int = 20): HttpResponse

    @GET("media/{id}/info/")
    suspend fun media(@Path("id") id: String): HttpResponse

    @GET("media/{id}/comments/")
    suspend fun comments(
        @Path("id") id: String,
        @Query("min_id") min: String?,
        @Query("max_id") max: String?,
        @Query("can_support_threading") threading: Boolean = true,
    ): HttpResponse
}

internal interface InstagramWebResources {
    @GET("?variant=following")
    suspend fun bootstrap(@Header("Accept") accept: String = "text/html"): HttpResponse

    @POST("graphql/query")
    suspend fun following(
        @Header("X-FB-LSD") lsd: String,
        @Header("X-FB-Friendly-Name") name: String,
        @Header("X-Root-Field-Name") field: String,
        @Header("Referer") referer: String,
        @Body form: FormDataContent,
    ): HttpResponse
}

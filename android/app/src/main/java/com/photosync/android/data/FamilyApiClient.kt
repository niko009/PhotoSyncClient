package com.photosync.android.data

import android.os.Build
import com.photosync.android.BuildConfig
import com.photosync.android.domain.model.AccessibleAlbum
import com.photosync.android.domain.model.FamilyInfo
import com.photosync.android.domain.model.FamilyInvite
import com.photosync.android.domain.model.FamilyMember
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class FamilyApiClient(
    private val preferencesStore: PreferencesStore,
    private val identity: DeviceIdentity,
) {
    fun getFamily(): FamilyInfo = request("/api/family", "GET").toFamilyInfo()

    fun getAccessibleAlbums(): List<AccessibleAlbum> {
        val array = request("/api/albums/accessible", "GET").getJSONArray("albums")
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(AccessibleAlbum(
                    albumId = item.getInt("album_id"),
                    name = item.getString("name"),
                    permission = item.getString("permission"),
                    sharingMode = item.getString("sharing_mode"),
                    ownedByMe = item.getBoolean("owned_by_me"),
                ))
            }
        }
    }

    fun updateAlbumSharing(albumId: Int, mode: String, familyPermission: String = "View", selectedPeople: Map<Int, String>? = null) {
        val selected = selectedPeople?.let { map ->
            JSONObject().apply { map.forEach { (userId, permission) -> put(userId.toString(), permission) } }
        }
        request(
            "/api/albums/$albumId/sharing",
            "PUT",
            JSONObject()
                .put("mode", mode)
                .put("family_permission", familyPermission)
                .put("selected_people", selected ?: JSONObject.NULL),
        )
    }

    fun createInvite(email: String): FamilyInvite = request(
        "/api/family/invites",
        "POST",
        JSONObject().put("email", email.trim()),
    ).toFamilyInvite()

    fun revokeInvite(inviteId: Int) {
        request("/api/family/invites/$inviteId", "DELETE")
    }

    fun removeMember(userId: Int) {
        request("/api/family/members/$userId", "DELETE")
    }

    fun acceptInvite(token: String, idToken: String) {
        require(token.matches(Regex("[A-Za-z0-9_-]{20,256}"))) { "Invalid invitation link" }
        ensureDeviceRegistered()
        val googlePayload = JSONObject().put("id_token", idToken)
        request("/api/auth/google/sign-in", "POST", googlePayload)
        request("/api/family/join/$token", "POST", googlePayload)
    }

    private fun ensureDeviceRegistered() {
        val origin = ServerAddress.normalize(preferencesStore.getServerUrl())
        val uuid = identity.credentials(origin).first
        request(
            "/api/devices/register",
            "POST",
            JSONObject()
                .put("device_uuid", uuid)
                .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                .put("app_version", BuildConfig.VERSION_NAME),
        )
    }

    private fun request(path: String, method: String, payload: JSONObject? = null): JSONObject {
        val origin = ServerAddress.normalize(preferencesStore.getServerUrl())
        val connection = URL(origin + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        val (uuid, secret) = identity.credentials(origin)
        connection.setRequestProperty("X-PhotoSync-Device", uuid)
        connection.setRequestProperty("Authorization", "Bearer $secret")
        if (payload != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }
        }

        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw FamilyApiException(status, body)
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}

class FamilyApiException(val statusCode: Int, val responseBody: String) :
    IllegalStateException("HTTP $statusCode: $responseBody") {
    fun userMessage(): String = runCatching {
        val json = JSONObject(responseBody)
        when (json.optString("error")) {
            "wrong_google_account" -> "Use the invited Google account (${json.optString("expected_email")})."
            "invite_expired" -> "This invitation has expired. Ask the family owner for a new link."
            "invite_revoked" -> "This invitation was revoked."
            "invite_already_used" -> "This invitation has already been used."
            "invite_already_pending" -> "An invitation for this email is already pending."
            "already_member" -> "This person is already in the family."
            "already_in_another_family" -> "This account already belongs to another family."
            "invalid_email" -> "Enter a valid Google email address."
            else -> "Family request failed (HTTP $statusCode)."
        }
    }.getOrDefault("Family request failed (HTTP $statusCode).")
}

private fun JSONObject.toFamilyInfo(): FamilyInfo {
    val memberArray = getJSONArray("members")
    val members = buildList {
        for (i in 0 until memberArray.length()) {
            val item = memberArray.getJSONObject(i)
            add(FamilyMember(
                userId = item.getInt("user_id"),
                email = item.getString("email"),
                displayName = item.optString("display_name").takeIf { it.isNotBlank() && it != "null" },
                role = item.getString("role"),
                isCurrentUser = item.getBoolean("is_current_user"),
            ))
        }
    }
    val inviteArray = getJSONArray("pending_invites")
    val invites = buildList {
        for (i in 0 until inviteArray.length()) add(inviteArray.getJSONObject(i).toFamilyInvite())
    }
    return FamilyInfo(getInt("id"), getString("name"), getString("role"), members, invites)
}

private fun JSONObject.toFamilyInvite() = FamilyInvite(
    id = getInt("id"),
    expectedEmail = getString("expected_email"),
    expiresAt = getString("expires_at"),
    status = getString("status"),
    inviteUrl = optString("invite_url").takeIf { it.isNotBlank() && it != "null" },
)

package com.example.musicfy.ritme.server

import java.security.SecureRandom
import java.util.Base64

interface IdGen {
    fun memberId(): String

    fun entryId(): String

    /** 128-bit, unguessable: it is the only credential for reclaiming a seat. */
    fun resumeToken(): String

    fun roomCode(): String
}

class SecureIdGen(private val random: SecureRandom = SecureRandom()) : IdGen {
    override fun memberId(): String = token(9)

    override fun entryId(): String = token(9)

    override fun resumeToken(): String = token(16)

    override fun roomCode(): String = buildString {
        repeat(Limits.ROOM_CODE_LENGTH) {
            append(Limits.ROOM_CODE_ALPHABET[random.nextInt(Limits.ROOM_CODE_ALPHABET.length)])
        }
    }

    private fun token(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))
}

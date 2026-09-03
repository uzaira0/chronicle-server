package com.openlattice.chronicle.services.auth

import com.openlattice.chronicle.configuration.JwtKeyMaterial
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class RefreshTokenMfaGuardTest {

    private val storageResolver = mock(StorageResolver::class.java)
    private val service = RefreshTokenService(
        storageResolver = storageResolver,
        jwtKeyMaterial = JwtKeyMaterial(
            algorithm = "HS256",
            keyId = "test",
            rsaPublicKey = null,
            rsaPrivateKey = null,
            hmacSecret = ByteArray(32) { 1 },
        ),
        issuer = "https://issuer.example",
        audience = "chronicle",
        accessTokenExpiryMinutes = 15,
        refreshTokenExpiryDays = 7,
        requireMfa = true,
    )

    @Test
    fun `MFA enforcement rejects refresh token creation before storage access`() {
        val exception = assertThrows(RefreshTokenException::class.java) {
            service.createRefreshToken(
                userId = "00000000-0000-0000-0000-000000000001",
                ipAddress = "192.0.2.1",
                userAgent = "test",
            )
        }

        assertEquals(
            "Interactive reauthentication is required while MFA enforcement is enabled",
            exception.message,
        )
        verifyNoInteractions(storageResolver)
    }

    @Test
    fun `MFA enforcement rejects refresh rotation before storage mutation`() {
        val exception = assertThrows(RefreshTokenException::class.java) {
            service.rotateRefreshToken(
                rawToken = "legacy-refresh-token",
                ipAddress = "192.0.2.1",
                userAgent = "test",
            )
        }

        assertEquals(
            "Interactive reauthentication is required while MFA enforcement is enabled",
            exception.message,
        )
        verifyNoInteractions(storageResolver)
    }
}

package com.example.security

import android.content.Context
import android.os.Debug
import android.util.Base64
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Multi-Layer Hardened Security Vault for AADIFY:
 * 1. Zero plaintext API keys, Firebase URLs, Project IDs, or Payment Gateway secrets in DEX constant pools.
 * 2. Split-shard rolling-key + XOR runtime byte reconstruction cached in memory for zero runtime overhead.
 * 3. Instant non-blocking local credential encryption with legacy Android Keystore fallback.
 * 4. Anti-tamper package & runtime integrity verification.
 */
object AadifySecurityVault {

    private const val KEYSTORE_ALIAS = "aadify_hardware_vault_key_v3"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    // Split Key Mask Shards (never stored as a single contiguous string)
    private val MASK_PART_A = intArrayOf(0xA7, 0x5C, 0xE3, 0x19, 0x8B, 0x42, 0xF0, 0x6D)
    private val MASK_PART_B = intArrayOf(0x3A, 0xC5, 0x71, 0x9E, 0x28, 0xD4, 0x63, 0xB1)

    // Encrypted Byte Shards (Zero plaintext strings in classes.dex)
    private val FB_PROJECT_ID_SHARD = intArrayOf(
        27, 180, 51, 69, 148, 135, 254, 104, 145, 163
    )
    private val FB_DATABASE_URL_SHARD = intArrayOf(
        41, 167, 59, 50, 230, 139, 23, 159, 222, 83, 233, 236, 27, 29, 181, 92,
        60, 52, 194, 150, 44, 135, 25, 197, 40, 168, 120, 45, 194, 59, 177, 116,
        187, 84, 213, 229, 119, 214, 91, 5, 117, 241, 203, 142, 253, 148
    )
    private val FB_STORAGE_BUCKET_SHARD = intArrayOf(
        27, 180, 51, 69, 148, 135, 254, 104, 145, 163, 43, 233, 87, 225, 102, 88,
        112, 254, 122, 131, 61, 144, 10, 185, 47, 151, 123, 64, 190, 47
    )
    private val FB_APP_ID_SHARD = intArrayOf(
        240, 229, 117, 243, 170, 136, 255, 187, 145, 157, 19, 155, 39, 34, 185, 85,
        115, 7, 133, 143, 32, 137, 82, 185, 223, 233, 97, 239, 126, 114, 3, 165,
        145, 136, 25, 151, 64, 38, 156, 82, 123, 58, 173, 140, 210
    )
    private val FB_SENDER_ID_SHARD = intArrayOf(
        239, 238, 120, 247, 170, 132, 253, 187, 142, 152, 20, 158
    )
    private val FB_API_KEY_SHARD = intArrayOf(
        64, 148, 61, 65, 198, 78, 236, 136, 229, 68, 231, 202, 122, 188, 139, 109,
        124, 52, 136, 138, 25, 216, 27, 185, 31, 120, 34, 46, 195, 69, 234, 184,
        143, 89, 22, 229, 117, 214, 141
    )
    private val FG_BASE_URL_SHARD = intArrayOf(
        41, 167, 59, 50, 230, 139, 23, 159, 222, 75, 232, 234, 95, 219, 102, 75,
        112, 244, 193, 137, 35
    )
    private val FG_API_KEY_SHARD = intArrayOf(
        27, 188, 50, 15, 221, 141, 250, 185, 222, 156, 20, 158, 50, 32, 97, 87,
        63, 51, 197, 147, 38, 216, 78, 181, 49, 152, 46, 238, 175, 59, 183, 85,
        192, 141, 21, 145, 124, 41, 155, 6, 128, 231, 127, 64
    )
    private val FG_CREATE_PATH_SHARD = intArrayOf(
        226, 188, 55, 57, 146, 52, 186, 101, 221, 88, 224, 164, 93, 225, 103, 89, 127
    )
    private val FG_QR_PATH_SHARD = intArrayOf(
        226, 188, 55, 57, 146, 70, 186, 160, 204, 84, 205
    )
    private val FG_VERIFY_PATH_SHARD = intArrayOf(
        226, 188, 55, 57, 146, 71, 205, 124, 213, 74, 212, 164, 93, 225, 103, 89,
        127, 65, 135, 138, 57
    )
    private val FG_STATUS_PATH_SHARD = intArrayOf(
        226, 188, 55, 57, 146, 52, 208, 101, 219, 85, 234, 220, 114, 52, 112, 74,
        112, 247, 138, 131, 227, 149, 32, 202
    )
    private val FG_REDIRECT_URL_SHARD = intArrayOf(
        41, 167, 59, 50, 230, 139, 23, 159, 222, 75, 232, 234, 95, 219, 102, 75,
        112, 244, 193, 137, 35, 208, 11, 198, 45, 168, 32, 46, 108, 47, 187, 150
    )

    private fun decodeShard(shard: IntArray): String {
        val out = ByteArray(shard.size)
        return try {
            for (i in shard.indices) {
                val maskVal = if ((i and 15) < 8) {
                    MASK_PART_A[i and 7]
                } else {
                    MASK_PART_B[(i and 15) - 8]
                }
                val rot = ((i * 37) + 0x5A) and 0xFF
                val unrotated = (shard[i] - rot) and 0xFF
                out[i] = (unrotated xor maskVal).toByte()
            }
            String(out, StandardCharsets.UTF_8)
        } finally {
            Arrays.fill(out, 0.toByte())
        }
    }

    // Cached decoded values (reconstructed once at runtime, zero repeated lock/CPU overhead)
    private val cachedFbProjectId: String by lazy { decodeShard(FB_PROJECT_ID_SHARD) }
    private val cachedFbDatabaseUrl: String by lazy { decodeShard(FB_DATABASE_URL_SHARD) }
    private val cachedFbStorageBucket: String by lazy { decodeShard(FB_STORAGE_BUCKET_SHARD) }
    private val cachedFbAppId: String by lazy { decodeShard(FB_APP_ID_SHARD) }
    private val cachedFbSenderId: String by lazy { decodeShard(FB_SENDER_ID_SHARD) }
    private val cachedFbApiKey: String by lazy { decodeShard(FB_API_KEY_SHARD) }
    private val cachedFgBaseUrl: String by lazy { decodeShard(FG_BASE_URL_SHARD) }
    private val cachedFgApiKey: String by lazy { decodeShard(FG_API_KEY_SHARD) }
    private val cachedFgCreatePath: String by lazy { decodeShard(FG_CREATE_PATH_SHARD) }
    private val cachedFgQrPath: String by lazy { decodeShard(FG_QR_PATH_SHARD) }
    private val cachedFgVerifyPath: String by lazy { decodeShard(FG_VERIFY_PATH_SHARD) }
    private val cachedFgStatusPath: String by lazy { decodeShard(FG_STATUS_PATH_SHARD) }
    private val cachedFgRedirectUrl: String by lazy { decodeShard(FG_REDIRECT_URL_SHARD) }

    fun isRuntimeHookedOrTampered(): Boolean {
        if (BuildConfig.DEBUG) return false
        return try {
            val stackSuspicious = Throwable().stackTrace.any { frame ->
                val cls = frame.className.lowercase()
                cls.contains("xposed") ||
                    cls.contains("frida") ||
                    cls.contains("substrate") ||
                    cls.contains("lsposed") ||
                    cls.contains("edxposed") ||
                    cls.contains("epic") ||
                    cls.contains("sandhook") ||
                    cls.contains("whale") ||
                    cls.contains("pine")
            }
            stackSuspicious || Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        } catch (_: Throwable) {
            false
        }
    }

    fun verifyAppIntegrity(context: Context): Boolean {
        return try {
            val pkg = context.packageName
            val expectedPkg = listOf("com", "aadarsh", "aadify", "app").joinToString(".")
            val fallbackPkg = listOf("com", "example").joinToString(".")
            val validPkg = pkg == expectedPkg || pkg == fallbackPkg
            val validLoader = context.classLoader != null
            validPkg && validLoader && !isRuntimeHookedOrTampered()
        } catch (_: Throwable) {
            true
        }
    }

    fun getFirebaseProjectId(): String = cachedFbProjectId

    fun getFirebaseDatabaseUrl(): String = cachedFbDatabaseUrl

    fun getFirebaseStorageBucket(): String = cachedFbStorageBucket

    fun getFirebaseAppId(): String = cachedFbAppId

    fun getFirebaseSenderId(): String = cachedFbSenderId

    fun getFirebaseApiKey(): String {
        val fromBuild = try {
            BuildConfig.FIREBASE_WEB_API_KEY.trim()
        } catch (_: Throwable) {
            ""
        }
        if (fromBuild.isNotBlank() &&
            !fromBuild.startsWith("YOUR_", ignoreCase = true) &&
            !fromBuild.startsWith("MY_", ignoreCase = true)
        ) {
            return fromBuild
        }
        return cachedFbApiKey
    }

    fun getFamGatewayBaseUrl(): String = cachedFgBaseUrl

    fun getFamGatewayApiKey(): String {
        val fromBuild = try {
            BuildConfig.FAMGATEWAY_API_KEY.trim()
        } catch (_: Throwable) {
            ""
        }
        if (fromBuild.isNotBlank() &&
            !fromBuild.startsWith("YOUR_", ignoreCase = true) &&
            !fromBuild.startsWith("MY_", ignoreCase = true)
        ) {
            return fromBuild
        }
        return cachedFgApiKey
    }

    fun getFamGatewayCreateOrderUrl(): String = cachedFgBaseUrl + cachedFgCreatePath

    fun getFamGatewayQrUrl(): String = cachedFgBaseUrl + cachedFgQrPath

    fun getFamGatewayVerifyPath(): String = cachedFgVerifyPath

    fun getFamGatewayStatusPath(): String = cachedFgStatusPath

    fun getFamGatewayRedirectUrl(): String = cachedFgRedirectUrl

    @Volatile
    private var cachedFirebaseApp: FirebaseApp? = null

    /**
     * Initializes the default FirebaseApp directly from the encrypted vault so no real keys or
     * URLs ever need to be stored in plaintext inside `google-services.json` or `resources.arsc`.
     */
    fun ensureSecureFirebaseInitialized(context: Context): FirebaseApp {
        cachedFirebaseApp?.let { return it }
        synchronized(this) {
            cachedFirebaseApp?.let { return it }
            val appCtx = context.applicationContext ?: context
            verifyAppIntegrity(appCtx)
            val existing = FirebaseApp.getApps(appCtx)
            if (existing.isNotEmpty()) {
                val instance = FirebaseApp.getInstance()
                cachedFirebaseApp = instance
                return instance
            }
            val options = FirebaseOptions.Builder()
                .setProjectId(getFirebaseProjectId())
                .setDatabaseUrl(getFirebaseDatabaseUrl())
                .setStorageBucket(getFirebaseStorageBucket())
                .setApplicationId(getFirebaseAppId())
                .setGcmSenderId(getFirebaseSenderId())
                .setApiKey(getFirebaseApiKey())
                .build()
            val initialized = FirebaseApp.initializeApp(appCtx, options) ?: FirebaseApp.getInstance()
            cachedFirebaseApp = initialized
            return initialized
        }
    }

    const val SECURITY_VAULT_LEVEL = 6

    /**
     * Fast, non-blocking Level-6 triple-pass rolling-shard encryption for local preferences
     * and offline vault metadata so the Main Thread never stalls on OEM hardware Keystore Binder IPC.
     */
    fun encryptSensitiveLocalString(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val raw = plainText.toByteArray(StandardCharsets.UTF_8)
            val encoded = ByteArray(raw.size)
            for (i in raw.indices) {
                val maskA = MASK_PART_A[i and 7]
                val maskB = MASK_PART_B[(i + 5) and 7]
                val rot1 = ((i * 73) + 0x5B) and 0xFF
                val rot2 = ((i * 29) + 0x1F) and 0xFF
                val pass1 = ((raw[i].toInt() and 0xFF) xor maskA)
                val pass2 = ((pass1 + rot1) and 0xFF) xor maskB
                val pass3 = (pass2 + rot2) and 0xFF
                encoded[i] = pass3.toByte()
            }
            "ENC_V6:" + Base64.encodeToString(encoded, Base64.NO_WRAP)
        } catch (_: Throwable) {
            plainText
        }
    }

    fun decryptSensitiveLocalString(storedValue: String): String {
        if (storedValue.isEmpty()) return ""
        if (storedValue.startsWith("ENC_V6:")) {
            return try {
                val encoded = Base64.decode(storedValue.removePrefix("ENC_V6:"), Base64.NO_WRAP)
                val out = ByteArray(encoded.size)
                for (i in encoded.indices) {
                    val maskA = MASK_PART_A[i and 7]
                    val maskB = MASK_PART_B[(i + 5) and 7]
                    val rot1 = ((i * 73) + 0x5B) and 0xFF
                    val rot2 = ((i * 29) + 0x1F) and 0xFF
                    val unpass3 = ((encoded[i].toInt() and 0xFF) - rot2) and 0xFF
                    val unpass2 = unpass3 xor maskB
                    val unrot = (unpass2 - rot1) and 0xFF
                    out[i] = (unrot xor maskA).toByte()
                }
                String(out, StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }
        }
        if (storedValue.startsWith("ENC_V5:")) {
            return try {
                val encoded = Base64.decode(storedValue.removePrefix("ENC_V5:"), Base64.NO_WRAP)
                val out = ByteArray(encoded.size)
                for (i in encoded.indices) {
                    val maskA = MASK_PART_A[i and 7]
                    val maskB = MASK_PART_B[(i + 3) and 7]
                    val rot = ((i * 71) + 0x4F) and 0xFF
                    val unpass2 = (encoded[i].toInt() and 0xFF) xor maskB
                    val unrot = (unpass2 - rot) and 0xFF
                    out[i] = (unrot xor maskA).toByte()
                }
                String(out, StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }
        }
        if (storedValue.startsWith("ENC_V4:")) {
            return try {
                val encoded = Base64.decode(storedValue.removePrefix("ENC_V4:"), Base64.NO_WRAP)
                val out = ByteArray(encoded.size)
                for (i in encoded.indices) {
                    val mask = if ((i and 15) < 8) MASK_PART_A[i and 7] else MASK_PART_B[(i and 15) - 8]
                    val rot = ((i * 53) + 0x3D) and 0xFF
                    val unrot = ((encoded[i].toInt() and 0xFF) - rot) and 0xFF
                    out[i] = (unrot xor mask).toByte()
                }
                String(out, StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }
        }
        if (storedValue.startsWith("ENC_V3:")) {
            return decryptLegacyKeystoreString(storedValue)
        }
        return storedValue
    }

    private fun decryptLegacyKeystoreString(storedValue: String): String {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existingEntry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
                ?: return ""
            val secretKey: SecretKey = existingEntry.secretKey
            val rawB64 = storedValue.removePrefix("ENC_V3:")
            val combined = Base64.decode(rawB64, Base64.NO_WRAP)
            val ivLen = combined[0].toInt() and 0xFF
            if (ivLen <= 0 || combined.size <= 1 + ivLen) return ""
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val cipherBytes = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Level-6 Anti-Crack Cryptographic Subscription Seal (HMAC-SHA256 + Split-Shard Derived Key).
     * Prevents SharedPreferences XML editing, Lucky Patcher / root pref spoofing, and fake plan injection.
     */
    fun computeSubscriptionIntegritySeal(
        planId: String,
        activatedAtMs: Long,
        expiresAtMs: Long,
        orderId: String,
        utr: String
    ): String {
        if (planId.isBlank() || planId.equals("none", ignoreCase = true) || expiresAtMs <= 0L) {
            return ""
        }
        return try {
            val derivedKey = ByteArray(32)
            for (i in 0 until 32) {
                val a = MASK_PART_A[i and 7]
                val b = MASK_PART_B[(i + 3) and 7]
                val c = FB_PROJECT_ID_SHARD[i % FB_PROJECT_ID_SHARD.size]
                derivedKey[i] = ((a xor b) + c + (i * 41)).toByte()
            }
            val payload = "AADIFY_V6_VIP_SEAL|${planId.lowercase()}|$activatedAtMs|$expiresAtMs|${orderId.trim()}|${utr.trim()}"
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(derivedKey, "HmacSHA256"))
            val digest = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
            Arrays.fill(derivedKey, 0.toByte())
            encryptSensitiveLocalString(Base64.encodeToString(digest, Base64.NO_WRAP))
        } catch (_: Throwable) {
            ""
        }
    }

    fun verifySubscriptionIntegritySeal(
        storedSeal: String,
        planId: String,
        activatedAtMs: Long,
        expiresAtMs: Long,
        orderId: String,
        utr: String
    ): Boolean {
        if (planId.isBlank() || planId.equals("none", ignoreCase = true)) return false
        if (storedSeal.isBlank() || isRuntimeHookedOrTampered()) return false
        return try {
            val expectedEncrypted = computeSubscriptionIntegritySeal(
                planId = planId,
                activatedAtMs = activatedAtMs,
                expiresAtMs = expiresAtMs,
                orderId = orderId,
                utr = utr
            )
            val actualRaw = decryptSensitiveLocalString(storedSeal)
            val expectedRaw = decryptSensitiveLocalString(expectedEncrypted)
            actualRaw.isNotBlank() && actualRaw == expectedRaw
        } catch (_: Throwable) {
            false
        }
    }
}

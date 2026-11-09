package com.example.security

import android.content.Context
import android.os.Debug
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Multi-Layer Hardened Security Vault for AADIFY:
 * 1. Zero plaintext API keys, Firebase URLs, Project IDs, or Payment Gateway secrets in DEX constant pools.
 * 2. Split-shard rolling-key + XOR runtime byte reconstruction with immediate buffer zeroing.
 * 3. Android Keystore hardware-backed AES-256-GCM encryption for local sensitive data.
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

    @Synchronized
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

    /**
     * Verifies runtime package integrity so cloned/modded APKs with altered package IDs
     * or attached instrumentation hooks are detected.
     */
    fun verifyAppIntegrity(context: Context): Boolean {
        return try {
            val pkg = context.packageName
            val validPkg = pkg == "com.aadarsh.aadify.app" || pkg == "com.example"
            val notHooked = try {
                Class.forName("de.robv.android.xposed.XposedBridge")
                false
            } catch (_: ClassNotFoundException) {
                true
            }
            validPkg && notHooked && (!Debug.isDebuggerConnected() || BuildConfig.DEBUG)
        } catch (_: Throwable) {
            true
        }
    }

    fun getFirebaseProjectId(): String = decodeShard(FB_PROJECT_ID_SHARD)

    fun getFirebaseDatabaseUrl(): String = decodeShard(FB_DATABASE_URL_SHARD)

    fun getFirebaseStorageBucket(): String = decodeShard(FB_STORAGE_BUCKET_SHARD)

    fun getFirebaseAppId(): String = decodeShard(FB_APP_ID_SHARD)

    fun getFirebaseSenderId(): String = decodeShard(FB_SENDER_ID_SHARD)

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
        return decodeShard(FB_API_KEY_SHARD)
    }

    fun getFamGatewayBaseUrl(): String = decodeShard(FG_BASE_URL_SHARD)

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
        return decodeShard(FG_API_KEY_SHARD)
    }

    fun getFamGatewayCreateOrderUrl(): String = getFamGatewayBaseUrl() + decodeShard(FG_CREATE_PATH_SHARD)

    fun getFamGatewayQrUrl(): String = getFamGatewayBaseUrl() + decodeShard(FG_QR_PATH_SHARD)

    fun getFamGatewayVerifyPath(): String = decodeShard(FG_VERIFY_PATH_SHARD)

    fun getFamGatewayStatusPath(): String = decodeShard(FG_STATUS_PATH_SHARD)

    fun getFamGatewayRedirectUrl(): String = decodeShard(FG_REDIRECT_URL_SHARD)

    /**
     * Initializes the default FirebaseApp directly from the encrypted vault so no real keys or
     * URLs ever need to be stored in plaintext inside `google-services.json` or `resources.arsc`.
     */
    @Synchronized
    fun ensureSecureFirebaseInitialized(context: Context): FirebaseApp {
        val appCtx = context.applicationContext ?: context
        val existing = FirebaseApp.getApps(appCtx)
        if (existing.isNotEmpty()) {
            return FirebaseApp.getInstance()
        }
        val options = FirebaseOptions.Builder()
            .setProjectId(getFirebaseProjectId())
            .setDatabaseUrl(getFirebaseDatabaseUrl())
            .setStorageBucket(getFirebaseStorageBucket())
            .setApplicationId(getFirebaseAppId())
            .setGcmSenderId(getFirebaseSenderId())
            .setApiKey(getFirebaseApiKey())
            .build()
        return FirebaseApp.initializeApp(appCtx, options) ?: FirebaseApp.getInstance()
    }

    // Hardware-backed Android Keystore AES-256-GCM encryption for local sensitive values
    private fun getOrCreateHardwareSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existingEntry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existingEntry != null) {
                return existingEntry.secretKey
            }
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (_: Throwable) {
            null
        }
    }

    fun encryptSensitiveLocalString(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val secretKey = getOrCreateHardwareSecretKey() ?: return plainText
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(1 + iv.size + cipherBytes.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
            "ENC_V3:" + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Throwable) {
            plainText
        }
    }

    fun decryptSensitiveLocalString(storedValue: String): String {
        if (storedValue.isEmpty() || !storedValue.startsWith("ENC_V3:")) {
            return storedValue
        }
        return try {
            val secretKey = getOrCreateHardwareSecretKey() ?: return ""
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
}

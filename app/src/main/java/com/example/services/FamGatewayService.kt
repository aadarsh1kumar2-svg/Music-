package com.example.services

import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.models.SubscriptionPlanType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class FamGatewayOrderSession(
    val orderId: String,
    val planType: SubscriptionPlanType,
    val amountInr: Int,
    val qrUrl: String,
    val checkoutUrl: String,
    val upiId: String,
    val upiIntent: String,
    val createdAtIst: String = "",
    val expiresAtIst: String = ""
)

data class FamOrderStatusResult(
    val status: String,
    val orderId: String,
    val amount: String = "",
    val transactionId: String = "",
    val utr: String = "",
    val senderName: String = ""
) {
    val isPaid: Boolean
        get() = status.equals("success", ignoreCase = true) ||
            status.equals("paid", ignoreCase = true) ||
            status.equals("completed", ignoreCase = true)

    val isExpired: Boolean
        get() = status.equals("expired", ignoreCase = true) ||
            status.equals("failed", ignoreCase = true)

    val isSuccess: Boolean
        get() = isPaid

    val isFailed: Boolean
        get() = isExpired
}

/**
 * Official FamGateway v2.0 Production UPI Payment Service
 * Base URL: https://famgateway.in
 *
 * - Creates Dynamic UPI QR & UPI Intent Orders (`POST /api/create-order` & `/api/qr.php`)
 * - Polls Live Payment Status (`GET /api/checkout-status.php?order_id=...`)
 * - Performs Authoritative Verification (`GET /api/verify-order.php`)
 * - Supports Timing-Safe HMAC-SHA256 Signature Verification
 */
class FamGatewayService {

    companion object {
        private const val TAG = "FamGatewayService"
        const val BASE_URL = "https://famgateway.in"

        // Obfuscated fallback key if BuildConfig.FAMGATEWAY_API_KEY is not injected via .env yet
        private const val ENCODED_DEFAULT_KEY =
            "ZmFtX2Q4MjFmMDkzNDFiYzI4MmNjNzZlZWRjM2FkZDFhMjIxZTQzZGRlYjE="

        fun resolveApiKey(): String {
            val fromBuildConfig = try {
                BuildConfig.FAMGATEWAY_API_KEY.trim()
            } catch (_: Throwable) {
                ""
            }
            if (fromBuildConfig.isNotBlank() &&
                !fromBuildConfig.startsWith("YOUR_", ignoreCase = true) &&
                !fromBuildConfig.startsWith("MY_", ignoreCase = true)
            ) {
                return fromBuildConfig
            }
            return try {
                String(Base64.decode(ENCODED_DEFAULT_KEY, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            } catch (_: Throwable) {
                ""
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Creates a live FamGateway UPI order for the given subscription plan and amount.
     */
    suspend fun createOrder(
        planType: SubscriptionPlanType,
        amountInr: Int,
        customerName: String,
        customerId: String
    ): Result<FamGatewayOrderSession> = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("FamGateway API Key is not configured."))
        }

        val cleanAmount = kotlin.math.max(1, amountInr)
        val cleanCustomer = customerName.trim().ifBlank { "AADIFY User" }

        val requestPayload = JSONObject().apply {
            put("api_key", apiKey)
            put("amount", cleanAmount)
            put("customer_name", cleanCustomer)
            put("customer_id", customerId)
            put("redirect_url", "https://famgateway.in/status.php")
        }

        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val endpoints = listOf(
            "$BASE_URL/api/create-order",
            "$BASE_URL/api/qr.php"
        )

        var lastError: Exception? = null
        for (endpoint in endpoints) {
            try {
                val body = requestPayload.toString().toRequestBody(jsonMediaType)
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Content-Type", "application/json")
                    .header("X-Api-Key", apiKey)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .post(body)
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val rawBody = resp.body?.string()?.trim().orEmpty()
                    if (resp.isSuccessful && rawBody.startsWith("{")) {
                        val root = JSONObject(rawBody)
                        val status = root.optString("status", "")
                        val dataObj = root.optJSONObject("data") ?: root
                        val orderId = dataObj.optString("order_id", "").trim()
                        if (status.equals("success", ignoreCase = true) || orderId.isNotBlank()) {
                            val resolvedOrderId = orderId.ifBlank { "fg_${System.currentTimeMillis()}" }
                            val qrUrl = dataObj.optString("qr_url")
                                .ifBlank { "$BASE_URL/api/qr-image.php?order_id=$resolvedOrderId" }
                            val checkoutUrl = dataObj.optString("checkout_url")
                                .ifBlank { "$BASE_URL/pay.php?order_id=$resolvedOrderId" }
                            val upiId = dataObj.optString("upi_id", "")
                            val amtStr = dataObj.optString("amount", String.format(Locale.US, "%d.00", cleanAmount))
                            val payableStr = dataObj.optString("payable_amount", amtStr)
                            val upiIntent = dataObj.optString("upi_intent").ifBlank {
                                if (upiId.isNotBlank()) {
                                    "upi://pay?pa=$upiId&pn=AADIFY&tr=$resolvedOrderId&tn=$resolvedOrderId&am=$payableStr&cu=INR"
                                } else {
                                    checkoutUrl
                                }
                            }
                            return@withContext Result.success(
                                FamGatewayOrderSession(
                                    orderId = resolvedOrderId,
                                    planType = planType,
                                    amountInr = cleanAmount,
                                    qrUrl = qrUrl,
                                    checkoutUrl = checkoutUrl,
                                    upiId = upiId,
                                    upiIntent = upiIntent,
                                    createdAtIst = dataObj.optString("created_at_ist", ""),
                                    expiresAtIst = dataObj.optString("expires_at_ist", "")
                                )
                            )
                        } else {
                            val errMsg = root.optString("message")
                                .ifBlank { root.optString("error", "Failed to create payment order") }
                            lastError = Exception(errMsg)
                        }
                    } else {
                        lastError = Exception("HTTP ${resp.code}: ${rawBody.take(140)}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Order creation attempt failed on $endpoint: ${e.message}")
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Unable to connect to FamGateway."))
    }

    /**
     * Checks live order status via BOTH FamGateway's client-side polling endpoint (`checkout-status.php`)
     * and authoritative verification endpoint (`verify-order.php`) with cache-busting headers so real
     * payment confirmation is detected as fast as the web checkout.
     */
    suspend fun checkOrderStatus(orderId: String): Result<FamOrderStatusResult> = withContext(Dispatchers.IO) {
        val cleanOrderId = orderId.trim()
        if (cleanOrderId.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Order ID is empty"))
        }

        try {
            // Query verify-order.php first (authoritative real-time DB status), then checkout-status.php
            val verifiedFirst = verifyOrderAuthoritativeInternal(cleanOrderId)
            if (verifiedFirst != null && (verifiedFirst.isPaid || verifiedFirst.isExpired)) {
                return@withContext Result.success(verifiedFirst)
            }

            val ts = System.currentTimeMillis()
            val pollUrl = "$BASE_URL/api/checkout-status.php?order_id=$cleanOrderId&_t=$ts"
            val pollReq = Request.Builder()
                .url(pollUrl)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .get()
                .build()

            httpClient.newCall(pollReq).execute().use { resp ->
                val raw = resp.body?.string()?.trim().orEmpty()
                if (resp.isSuccessful && raw.startsWith("{")) {
                    val parsed = parseStatusJson(raw, cleanOrderId)
                    if (parsed.isPaid) {
                        val verified = verifyOrderAuthoritativeInternal(cleanOrderId)
                        return@withContext Result.success(
                            if (verified != null && verified.isPaid) verified else parsed
                        )
                    }
                    return@withContext Result.success(parsed)
                }
            }

            if (verifiedFirst != null) {
                return@withContext Result.success(verifiedFirst)
            }
            Result.failure(Exception("Could not read payment status"))
        } catch (e: Exception) {
            val fallback = verifyOrderAuthoritativeInternal(cleanOrderId)
            if (fallback != null) {
                Result.success(fallback)
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun verifyOrderAuthoritative(orderId: String): Result<FamOrderStatusResult> = withContext(Dispatchers.IO) {
        checkOrderStatus(orderId.trim())
    }

    private fun verifyOrderAuthoritativeInternal(orderId: String): FamOrderStatusResult? {
        val apiKey = resolveApiKey()
        if (apiKey.isBlank()) return null
        return try {
            val ts = System.currentTimeMillis()
            val verifyUrl = "$BASE_URL/api/verify-order.php?api_key=$apiKey&order_id=$orderId&_t=$ts"
            val req = Request.Builder()
                .url(verifyUrl)
                .header("X-Api-Key", apiKey)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()?.trim().orEmpty()
                if (!resp.isSuccessful || !raw.startsWith("{")) return null
                parseStatusJson(raw, orderId)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStatusJson(raw: String, fallbackOrderId: String): FamOrderStatusResult {
        val obj = JSONObject(raw)
        val dataObj = obj.optJSONObject("data") ?: obj
        val rawStatus = dataObj.optString("payment_status")
            .ifBlank { dataObj.optString("order_status") }
            .ifBlank { dataObj.optString("status") }
            .ifBlank { obj.optString("payment_status") }
            .ifBlank { obj.optString("status", "pending") }
            .trim()
            .lowercase(Locale.US)

        // Note: In some responses, root `status` is "success" (meaning HTTP call succeeded) while `data.status` is "pending".
        // Make sure we check `dataObj`'s status first when `data` object exists!
        val nestedData = obj.optJSONObject("data")
        val resolvedStatus = if (nestedData != null) {
            nestedData.optString("payment_status")
                .ifBlank { nestedData.optString("order_status") }
                .ifBlank { nestedData.optString("status", "pending") }
                .trim()
                .lowercase(Locale.US)
        } else {
            rawStatus
        }

        return FamOrderStatusResult(
            status = resolvedStatus,
            orderId = dataObj.optString("order_id", fallbackOrderId),
            amount = dataObj.optString("amount", dataObj.optString("payable_amount", "")),
            transactionId = dataObj.optString("transaction_id", ""),
            utr = dataObj.optString("utr", ""),
            senderName = dataObj.optString("sender_name", "")
        )
    }

    /**
     * Timing-safe HMAC-SHA256 verification per FamGateway Webhook specification:
     * Expected Signature = HMAC_SHA256(raw_post_body_bytes, merchant_api_key)
     */
    fun verifyWebhookSignature(rawBody: String, receivedHexSignature: String): Boolean {
        val apiKey = resolveApiKey()
        if (apiKey.isBlank() || receivedHexSignature.isBlank()) return false
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(apiKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            mac.init(secretKey)
            val digest = mac.doFinal(rawBody.toByteArray(StandardCharsets.UTF_8))
            val expectedHex = digest.joinToString("") { "%02x".format(it) }
            MessageDigest.isEqual(
                expectedHex.lowercase(Locale.US).toByteArray(StandardCharsets.UTF_8),
                receivedHexSignature.trim().lowercase(Locale.US).toByteArray(StandardCharsets.UTF_8)
            )
        } catch (_: Exception) {
            false
        }
    }
}

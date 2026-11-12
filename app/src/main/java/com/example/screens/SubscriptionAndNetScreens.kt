package com.example.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.R
import com.example.models.AdminSubscriptionConfig
import com.example.models.SubscriptionPlanType
import com.example.models.UserSubscriptionState
import com.example.services.FamGatewayOrderSession
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.DividerDark
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyDarkGreenPill
import com.example.ui.theme.SpotifyGreenBorder
import com.example.ui.theme.SpotifyVibrantGreen
import com.example.ui.theme.SurfaceDarkCard

/**
 * Full-screen No Internet page shown whenever the user has no active internet connection.
 * Displays the AADIFY logo at the top and "No Internet - Please turn mobile network or connect with hotspot" below.
 */
@Composable
fun NoInternetScreen(
    onRetryConnection: () -> Unit,
    mySongsCount: Int = 0,
    downloadedSongsCount: Int = 0,
    onOpenMySongsOffline: (() -> Unit)? = null,
    onOpenDownloadedSongsOffline: (() -> Unit)? = null
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DeepCharcoal,
                        PureBlack,
                        PureBlack
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp)
            .testTag("no_internet_screen"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = SurfaceDarkCard,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.5.dp,
                    color = SpotifyGreenBorder.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top AADIFY Logo
                Image(
                    painter = painterResource(id = R.drawable.aadify_logo),
                    contentDescription = "AADIFY Logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .border(
                            width = 1.5.dp,
                            color = SpotifyVibrantGreen,
                            shape = RoundedCornerShape(22.dp)
                        )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    color = ErrorRed.copy(alpha = 0.15f),
                    shape = CircleShape,
                    modifier = Modifier.size(60.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.SignalWifiOff,
                            contentDescription = "No Internet",
                            tint = ErrorRed,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "No Internet",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 26.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Please turn mobile network or connect with hotspot",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MutedGray,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(22.dp))

                Button(
                    onClick = onRetryConnection,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyVibrantGreen,
                        contentColor = PureBlack
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("no_internet_retry_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Try Again",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = PureBlack,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } catch (_: Exception) {
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryWhite),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "Open Network Settings",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = PrimaryWhite,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                if (onOpenMySongsOffline != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpenMySongsOffline,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyDarkGreenPill,
                            contentColor = SpotifyVibrantGreen
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .border(
                                width = 1.dp,
                                color = SpotifyGreenBorder,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .testTag("no_internet_my_songs_button")
                    ) {
                        val badgeSuffix = if (mySongsCount > 0) " ($mySongsCount)" else ""
                        Text(
                            text = "🎵 My Songs$badgeSuffix",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = SpotifyVibrantGreen,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp
                            )
                        )
                    }
                }

                if (onOpenDownloadedSongsOffline != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onOpenDownloadedSongsOffline,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1F1704),
                            contentColor = Color(0xFFFFD700)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .border(
                                width = 1.dp,
                                color = Color(0xFFFFD700).copy(alpha = 0.65f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .testTag("no_internet_downloaded_songs_button")
                    ) {
                        val dlSuffix = if (downloadedSongsCount > 0) " ($downloadedSongsCount)" else ""
                        Text(
                            text = "📥 Downloaded Songs$dlSuffix 👑",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = Color(0xFFFFD700),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * First-time / New User "Crazy Deals" popup shown on Home screen with Admin-set banner,
 * cheapest price, live 3-day countdown timer, "Buy" button, and "Not Now" button.
 */
@Composable
fun CrazyDealsPopupDialog(
    config: AdminSubscriptionConfig,
    remainingWindowSeconds: Long,
    onBuyClick: () -> Unit,
    onNotNowClick: () -> Unit
) {
    Dialog(
        onDismissRequest = onNotNowClick,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = DeepCharcoal,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .border(
                    width = 1.5.dp,
                    color = SpotifyVibrantGreen,
                    shape = RoundedCornerShape(26.dp)
                )
                .testTag("crazy_deals_popup_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header badge + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color(0xFFFF6D00).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.border(
                            width = 1.dp,
                            color = Color(0xFFFF9100),
                            shape = RoundedCornerShape(50)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                tint = Color(0xFFFF9100),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "FIRST USER CRAZY DEAL",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color(0xFFFFAB40),
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.8.sp
                                )
                            )
                        }
                    }

                    IconButton(
                        onClick = onNotNowClick,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MutedGray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Banner Image (Admin-editable URL)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(155.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(SurfaceDarkCard)
                        .border(
                            width = 1.2.dp,
                            color = Color(0xFFFFAB40).copy(alpha = 0.7f),
                            shape = RoundedCornerShape(18.dp)
                        )
                ) {
                    AsyncImage(
                        model = config.crazyDealBannerUrl,
                        contentDescription = config.crazyDealTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (config.crazyDealBannerUrl.isBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            PureBlack.copy(alpha = 0.82f)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(14.dp)
                        ) {
                            Surface(
                                color = SpotifyVibrantGreen,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "CHEAPEST NEW USER OFFER",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = PureBlack,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 10.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = config.crazyDealTitle,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    color = PrimaryWhite,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 20.sp
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "🔥 ${config.crazyDealTitle}",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 3-Day Countdown Timer Pill
                if (remainingWindowSeconds > 0L) {
                    Surface(
                        color = SurfaceDarkCard,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = null,
                                tint = SpotifyVibrantGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Offer expires in: ${formatCountdownTimer(remainingWindowSeconds)}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = SpotifyVibrantGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                Text(
                    text = config.crazyDealSubtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MutedGray,
                        fontSize = 14.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Cheapest Price Card
                Surface(
                    color = SpotifyDarkGreenPill,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = SpotifyVibrantGreen,
                            shape = RoundedCornerShape(16.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "CHEAPEST SPECIAL PLAN",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = SpotifyVibrantGreen,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                            Text(
                                text = "${config.crazyDealDurationDays} Days Full Premium Access",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = PrimaryWhite,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                        Text(
                            text = "₹${config.crazyDealPriceInr}",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                color = SpotifyVibrantGreen,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 30.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Buy & Not Now Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onNotNowClick,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MutedGray),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("crazy_deal_not_now_button")
                    ) {
                        Text(
                            text = "Not Now",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = MutedGray,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Button(
                        onClick = onBuyClick,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyVibrantGreen,
                            contentColor = PureBlack
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(50.dp)
                            .testTag("crazy_deal_buy_button")
                    ) {
                        Text(
                            text = "Buy Now • ₹${config.crazyDealPriceInr}",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = PureBlack,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Blocking popup dropped on Free users as soon as their 1-hour (3600s) daily listening limit is reached.
 */
@Composable
fun DailyLimitReachedDialog(
    freeDailyLimitSeconds: Int = 3600,
    onBuySubscriptionClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            color = DeepCharcoal,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .border(
                    width = 1.5.dp,
                    color = ErrorRed.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(24.dp)
                )
                .testTag("daily_limit_reached_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = ErrorRed.copy(alpha = 0.16f),
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Limit Reached",
                            tint = ErrorRed,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val totalMins = (freeDailyLimitSeconds.coerceAtLeast(60)) / 60
                Text(
                    text = "Daily Free Limit (${totalMins}m) Reached",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Your limit has been reached! Buy subscription or wait for next day.",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MutedGray,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(22.dp))

                Button(
                    onClick = onBuySubscriptionClick,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyVibrantGreen,
                        contentColor = PureBlack
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("daily_limit_buy_subscription_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.WorkspacePremium,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Buy Subscription",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = PureBlack,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Wait for Next Day",
                        color = MutedGray
                    )
                }
            }
        }
    }
}

/**
 * Subscription Plans Modal showing Weekly, Monthly, Forever (and Crazy Deal if within 3 days of new account),
 * plus active subscription countdown timer if subscribed, and free-tier daily usage bar.
 */
@Composable
fun SubscriptionPlansDialog(
    config: AdminSubscriptionConfig,
    subscriptionState: UserSubscriptionState,
    activeSubscriptionRemainingSeconds: Long,
    crazyDealRemainingSeconds: Long,
    dailyListenedSeconds: Int,
    freeDailyLimitSeconds: Int = config.freeDailyLimitSeconds,
    onSelectPlanToBuy: (SubscriptionPlanType) -> Unit,
    onDismiss: () -> Unit
) {
    val isCurrentlySubscribed = subscriptionState.isActive()
    val royalGold = Color(0xFFFFD700)
    val deepGold = Color(0xFFFFAB00)
    val vipBorderBrush = Brush.linearGradient(
        colors = listOf(
            royalGold,
            SpotifyVibrantGreen,
            royalGold
        )
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF0B0E11),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .border(
                    width = 1.8.dp,
                    brush = vipBorderBrush,
                    shape = RoundedCornerShape(28.dp)
                )
                .testTag("subscription_plans_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF171307),
                                Color(0xFF0C120F),
                                Color(0xFF080A0C)
                            )
                        )
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp)
            ) {
                // Luxury Royal VIP Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF3A2B07),
                                            Color(0xFF1E1604)
                                        )
                                    )
                                )
                                .border(
                                    width = 1.2.dp,
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFFFFF3B0), royalGold, deepGold)
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "👑",
                                fontSize = 22.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "AADIFY VIP",
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                Color(0xFFFFF6C3),
                                                royalGold,
                                                Color(0xFFFFB300)
                                            )
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 21.sp,
                                        letterSpacing = 0.8.sp
                                    ),
                                    maxLines = 1
                                )
                                Surface(
                                    color = royalGold.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.border(
                                        width = 0.8.dp,
                                        color = royalGold.copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(50)
                                    )
                                ) {
                                    Text(
                                        text = "by Aadarsh",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = Color(0xFFFFE57F),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.5.sp
                                        ),
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        maxLines = 1
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "🚫 100% Ads-Free • 🟢 Spotify Import • ♾️ Unlimited VIP",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = SpotifyVibrantGreen,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(SurfaceDarkCard)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = PrimaryWhite,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Banner (shown cleanly without ugly overlapping text covering Admin's custom artwork!)
                if (config.premiumEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(152.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(SurfaceDarkCard)
                            .border(
                                width = 1.2.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(royalGold.copy(alpha = 0.8f), SpotifyVibrantGreen.copy(alpha = 0.8f))
                                ),
                                shape = RoundedCornerShape(18.dp)
                            )
                    ) {
                        AsyncImage(
                            model = config.premiumBannerUrl,
                            contentDescription = config.premiumTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (config.premiumBannerUrl.isBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                PureBlack.copy(alpha = 0.88f)
                                            )
                                        )
                                    )
                            )
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = config.premiumHeading,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = PrimaryWhite,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                                Text(
                                    text = config.premiumDescription,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = SpotifyVibrantGreen
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Active Subscription Status OR Clean Single-Line Free Daily Limit Status
                if (isCurrentlySubscribed) {
                    Surface(
                        color = Color(0xFF191405),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.2.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(royalGold, SpotifyVibrantGreen)
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "👑", fontSize = 22.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "VIP Active: ${subscriptionState.planType.displayName}",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = Color(0xFFFFF3B0),
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                                val timerLabel = if (subscriptionState.planType == SubscriptionPlanType.FOREVER) {
                                    "♾️ FOREVER 👑 • Lifetime VIP Unlocked"
                                } else {
                                    "⏳ Time Remaining: ${formatCountdownTimer(activeSubscriptionRemainingSeconds)}"
                                }
                                Text(
                                    text = timerLabel,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = SpotifyVibrantGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                } else {
                    val effectiveLimitSecs = freeDailyLimitSeconds.coerceAtLeast(60)
                    val totalLimitMins = effectiveLimitSecs / 60
                    val remainingFreeSeconds = (effectiveLimitSecs - dailyListenedSeconds).coerceAtLeast(0)
                    val remainingMins = remainingFreeSeconds / 60
                    val remainingSecs = remainingFreeSeconds % 60
                    Surface(
                        color = Color(0xFF14181D),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = DividerDark,
                                shape = RoundedCornerShape(14.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = if (remainingFreeSeconds > 300) SpotifyVibrantGreen else ErrorRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Free Daily Limit Left:",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MutedGray,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = if (remainingFreeSeconds > 300) SpotifyDarkGreenPill else ErrorRed.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.border(
                                    width = 1.dp,
                                    color = if (remainingFreeSeconds > 300) SpotifyGreenBorder else ErrorRed,
                                    shape = RoundedCornerShape(50)
                                )
                            ) {
                                Text(
                                    text = String.format("%02dm %02ds / %dm", remainingMins, remainingSecs, totalLimitMins),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = if (remainingFreeSeconds > 300) SpotifyVibrantGreen else ErrorRed,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.5.sp
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 👑 INCLUDED IN ALL PREMIUM PLANS — MOVED TO THE TOP WITH POINT-WISE EMOJIS!
                Surface(
                    color = Color(0xFF12161A),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.3.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    royalGold.copy(alpha = 0.85f),
                                    SpotifyVibrantGreen.copy(alpha = 0.75f),
                                    royalGold.copy(alpha = 0.85f)
                                )
                            ),
                            shape = RoundedCornerShape(18.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF1F1908).copy(alpha = 0.75f),
                                        Color(0xFF101614)
                                    )
                                )
                            )
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = royalGold.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.border(
                                    width = 1.dp,
                                    color = royalGold.copy(alpha = 0.75f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            ) {
                                Text(
                                    text = "👑 ALL-IN-ONE VIP PASS",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = Color(0xFFFFE57F),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.6.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            Text(
                                text = "Included in All Premium Plans:",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = PrimaryWhite,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.5.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        HorizontalDivider(color = DividerDark.copy(alpha = 0.6f))

                        BenefitRow(
                            emoji = "🚫",
                            title = "100% Ads-Free Music Experience",
                            subtitle = "Zero ads, zero interruptions — pure non-stop HD music"
                        )
                        BenefitRow(
                            emoji = "🟢",
                            title = "Import Spotify Playlists & Songs",
                            subtitle = "Paste any Spotify playlist or song link & play instantly"
                        )
                        BenefitRow(
                            emoji = "♾️",
                            title = "Unlimited 24/7 Music Streaming",
                            subtitle = "No daily free time limit — listen all day & night"
                        )
                        BenefitRow(
                            emoji = "👑",
                            title = "Royal VIP Gold Name & Crown Badge",
                            subtitle = "Luxury golden designer profile on Home & Drawer"
                        )
                        BenefitRow(
                            emoji = "☁️",
                            title = "Permanent Cloud Data Save (Only for VIP Users)",
                            subtitle = "Free user ka koi bhi data save nahi hota — Only Premium VIP users ka playlist & library data save rehta hai"
                        )
                        BenefitRow(
                            emoji = "📂",
                            title = "Unlimited Aadify & Spotify Playlists (Add / Delete Songs)",
                            subtitle = "Add or delete individual songs in both Aadify & Spotify playlists"
                        )
                        BenefitRow(
                            emoji = "🔗",
                            title = "1-Tap Short Playlist Share Links",
                            subtitle = "Share your playlists with friends via aadify.xo.je"
                        )
                        BenefitRow(
                            emoji = "🔁",
                            title = "Unlock Repeat One, Repeat All & Play All",
                            subtitle = "Full loop control + background gaming audio support"
                        )
                        BenefitRow(
                            emoji = "🎨",
                            title = "Unlock All 6 Exclusive Prime App Themes",
                            subtitle = "Switch between 6 neon & royal themes anytime"
                        )
                        BenefitRow(
                            emoji = "📥",
                            title = "Download Any Song & Spotify Track Offline",
                            subtitle = "App & Spotify ke kisi bhi song ko device mein download karke bina internet ke play karein (Saved in Downloaded Songs)"
                        )
                        BenefitRow(
                            emoji = "❤️",
                            title = "Unlimited Favorites & Custom Collections",
                            subtitle = "Save unlimited favorite tracks & unlock My Songs + Downloaded Songs"
                        )
                        BenefitRow(
                            emoji = "⚡",
                            title = "Instant Activation via AadarshPay UPI",
                            subtitle = "100% automated real-time VIP unlock in seconds"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "✨ CHOOSE YOUR VIP PLAN",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = Color(0xFFFFE57F),
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.1.sp,
                        fontSize = 12.sp
                    ),
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                )

                // If user is within new-user window and deal is enabled by Admin, show Crazy Deal card at top
                if (crazyDealRemainingSeconds > 0L && config.crazyDealEnabled) {
                    PlanOptionCard(
                        title = "🔥 ${config.crazyDealTitle}",
                        subtitle = "🚫 Ads-Free + 🟢 Spotify Import + ♾️ ${config.crazyDealDurationDays} Days Full VIP • Ends in ${formatCountdownTimer(crazyDealRemainingSeconds)}",
                        priceInr = config.crazyDealPriceInr,
                        badgeText = "⚡ SPECIAL NEW USER OFFER",
                        highlightColor = Color(0xFFFF9100),
                        isCurrentPlan = isCurrentlySubscribed && subscriptionState.planType == SubscriptionPlanType.CRAZY_DEAL,
                        onClick = { onSelectPlanToBuy(SubscriptionPlanType.CRAZY_DEAL) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 1. Weekly Plan
                PlanOptionCard(
                    title = "⚡ Weekly VIP Plan",
                    subtitle = "7 Days 100% Ads-Free • Spotify Import • Unlimited Playlists & Repeat",
                    priceInr = config.weeklyPriceInr,
                    badgeText = "7 DAYS VIP",
                    highlightColor = SpotifyVibrantGreen,
                    isCurrentPlan = isCurrentlySubscribed && subscriptionState.planType == SubscriptionPlanType.WEEKLY,
                    onClick = { onSelectPlanToBuy(SubscriptionPlanType.WEEKLY) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Monthly Plan
                PlanOptionCard(
                    title = "💎 Monthly VIP Plan",
                    subtitle = "30 Days 100% Ads-Free • Spotify Import • Cloud Backup & All 6 Themes",
                    priceInr = config.monthlyPriceInr,
                    badgeText = "🔥 MOST POPULAR",
                    highlightColor = SpotifyVibrantGreen,
                    isCurrentPlan = isCurrentlySubscribed && subscriptionState.planType == SubscriptionPlanType.MONTHLY,
                    onClick = { onSelectPlanToBuy(SubscriptionPlanType.MONTHLY) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 3. Forever Plan
                PlanOptionCard(
                    title = "👑 Forever Plan (Lifetime)",
                    subtitle = "♾️ FOREVER 👑 Lifetime Ads-Free VIP • Pay Once, Enjoy Forever",
                    priceInr = config.foreverPriceInr,
                    badgeText = "👑 BEST VALUE • LIFETIME ♾️",
                    highlightColor = royalGold,
                    isCurrentPlan = isCurrentlySubscribed && subscriptionState.planType == SubscriptionPlanType.FOREVER,
                    onClick = { onSelectPlanToBuy(SubscriptionPlanType.FOREVER) }
                )
            }
        }
    }
}

@Composable
private fun BenefitRow(
    emoji: String,
    title: String,
    subtitle: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = Color(0xFF1D2622),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .size(30.dp)
                .border(
                    width = 0.8.dp,
                    color = SpotifyGreenBorder.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp)
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = emoji,
                    fontSize = 15.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = PrimaryWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.8.sp
                )
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MutedGray,
                        fontSize = 11.sp
                    )
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = SpotifyVibrantGreen,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun PlanOptionCard(
    title: String,
    subtitle: String,
    priceInr: Int,
    badgeText: String,
    highlightColor: Color,
    isCurrentPlan: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isCurrentPlan) SpotifyDarkGreenPill else Color(0xFF13181D),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isCurrentPlan) 1.8.dp else 1.3.dp,
                color = if (isCurrentPlan) SpotifyVibrantGreen else highlightColor.copy(alpha = 0.75f),
                shape = RoundedCornerShape(18.dp)
            )
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            highlightColor.copy(alpha = 0.10f),
                            Color(0xFF13181D),
                            highlightColor.copy(alpha = 0.06f)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Surface(
                    color = highlightColor.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.border(
                        width = 0.8.dp,
                        color = highlightColor.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(50)
                    )
                ) {
                    Text(
                        text = if (isCurrentPlan) "✓ ACTIVE VIP PLAN" else badgeText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = highlightColor,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        ),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp
                    )
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MutedGray,
                        fontSize = 11.8.sp,
                        lineHeight = 16.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "₹$priceInr",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = highlightColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 25.sp
                    )
                )
                Spacer(modifier = Modifier.height(5.dp))
                Surface(
                    color = highlightColor,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (isCurrentPlan) "Extend" else "Buy VIP",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = PureBlack,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * FamGateway v2.0 Live UPI Payment Checkout Modal.
 * Shows dynamic QR code, "Pay via UPI App" intent button, "Open Checkout Page" button,
 * and automatic 3-second real-time payment verification status.
 */
@Composable
fun FamGatewayCheckoutDialog(
    orderSession: FamGatewayOrderSession?,
    isCreatingOrder: Boolean,
    isUserClickedIHavePaid: Boolean = false,
    isPaymentVerifiedSuccess: Boolean = false,
    paymentStatusMessage: String,
    errorMessage: String?,
    onVerifyNowClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = DeepCharcoal,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .border(
                    width = 1.5.dp,
                    color = SpotifyVibrantGreen,
                    shape = RoundedCornerShape(24.dp)
                )
                .testTag("famgateway_checkout_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = SpotifyVibrantGreen,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "AadarshPay UPI Checkout",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = PrimaryWhite,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp
                                )
                            )
                            Text(
                                text = "100% Automated Instant Verification",
                                style = MaterialTheme.typography.labelSmall.copy(color = SpotifyVibrantGreen)
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = MutedGray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isCreatingOrder) {
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressIndicator(
                        color = SpotifyVibrantGreen,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Generating secure UPI QR & Payment Link...",
                        style = MaterialTheme.typography.bodyMedium.copy(color = PrimaryWhite),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                } else if (errorMessage != null && orderSession == null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium.copy(color = ErrorRed),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyVibrantGreen,
                            contentColor = PureBlack
                        )
                    ) {
                        Text("Close")
                    }
                } else if (orderSession != null) {
                    Surface(
                        color = SpotifyDarkGreenPill,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = orderSession.planType.displayName,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = PrimaryWhite,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = "Order: ${orderSession.orderId}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = MutedGray)
                                )
                            }
                            Text(
                                text = "₹${orderSession.amountInr}",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    color = SpotifyVibrantGreen,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // QR Code Box
                    if (orderSession.qrUrl.isNotBlank()) {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.size(200.dp)
                        ) {
                            AsyncImage(
                                model = orderSession.qrUrl,
                                contentDescription = "Scan UPI QR Code",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Scan QR with any UPI App (GPay, PhonePe, Paytm, AadarshPay)",
                            style = MaterialTheme.typography.labelMedium.copy(color = MutedGray),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Pay via Installed UPI App (Intent Deep Link)
                    if (orderSession.upiIntent.isNotBlank()) {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(orderSession.upiIntent)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    if (orderSession.checkoutUrl.isNotBlank()) {
                                        try {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(orderSession.checkoutUrl)).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SpotifyVibrantGreen,
                                contentColor = PureBlack
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Pay ₹${orderSession.amountInr} with UPI App",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = PureBlack,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Open Web Checkout Link fallback
                    if (orderSession.checkoutUrl.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(orderSession.checkoutUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(browserIntent)
                                } catch (_: Exception) {
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryWhite),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Text(
                                text = "Open AadarshPay Web Checkout",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = PrimaryWhite,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    HorizontalDivider(color = DividerDark)
                    Spacer(modifier = Modifier.height(12.dp))

                    // LIVE PAYMENT STATUS BUTTON / PILL (Green when Successful, Pending/Checking while waiting)
                    val statusBgColor = when {
                        isPaymentVerifiedSuccess -> SpotifyVibrantGreen
                        isUserClickedIHavePaid -> Color(0xFFFFAB00).copy(alpha = 0.16f)
                        else -> SurfaceDarkCard
                    }
                    val statusBorderColor = when {
                        isPaymentVerifiedSuccess -> SpotifyVibrantGreen
                        isUserClickedIHavePaid -> Color(0xFFFFAB00)
                        else -> MutedGray.copy(alpha = 0.45f)
                    }
                    val statusContentColor = when {
                        isPaymentVerifiedSuccess -> PureBlack
                        isUserClickedIHavePaid -> Color(0xFFFFAB00)
                        else -> PrimaryWhite
                    }
                    val statusLabel = when {
                        isPaymentVerifiedSuccess -> "✓ STATUS: PAYMENT SUCCESSFUL"
                        isUserClickedIHavePaid -> "STATUS: PENDING • CHECKING PAYMENT..."
                        else -> "STATUS: PENDING • WAITING FOR PAYMENT"
                    }

                    Surface(
                        color = statusBgColor,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .border(
                                width = 1.2.dp,
                                color = statusBorderColor,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .testTag("aadarshpay_live_status_badge")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isPaymentVerifiedSuccess) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = PureBlack,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                CircularProgressIndicator(
                                    color = if (isUserClickedIHavePaid) Color(0xFFFFAB00) else SpotifyVibrantGreen,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = statusContentColor,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = paymentStatusMessage,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isPaymentVerifiedSuccess) SpotifyVibrantGreen else MutedGray,
                            fontWeight = FontWeight.Medium
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // "I Have Paid" Button:
                    // Once clicked ONCE, it locks into "Checking Payment... Please Wait" so the user never has to tap repeatedly!
                    Button(
                        onClick = {
                            if (!isUserClickedIHavePaid && !isPaymentVerifiedSuccess) {
                                onVerifyNowClick()
                            }
                        },
                        enabled = !isUserClickedIHavePaid && !isPaymentVerifiedSuccess,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPaymentVerifiedSuccess) SpotifyVibrantGreen else SpotifyDarkGreenPill,
                            contentColor = if (isPaymentVerifiedSuccess) PureBlack else PrimaryWhite,
                            disabledContainerColor = if (isPaymentVerifiedSuccess) {
                                SpotifyVibrantGreen
                            } else {
                                SpotifyDarkGreenPill.copy(alpha = 0.75f)
                            },
                            disabledContentColor = if (isPaymentVerifiedSuccess) PureBlack else SpotifyVibrantGreen
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .border(
                                width = 1.5.dp,
                                color = SpotifyVibrantGreen,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .testTag("aadarshpay_verify_now_button")
                    ) {
                        when {
                            isPaymentVerifiedSuccess -> {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = PureBlack,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Payment Verified ✓",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = PureBlack,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 16.sp
                                    )
                                )
                            }
                            isUserClickedIHavePaid -> {
                                CircularProgressIndicator(
                                    color = SpotifyVibrantGreen,
                                    strokeWidth = 2.2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Checking Payment... Please Wait",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = SpotifyVibrantGreen,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp
                                    )
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = SpotifyVibrantGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "I Have Paid",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = PrimaryWhite,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 16.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatCountdownTimer(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "00h 00m 00s"
    val days = totalSeconds / 86_400L
    val hours = (totalSeconds % 86_400L) / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (days > 0L) {
        String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds)
    } else {
        String.format("%02dh %02dm %02ds", hours, minutes, seconds)
    }
}

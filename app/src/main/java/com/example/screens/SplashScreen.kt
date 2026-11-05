package com.example.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.DividerDark
import com.example.ui.theme.MutedGray
import com.example.ui.theme.PrimaryWhite
import com.example.ui.theme.PureBlack
import com.example.ui.theme.SpotifyDarkGreenPill
import com.example.ui.theme.SpotifyGreenBorder
import com.example.ui.theme.SpotifyVibrantGreen
import com.example.ui.theme.SurfaceDarkCard
import kotlinx.coroutines.delay

@Composable
fun SplashScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "neon_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    val bar1 by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar1"
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(360, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar2"
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(480, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar3"
    )
    val bar4 by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(390, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar4"
    )
    val bar5 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(440, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar5"
    )

    var progress by remember { mutableFloatStateOf(0.08f) }
    LaunchedEffect(Unit) {
        val steps = 25
        for (i in 1..steps) {
            delay(65L)
            progress = (i.toFloat() / steps.toFloat()).coerceIn(0.08f, 1.0f)
        }
    }

    val statusText = when {
        progress < 0.4f -> "Initializing High-Definition Audio Engine..."
        progress < 0.78f -> "Syncing Online & Offline Music Library..."
        else -> "Preparing Background & Lock Screen Playback..."
    }

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
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        // Outer Ambient Glow
        Box(
            modifier = Modifier
                .size(320.dp)
                .scale(pulseScale)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SpotifyVibrantGreen.copy(alpha = glowAlpha),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(142.dp)
                    .scale(pulseScale)
                    .clip(RoundedCornerShape(30.dp))
                    .border(
                        width = 2.dp,
                        color = SpotifyVibrantGreen.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(30.dp)
                    )
                    .background(SurfaceDarkCard)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.aadify_logo),
                    contentDescription = "AADIFY Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(30.dp))
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(id = R.string.splash_logo_title),
                style = MaterialTheme.typography.displayLarge.copy(
                    color = PrimaryWhite,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 38.sp,
                    letterSpacing = 3.sp,
                    shadow = Shadow(
                        color = SpotifyVibrantGreen.copy(alpha = 0.85f),
                        offset = Offset(0f, 0f),
                        blurRadius = 28f
                    )
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                color = SpotifyDarkGreenPill,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.border(
                    width = 1.dp,
                    color = SpotifyGreenBorder,
                    shape = RoundedCornerShape(20.dp)
                )
            ) {
                Text(
                    text = "PREMIUM STREAMING & OFFLINE PLAYER",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SpotifyVibrantGreen,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        fontSize = 10.sp
                    ),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Animated Equalizer Wave Bars
            Row(
                modifier = Modifier.height(34.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                listOf(bar1, bar2, bar3, bar4, bar5, bar2, bar1).forEach { fraction ->
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .fillMaxHeight(fraction.coerceIn(0.2f, 1f))
                            .clip(RoundedCornerShape(50))
                            .background(SpotifyVibrantGreen)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            LinearProgressIndicator(
                progress = { progress },
                color = SpotifyVibrantGreen,
                trackColor = SurfaceDarkCard,
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MutedGray,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun DeveloperIntroScreen(
    onContinue: () -> Unit
) {
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
            .padding(horizontal = 22.dp, vertical = 20.dp)
            .testTag("developer_intro_screen"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = SurfaceDarkCard,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.5.dp,
                    color = SpotifyGreenBorder,
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
                Image(
                    painter = painterResource(id = R.drawable.aadify_logo),
                    contentDescription = "AADIFY Official Logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(92.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(
                            width = 1.5.dp,
                            color = SpotifyVibrantGreen,
                            shape = RoundedCornerShape(20.dp)
                        )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = SpotifyVibrantGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "OFFICIAL RELEASE • AADIFY",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = SpotifyVibrantGreen,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp
                        )
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Surface(
                    color = DeepCharcoal,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = DividerDark,
                            shape = RoundedCornerShape(18.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "This app is made by Aadarsh kumar a part of HIS",
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = PrimaryWhite,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 20.sp,
                                lineHeight = 27.sp
                            ),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = DividerDark)
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "This app is like spotify song Will play in background",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = SpotifyVibrantGreen,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                lineHeight = 23.sp
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            tint = SpotifyVibrantGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Non-stop background & screen-off audio playback",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = PrimaryWhite,
                                fontSize = 13.5.sp
                            )
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = SpotifyVibrantGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Lock Screen & Notification Bar smart controls",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = PrimaryWhite,
                                fontSize = 13.5.sp
                            )
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = SpotifyVibrantGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Stream online hits & play device offline music",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = PrimaryWhite,
                                fontSize = 13.5.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(26.dp))

                Button(
                    onClick = onContinue,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyVibrantGreen,
                        contentColor = PureBlack
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("developer_intro_continue_button")
                ) {
                    Text(
                        text = "Continue",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = PureBlack,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = PureBlack
                    )
                }
            }
        }
    }
}

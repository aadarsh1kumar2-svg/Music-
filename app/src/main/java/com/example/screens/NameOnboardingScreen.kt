package com.example.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
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

@Composable
fun NameOnboardingScreen(
    initialName: String = "",
    initialPassword: String = "",
    isAuthenticating: Boolean = false,
    authErrorMessage: String? = null,
    onClearError: () -> Unit = {},
    onAuthenticate: (userName: String, password: String, isCreateAccount: Boolean) -> Unit
) {
    var enteredName by rememberSaveable { mutableStateOf(initialName) }
    var enteredPassword by rememberSaveable { mutableStateOf(initialPassword) }
    var isCreateAccountMode by rememberSaveable { mutableStateOf(false) }
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var localValidationError by remember { mutableStateOf<String?>(null) }

    val activeError = localValidationError ?: authErrorMessage

    fun submitForm() {
        val cleanName = enteredName.trim()
        val cleanPass = enteredPassword.trim()
        if (cleanName.length < 2) {
            localValidationError = "Please enter a valid User Name (at least 2 characters)"
            return
        }
        if (cleanPass.length < 4) {
            localValidationError = "Please enter a password (at least 4 characters)"
            return
        }
        localValidationError = null
        onAuthenticate(cleanName, cleanPass, isCreateAccountMode)
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
            .padding(horizontal = 24.dp)
            .testTag("name_onboarding_screen"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = SurfaceDarkCard,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = SpotifyGreenBorder.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.aadify_logo),
                    contentDescription = "AADIFY Logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(18.dp))
                )

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    color = SpotifyDarkGreenPill,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = SpotifyGreenBorder,
                        shape = RoundedCornerShape(50)
                    )
                ) {
                    Text(
                        text = if (isCreateAccountMode) "CREATE NEW ACCOUNT" else "SIGN IN TO AADIFY",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = SpotifyVibrantGreen,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontSize = 11.sp
                        ),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (isCreateAccountMode) "Create Your Account" else "Welcome Back",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        color = PrimaryWhite,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 25.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isCreateAccountMode) {
                        "Choose a unique User Name and Password. Every username on AADIFY is 100% unique."
                    } else {
                        "Sign in with your User Name and Password to continue listening."
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MutedGray,
                        fontSize = 14.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(22.dp))

                // 1. User Name Field (Top)
                OutlinedTextField(
                    value = enteredName,
                    onValueChange = {
                        enteredName = it
                        localValidationError = null
                        onClearError()
                    },
                    enabled = !isAuthenticating,
                    label = { Text("User Name") },
                    placeholder = { Text("Enter unique username...", color = MutedGray) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "User Name",
                            tint = SpotifyVibrantGreen
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SpotifyVibrantGreen,
                        unfocusedBorderColor = DividerDark,
                        focusedLabelColor = SpotifyVibrantGreen,
                        unfocusedLabelColor = MutedGray,
                        focusedTextColor = PrimaryWhite,
                        unfocusedTextColor = PrimaryWhite,
                        cursorColor = SpotifyVibrantGreen
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_name_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 2. Password Field (Below User Name)
                OutlinedTextField(
                    value = enteredPassword,
                    onValueChange = {
                        enteredPassword = it
                        localValidationError = null
                        onClearError()
                    },
                    enabled = !isAuthenticating,
                    label = { Text("Password") },
                    placeholder = { Text("Enter your password...", color = MutedGray) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Password",
                            tint = SpotifyVibrantGreen
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (isPasswordVisible) "Hide Password" else "Show Password",
                                tint = MutedGray
                            )
                        }
                    },
                    visualTransformation = if (isPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (!isAuthenticating) {
                                submitForm()
                            }
                        }
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SpotifyVibrantGreen,
                        unfocusedBorderColor = DividerDark,
                        focusedLabelColor = SpotifyVibrantGreen,
                        unfocusedLabelColor = MutedGray,
                        focusedTextColor = PrimaryWhite,
                        unfocusedTextColor = PrimaryWhite,
                        cursorColor = SpotifyVibrantGreen
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_password_input")
                )

                if (!activeError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = activeError,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = ErrorRed,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                // 3. Primary Continue Button
                Button(
                    onClick = { submitForm() },
                    enabled = !isAuthenticating,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpotifyVibrantGreen,
                        contentColor = PureBlack
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("onboarding_submit_button")
                ) {
                    if (isAuthenticating) {
                        CircularProgressIndicator(
                            color = PureBlack,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isCreateAccountMode) "Creating Account..." else "Signing In...",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = PureBlack,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        )
                    } else {
                        Text(
                            text = if (isCreateAccountMode) "Create Account & Continue" else "Continue",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = PureBlack,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(color = DividerDark)

                Spacer(modifier = Modifier.height(14.dp))

                // 4. "Create your account" / "Back to Sign In" button below Continue
                OutlinedButton(
                    onClick = {
                        isCreateAccountMode = !isCreateAccountMode
                        localValidationError = null
                        onClearError()
                    },
                    enabled = !isAuthenticating,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SpotifyVibrantGreen
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("onboarding_toggle_create_account_button")
                ) {
                    Icon(
                        imageVector = if (isCreateAccountMode) Icons.Default.Person else Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = SpotifyVibrantGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isCreateAccountMode) {
                            "Already have an account? Sign In"
                        } else {
                            "Create your account"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = SpotifyVibrantGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Created by Aadarsh kr.",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = SpotifyVibrantGreen.copy(alpha = 0.9f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 0.6.sp
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_created_by_footer")
                )
            }
        }
    }
}

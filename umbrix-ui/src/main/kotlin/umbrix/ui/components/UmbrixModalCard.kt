package umbrix.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.theme.UmbrixTheme

/**
 * Foundation modal dialog shell providing standardized window scrimming, entrance/exit
 * scale-and-fade animation choreography, elevation dropshadows, focus containment,
 * Escape-to-dismiss keyboard routing, and uniform title/action layouts.
 *
 * @param title The dialog header text rendered in 16sp SemiBold.
 * @param onDismiss Callback executed when the scrim is clicked, Escape is pressed, or close button clicked.
 * @param width Structural width of the dialog card (defaults to 520.dp).
 * @param showCloseButton When true, renders a right-aligned '✕' dismiss button in the header.
 * @param isScrollable When true, wraps [content] inside a vertical scrolling container.
 * @param actions Optional right-aligned action buttons rendered in the card footer.
 * @param content Primary dialog presentation body.
 */
@Composable
fun UmbrixModalCard(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    showCloseButton: Boolean = true,
    isScrollable: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val cardFocusRequester = remember { FocusRequester() }

    // Intercept exit animation completion before delegating to onDismiss
    fun handleRequestDismiss() {
        if (visibleState.currentState && visibleState.targetState) {
            visibleState.targetState = false
        }
    }

    LaunchedEffect(visibleState.currentState, visibleState.targetState) {
        if (!visibleState.currentState && !visibleState.targetState) {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        try {
            cardFocusRequester.requestFocus()
        } catch (_: Throwable) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { canFocus = false },
        contentAlignment = Alignment.Center
    ) {
        // --- 1. Scrim Layer with 200ms Entrance / 150ms Exit Fade ---
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(durationMillis = 150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB3000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { handleRequestDismiss() }
                    )
            )
        }

        // --- 2. Elevated Card Layer with 150ms Scale/Fade Entrance ---
        AnimatedVisibility(
            visibleState = visibleState,
            enter = scaleIn(
                initialScale = 0.95f,
                animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(durationMillis = 150)),
            exit = scaleOut(
                targetScale = 0.95f,
                animationSpec = tween(durationMillis = 100)
            ) + fadeOut(animationSpec = tween(durationMillis = 100))
        ) {
            Box(
                modifier = Modifier
                    .width(width)
                    .fillMaxHeight(0.88f)
                    .shadow(elevation = 24.dp, shape = RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(UmbrixTheme.colors.surfaceElevated)
                    .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Absorb clicks inside card */ }
                    .focusRequester(cardFocusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            handleRequestDismiss()
                            true
                        } else false
                    }
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header Bar
                    ModalHeader(
                        title = title,
                        showCloseButton = showCloseButton,
                        onClose = { handleRequestDismiss() }
                    )

                    Divider(Orientation.Horizontal)

                    // Content Viewport
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        if (isScrollable) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(20.dp),
                                content = content
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                content = content
                            )
                        }
                    }

                    // Optional Action Footer
                    if (actions != null) {
                        Divider(Orientation.Horizontal)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(UmbrixTheme.colors.surfacePanel)
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModalHeader(
    title: String,
    showCloseButton: Boolean,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UmbrixTheme.colors.surfacePanel)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = UmbrixTheme.colors.textPrimary
        )

        if (showCloseButton) {
            var isHovered by remember { mutableStateOf(false) }
            val closeBg = if (isHovered) UmbrixTheme.colors.windowControlHoverClose else Color.Transparent
            val closeColor = if (isHovered) Color.White else UmbrixTheme.colors.textMuted

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(closeBg)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = closeColor
                )
            }
        }
    }
}

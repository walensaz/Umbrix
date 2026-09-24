package umbrix.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import umbrix.ui.theme.UmbrixTheme

/** Hover dwell before a cascading submenu opens or switches (Phase 4 §5.3). */
internal const val SUBMENU_OPEN_DELAY_MS: Long = 200L

/** Entrance fade + slide duration for every menu panel. */
private const val MENU_ENTER_DURATION_MS: Int = 150

/**
 * Hover bookkeeping for one menu panel level.
 *
 * Only pointer *enter* events update [hoveredItemKey]; exit events are ignored so the pointer can
 * travel from a parent row into its (separate popup) submenu without the cascade collapsing. The
 * submenu open state trails [hoveredItemKey] by [SUBMENU_OPEN_DELAY_MS], suppressing flicker when
 * sweeping vertically across several submenu parents.
 */
@Stable
internal class MenuLevelState {
    var hoveredItemKey by mutableStateOf<String?>(null)
    var openSubmenuKey by mutableStateOf<String?>(null)

    // Plain set (not snapshot state): only consulted inside key handlers, never drives composition
    val registeredSubmenuKeys: MutableSet<String> = mutableSetOf()

    val hasExpandedSubmenu: Boolean
        get() = openSubmenuKey?.let { openKey -> openKey in registeredSubmenuKeys } == true

    fun collapseSubmenu() {
        openSubmenuKey = null
        hoveredItemKey = null
    }
}

internal val LocalMenuLevelState = staticCompositionLocalOf<MenuLevelState?> { null }

/**
 * Compose-native root dropdown popup anchored below its trigger (the composable this is placed in).
 *
 * Encapsulates:
 * - Skia surface backed by [UmbrixTheme.colors.menuBackground], 8.dp radius, 1.dp `menuBorder`, 12.dp shadow
 * - 150ms fade + vertical slide entrance
 * - Dismissal on outside click; hierarchical Escape (first closes an open submenu, then the root)
 * - Up/Down arrow focus traversal with Enter activation
 * - Width bounded to 220.dp..320.dp
 *
 * Closed menus emit no composable nodes and hold no coroutines (zero idle CPU / RSS cost).
 */
@Composable
fun UmbrixDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (expanded) {
        val density = LocalDensity.current
        val positionProvider = remember(density) {
            with(density) {
                RootMenuPositionProvider(verticalGapPx = 4.dp.roundToPx(), edgeMarginPx = 8.dp.roundToPx())
            }
        }
        val levelState = remember { MenuLevelState() }
        val focusRequester = remember { FocusRequester() }

        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true)
        ) {
            val focusManager = LocalFocusManager.current
            MenuPanel(
                levelState = levelState,
                modifier = modifier
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) {
                            false
                        } else {
                            when (keyEvent.key) {
                                Key.Escape -> {
                                    if (levelState.hasExpandedSubmenu) {
                                        levelState.collapseSubmenu()
                                    } else {
                                        onDismissRequest()
                                    }
                                    true
                                }
                                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                                else -> false
                            }
                        }
                    },
                content = content
            )

            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
        }
    }
}

/**
 * Shared themed surface for root and cascading panels. Provides a fresh [MenuLevelState] to its
 * children and drives the debounced submenu open/close transitions for that level.
 */
@Composable
internal fun MenuPanel(
    levelState: MenuLevelState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val slideOffsetPx = remember(density) { with(density) { 4.dp.roundToPx() } }
    val entranceState = remember { MutableTransitionState(false).apply { targetState = true } }
    val panelShape = RoundedCornerShape(8.dp)

    LaunchedEffect(levelState.hoveredItemKey) {
        val targetKey = levelState.hoveredItemKey
        if (targetKey != levelState.openSubmenuKey) {
            delay(SUBMENU_OPEN_DELAY_MS)
            levelState.openSubmenuKey = targetKey
        }
    }

    CompositionLocalProvider(LocalMenuLevelState provides levelState) {
        AnimatedVisibility(
            visibleState = entranceState,
            enter = fadeIn(animationSpec = tween(MENU_ENTER_DURATION_MS, easing = FastOutSlowInEasing)) +
                slideInVertically(animationSpec = tween(MENU_ENTER_DURATION_MS, easing = FastOutSlowInEasing)) { -slideOffsetPx }
        ) {
            Column(
                modifier = modifier
                    .shadow(elevation = 12.dp, shape = panelShape)
                    .background(color = UmbrixTheme.colors.menuBackground, shape = panelShape)
                    .border(width = 1.dp, color = UmbrixTheme.colors.menuBorder, shape = panelShape)
                    .padding(vertical = 4.dp)
                    // widthIn must precede the intrinsic width so the 220..320dp bounds clamp it
                    .widthIn(min = 220.dp, max = 320.dp)
                    .width(IntrinsicSize.Max),
                content = content
            )
        }
    }
}

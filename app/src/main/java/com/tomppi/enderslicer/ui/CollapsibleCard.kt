package com.tomppi.enderslicer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * The folded card: one chevron and nothing else, in the smallest square
 * Material still calls a touch target.
 */
private val FoldedCardSize = 48.dp

/**
 * One floating Plate card with two states: open, or folded down to a bare
 * chevron tab.
 *
 * Folded, the card is only its chevron - a 48dp square sitting where the card
 * was and against the edge its caller anchored it to, so nothing but the arrow
 * covers the model. The whole tab is the tap target and it consumes touches
 * only inside those 48dp, so the orbit, pan and pinch gestures around it keep
 * reaching the model view underneath. There is no title left to name it, so
 * the chevron carries the label for TalkBack.
 *
 * Open, the header row is the tappable surface, [headerTrailing] rides in it
 * (the Print session card keeps its state chip and its Hide action there), and
 * the content is composed only while open.
 *
 * [expanded] is hoisted by the caller and kept in a rememberSaveable at the
 * root of [EnderSlicerApp]: selecting another tab disposes the whole Plate
 * subtree, so state remembered inside the card would not survive the trip.
 */
@Composable
internal fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier) {
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(
                        horizontal = EnderSlicerDimens.Space8,
                        vertical = EnderSlicerDimens.Space6,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
            ) {
                Text(
                    title,
                    style = titleStyle,
                    modifier = Modifier.weight(1f),
                )
                headerTrailing?.invoke()
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = EnderSlicerDimens.Space8,
                        end = EnderSlicerDimens.Space8,
                        bottom = EnderSlicerDimens.Space8,
                    ),
                verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space4),
                content = content,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(FoldedCardSize)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Expand $title",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

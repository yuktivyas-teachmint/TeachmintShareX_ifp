package com.teachmint.sharex.share.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teachmint.sharex.R
import com.teachmint.sharex.filetransfer.TransferredFileInfo
import com.teachmint.sharex.filetransfer.getTransferredFiles
import com.teachmint.sharex.getTimezoneOffsetMs
import com.teachmint.sharex.ui.theme.*
import com.teachmint.sharex.uiComponents.CompactSearchBar
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
fun TransferredFilesScreen(
    onBackClick: () -> Unit,
    onFileClick: (TransferredFileInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var files by remember { mutableStateOf(getTransferredFiles()) }
    var searchQuery by remember { mutableStateOf("") }

    LifecycleResumeEffect(Unit) {
        files = getTransferredFiles()
        onPauseOrDispose { }
    }

    val filteredFiles = remember(files, searchQuery) {
        if (searchQuery.isBlank()) files
        else files.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    Box(modifier = modifier) {
        // Gradient background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(AppSurface, AppBackground),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppDimens.dp24, vertical = AppDimens.dp20),
        ) {
            // Top bar with back button and title
            TransferredFilesTopBar(onBackClick = onBackClick)

            Spacer(modifier = Modifier.height(AppDimens.dp24))

            if (files.isEmpty()) {
                EmptyFilesState(modifier = Modifier.weight(1f))
            } else {
                // Search bar
                CompactSearchBar(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search by file name",
                    borderStroke = BorderStroke(AppDimens.dp1, AppBorder.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AppDimens.dp40),
                )

                Spacer(modifier = Modifier.height(AppDimens.dp16))

                if (filteredFiles.isEmpty()) {
                    NoFileFoundState(modifier = Modifier.weight(1f))
                } else {
                    // File summary header
                    FileSummaryHeader(files = filteredFiles)

                    Spacer(modifier = Modifier.height(AppDimens.dp16))

                    // File list
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(AppDimens.dp10),
                    ) {
                        items(filteredFiles, key = { it.absolutePath }) { file ->
                            TransferredFileItem(
                                file = file,
                                onClick = { onFileClick(file) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferredFilesTopBar(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back button
        Surface(
            shape = CircleShape,
            color = BackButtonBackground,
            border = BorderStroke(AppDimens.dp1, AppBorder.copy(alpha = 0.5f)),
            modifier = Modifier
                .size(AppDimens.dp40)
                .clickable(onClick = onBackClick),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                    modifier = Modifier.size(AppDimens.dp20),
                )
            }
        }

        Spacer(modifier = Modifier.width(AppDimens.dp16))

        Text(
            text = stringResource(R.string.received_files),
            color = TextPrimary,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun FileSummaryHeader(
    files: List<TransferredFileInfo>,
    modifier: Modifier = Modifier,
) {
    val totalSize = files.sumOf { it.size }
    val formattedTotal = formatTotalSize(totalSize)

    Surface(
        shape = RoundedCornerShape(AppDimens.dp12),
        color = CardButtonBackground,
        border = BorderStroke(AppDimens.dp1, PrimaryAccent.copy(alpha = 0.15f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = AppDimens.dp16, vertical = AppDimens.dp14),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = PrimaryAccent,
                modifier = Modifier.size(AppDimens.dp20),
            )
            Spacer(modifier = Modifier.width(AppDimens.dp10))
            Text(
                text = pluralStringResource(R.plurals.files_count, files.size, files.size),
                color = TextPrimary,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(modifier = Modifier.width(AppDimens.dp6))
            Text(
                text = "·",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.width(AppDimens.dp6))
            Text(
                text = formattedTotal,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyFilesState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Large icon with faded background circle
            Box(
                modifier = Modifier
                    .size(AppDimens.dp80)
                    .clip(CircleShape)
                    .background(AppBorder.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.size(AppDimens.dp40),
                )
            }

            Spacer(modifier = Modifier.height(AppDimens.dp20))

            Text(
                text = stringResource(R.string.no_files_received),
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )

            Spacer(modifier = Modifier.height(AppDimens.dp8))

            Text(
                text = stringResource(R.string.files_appear_here),
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun NoFileFoundState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Icon with faded background circle
            Box(
                modifier = Modifier
                    .size(AppDimens.dp80)
                    .clip(CircleShape)
                    .background(AppBorder.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SearchOff,
                    contentDescription = null,
                    tint = TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.size(AppDimens.dp40),
                )
            }

            Spacer(modifier = Modifier.height(AppDimens.dp20))

            Text(
                text = stringResource(R.string.no_files_found),
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )

            Spacer(modifier = Modifier.height(AppDimens.dp8))

            Text(
                text = stringResource(R.string.try_different_file_name),
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TransferredFileItem(
    file: TransferredFileInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fileCategory = getFileCategory(file.extension)

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppDimens.dp12),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        border = BorderStroke(AppDimens.dp1, AppBorder.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .padding(AppDimens.dp14)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Colored icon with background
            Box(
                modifier = Modifier
                    .size(AppDimens.dp44)
                    .clip(RoundedCornerShape(AppDimens.dp10))
                    .background(fileCategory.color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = fileCategory.icon,
                    contentDescription = null,
                    tint = fileCategory.color,
                    modifier = Modifier.size(AppDimens.dp22),
                )
            }

            Spacer(modifier = Modifier.width(AppDimens.dp12))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppDimens.dp4))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Extension badge
                    Surface(
                        shape = RoundedCornerShape(AppDimens.dp4),
                        color = fileCategory.color.copy(alpha = 0.1f),
                    ) {
                        Text(
                            text = file.extension.uppercase(),
                            color = fileCategory.color.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 10.sp,
                            ),
                            modifier = Modifier.padding(horizontal = AppDimens.dp6, vertical = AppDimens.dp2),
                        )
                    }
                    Spacer(modifier = Modifier.width(AppDimens.dp8))
                    Text(
                        text = file.formattedSize,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.width(AppDimens.dp8))
                    Text(
                        text = "·",
                        color = TextSecondary.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.width(AppDimens.dp8))
                    Text(
                        text = formatDate(file.lastModifiedMs),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(AppDimens.dp8))

            // Open indicator
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = "Open file",
                tint = TextSecondary.copy(alpha = 0.4f),
                modifier = Modifier.size(AppDimens.dp18),
            )
        }
    }
}

// -- File category mapping --

private data class FileCategory(
    val icon: ImageVector,
    val color: Color,
)

private val CategoryPdf = FileCategory(
    icon = Icons.Outlined.PictureAsPdf,
    color = Color(0xFFE57373), // Soft red
)

private val CategoryImage = FileCategory(
    icon = Icons.Outlined.Image,
    color = Color(0xFF81C784), // Soft green
)

private val CategoryVideo = FileCategory(
    icon = Icons.Outlined.VideoFile,
    color = Color(0xFFBA68C8), // Purple
)

private val CategoryAudio = FileCategory(
    icon = Icons.Outlined.AudioFile,
    color = Color(0xFFFFB74D), // Orange
)

private val CategoryDocument = FileCategory(
    icon = Icons.Outlined.Description,
    color = Color(0xFF64B5F6), // Blue
)

private val CategorySpreadsheet = FileCategory(
    icon = Icons.Outlined.TableChart,
    color = Color(0xFF4DB6AC), // Teal
)

private val CategoryPresentation = FileCategory(
    icon = Icons.Outlined.Slideshow,
    color = Color(0xFFFF8A65), // Deep orange
)

private val CategoryArchive = FileCategory(
    icon = Icons.Outlined.FolderZip,
    color = Color(0xFFA1887F), // Brown
)

private val CategoryText = FileCategory(
    icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
    color = Color(0xFF90A4AE), // Blue grey
)

private val CategoryGeneric = FileCategory(
    icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
    color = TextSecondary,
)

private fun getFileCategory(extension: String): FileCategory {
    return when (extension.lowercase()) {
        "pdf" -> CategoryPdf
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg" -> CategoryImage
        "mp4", "mkv", "avi", "mov", "wmv", "flv" -> CategoryVideo
        "mp3", "wav", "aac", "flac", "ogg", "m4a" -> CategoryAudio
        "doc", "docx" -> CategoryDocument
        "xls", "xlsx", "csv" -> CategorySpreadsheet
        "ppt", "pptx" -> CategoryPresentation
        "zip", "rar", "7z", "tar", "gz" -> CategoryArchive
        "txt", "md", "log" -> CategoryText
        else -> CategoryGeneric
    }
}

// -- Utility functions --

private fun formatTotalSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "${formatOneDecimal(gb)} GB"
        mb >= 1.0 -> "${formatOneDecimal(mb)} MB"
        kb >= 1.0 -> "${formatOneDecimal(kb)} KB"
        else -> "$bytes B"
    }
}

private fun formatOneDecimal(value: Double): String {
    val rounded = kotlin.math.round(value * 10).toLong()
    return "${rounded / 10}.${rounded % 10}"
}

private fun formatDate(epochMs: Long): String {
    if (epochMs <= 0) return ""

    val offsetMs = getTimezoneOffsetMs(epochMs)
    val localEpochMs = epochMs + offsetMs
    val totalSeconds = localEpochMs / 1000
    val minute = ((totalSeconds / 60) % 60).toInt()
    val hour24 = ((totalSeconds / 3600) % 24).toInt()

    val amPm = if (hour24 < 12) "AM" else "PM"
    val hour12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }

    // Days since epoch (Jan 1, 1970)
    var remainingDays = (totalSeconds / 86400).toInt()

    // Compute year
    var year = 1970
    while (true) {
        val daysInYear = if (isLeapYear(year)) 366 else 365
        if (remainingDays < daysInYear) break
        remainingDays -= daysInYear
        year++
    }

    // Compute month and day
    val daysInMonths = if (isLeapYear(year))
        intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    else
        intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    var month = 0
    while (month < 12 && remainingDays >= daysInMonths[month]) {
        remainingDays -= daysInMonths[month]
        month++
    }
    val day = remainingDays + 1

    val monthNames = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )
    val monthName = monthNames[month]

    return "$monthName $day, $year $hour12:${minute.toString().padStart(2, '0')} $amPm"
}

private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

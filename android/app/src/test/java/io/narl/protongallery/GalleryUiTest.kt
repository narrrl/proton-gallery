package io.narl.protongallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.luminance
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.github.takahirom.roborazzi.captureRoboImage
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.FolderAlbum
import io.narl.protongallery.bridge.AlbumOptions
import io.narl.protongallery.bridge.AlbumOrder
import io.narl.protongallery.bridge.AlbumGrouping
import io.narl.protongallery.bridge.AccountStatus
import io.narl.protongallery.bridge.PhoneFolder
import io.narl.protongallery.bridge.FreeUpSummary
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import io.narl.protongallery.bridge.SignInStage
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xxhdpi")
class GalleryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun the_empty_library_has_a_working_import_action() {
        var picks = 0
        render(emptyList(), ThemeMode.Light, onImport = { picks++ })
        compose.onNodeWithText("Choose photos").performClick()
        assertEquals(1, picks)
        compose.onRoot().captureRoboImage("build/screenshots/empty-light.png")
    }

    @Test fun account_and_appearance_share_one_menu_in_dark_mode() {
        render(emptyList(), ThemeMode.Dark, initialLocation = GalleryLocation())
        compose.onNodeWithContentDescription("Account and settings").performClick()
        compose.onNodeWithText("Theme").assertExists()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithText("Sign in").assertExists()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/menu-light.png")
    }

    @Test fun text_stays_readable_in_dark_mode() {
        render(fixtureItems(), ThemeMode.Dark, initialLocation = GalleryLocation(GallerySection.Drive))
        compose.onNodeWithText("Trips").performClick()
        compose.onNodeWithText("2026").performClick()
        compose.onNodeWithText("Coast").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        val heading = compose.onAllNodesWithText(galleryDate(1_789_000_000L)).onFirst().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        heading.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val color = layouts.single().layoutInput.style.color
        assertTrue("date heading is too dark: $color", color.luminance() > 0.5f)
        compose.onRoot().captureRoboImage("build/screenshots/album-dark.png")
    }

    @Test fun pinching_the_grid_changes_tile_size_and_headings() {
        var zoom = ZOOM_DEFAULT
        render(fixtureItems(), ThemeMode.Light, onZoom = { zoom = it })
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(galleryDate(1_789_000_000L)).assertCountEquals(1)
        compose.onRoot().performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(240f, 0f), center + Offset(40f, 0f), center + Offset(240f, 0f))
        }
        assertTrue("zoom stayed at $zoom", zoom > ZOOM_DEFAULT)
        compose.onRoot().performTouchInput {
            pinch(center - Offset(400f, 0f), center - Offset(10f, 0f), center + Offset(400f, 0f), center + Offset(10f, 0f))
        }
        compose.waitForIdle()
        assertTrue("zoom $zoom is not small", zoom < 64f)
        // Small tiles head each year rather than each day.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2026").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(galleryDate(1_789_000_000L)).assertCountEquals(0)
        waitForThumbnails()
        compose.onRoot().captureRoboImage("build/screenshots/zoomed-out-light.png")
    }

    @Test fun a_photo_opens_with_device_availability_and_returns_to_the_grid() {
        val items = fixtureItems()
        render(items, ThemeMode.Light)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        waitForThumbnails()
        compose.onRoot().captureRoboImage("build/screenshots/library-light.png")
        compose.onNodeWithContentDescription("Photo: Coast 0.jpg").performClick()
        compose.onNodeWithText("Available on this device").assertExists()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.onNodeWithText("On this device").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Drive").performClick()
        compose.onNodeWithText("Trips").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/collections-light.png")
    }

    @Test @Config(sdk = [34], qualifiers = "w800dp-h1100dp-xhdpi")
    fun the_grid_adapts_to_a_tablet_without_fixed_column_counts() {
        render(fixtureItems(), ThemeMode.Dark)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        waitForThumbnails()
        compose.onRoot().captureRoboImage("build/screenshots/library-tablet-dark.png")
    }

    @Test @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun import_remains_reachable_with_large_text_on_a_small_phone() {
        var picks = 0
        render(emptyList(), ThemeMode.Light, onImport = { picks++ }, fontScale = 1.5f)
        compose.onNodeWithText("Choose photos").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, picks)
        compose.onRoot().captureRoboImage("build/screenshots/empty-large-text.png")
    }

    @Test fun drive_folders_open_as_nested_albums_and_back_returns_to_the_parent() {
        render(fixtureItems(), ThemeMode.Light, initialLocation = GalleryLocation())
        compose.onNodeWithText("Drive").performClick()
        compose.onNodeWithText("Trips").performClick()
        compose.onNodeWithText("2026").performClick()
        compose.onNodeWithText("Coast").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("build/screenshots/nested-album-light.png")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Coast").assertExists()
    }

    @Test fun the_photos_tab_asks_to_show_this_phones_photos() {
        var asked = 0
        render(fixtureItems(), ThemeMode.Light, initialLocation = GalleryLocation(), backup = BackupState(access = false),
            actions = BackupActions(allowAccess = { asked++ }))
        compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").assertCountEquals(0)
        compose.onNodeWithText("Show this phone’s photos").performClick()
        assertEquals(1, asked)
        compose.onRoot().captureRoboImage("build/screenshots/main-photos-empty.png")
    }

    @Test fun phone_photos_show_what_is_not_backed_up_and_back_up_a_selection() {
        val items = fixtureItems().mapIndexed { index, item -> item.copy(phoneId = "$index", backedUp = index % 2 == 0) }
        var uploaded = emptyList<GalleryItem>()
        render(items, ThemeMode.Light, initialLocation = GalleryLocation(), photosTab = true,
            actions = BackupActions(upload = { uploaded = it }))
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg, not backed up").fetchSemanticsNodes().isNotEmpty() }
        waitForThumbnails()
        compose.onRoot().captureRoboImage("build/screenshots/phone-timeline-light.png")
        compose.onNodeWithContentDescription("Photo: Coast 1.jpg, not backed up").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithContentDescription("Photo: Coast 0.jpg").performClick()
        compose.onNodeWithText("2 selected").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/phone-selection-light.png")
        compose.onNodeWithText("Back up 1").performClick()
        assertEquals(listOf("Coast 1.jpg", "Coast 0.jpg"), uploaded.map { it.name })
        compose.onNodeWithText("2 selected").assertDoesNotExist()
    }

    @Test fun the_backup_sheet_chooses_folders_and_conditions() {
        val folders = listOf(PhoneFolder("camera", "Camera", 120uL, 7uL, false), PhoneFolder("shots", "Screenshots", 30uL, 0uL, true))
        val changes = mutableListOf<String>()
        render(emptyList(), ThemeMode.Dark, initialLocation = GalleryLocation(), backup = BackupState(folders = folders),
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "tester", null, false)),
            actions = BackupActions(setFolder = { folder, on -> changes += "${folder.id}=$on" }, setWifiOnly = { changes += "wifi=$it" }))
        compose.onNodeWithContentDescription("Account and settings").performClick()
        compose.onNodeWithText("Backup is on. Everything is backed up.").performClick()
        compose.onNodeWithText("Back up this phone").assertExists()
        compose.onNodeWithText("120 items · 7 not backed up").assertExists()
        val switches = compose.onAllNodes(isToggleable())
        switches[0].performClick()
        switches[2].performClick()
        assertEquals(listOf("camera=true", "wifi=false"), changes)
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/backup-sheet-dark.png")
    }

    @Test fun selected_photos_can_be_shared() {
        val items = fixtureItems().mapIndexed { index, item -> item.copy(phoneId = "$index", backedUp = true) }
        var shared = emptyList<GalleryItem>()
        render(items, ThemeMode.Light, initialLocation = GalleryLocation(), photosTab = true, media = MediaActions(share = { shared = it }))
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Photo: Coast 1.jpg").performTouchInput { longClick() }
        // Everything is backed up already, so only sharing is offered.
        compose.onNodeWithText("Back up", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Share").performClick()
        assertEquals(listOf("Coast 1.jpg"), shared.map { it.name })
    }

    @Test fun deleting_asks_first_and_says_which_trash() {
        // Two on the phone and in Proton, one only in Proton.
        val items = fixtureItems().mapIndexed { index, item ->
            item.copy(phoneId = if (index < 2) "$index" else null, backedUp = true, inProton = true)
        }
        var deleted = emptyList<GalleryItem>()
        render(items, ThemeMode.Light, initialLocation = GalleryLocation(), photosTab = true, media = MediaActions(delete = { deleted = it }))
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Photo: Coast 1.jpg").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Photo: Coast 2.jpg").performClick()
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Delete 2 items?").assertExists()
        compose.onNodeWithText("They move to the trash on this phone and in Proton Photos.").assertExists()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/delete-confirm-light.png")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<GalleryItem>(), deleted)
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.onNodeWithText("Move to trash").performClick()
        assertEquals(listOf("Coast 1.jpg", "Coast 2.jpg"), deleted.map { it.name })
        compose.onNodeWithText("2 selected").assertDoesNotExist()
    }

    @Test fun files_kept_only_in_the_app_cannot_be_deleted_from_a_selection() {
        render(fixtureItems(), ThemeMode.Light)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg", substring = true).onFirst().performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithContentDescription("Delete").assertDoesNotExist()
    }

    @Test fun another_app_can_pick_several_photos() {
        val items = fixtureItems().mapIndexed { index, item -> item.copy(phoneId = "$index", backedUp = true) }
        var picked = emptyList<GalleryItem>()
        render(items, ThemeMode.Light, initialLocation = GalleryLocation(), photosTab = true,
            pick = PickRequest(multiple = true, mime = "image/*"), onPick = { picked = it })
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Choose photos and videos").assertExists()
        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Photo: Coast 1.jpg").performClick()
        compose.onNodeWithContentDescription("Photo: Coast 2.jpg").performClick()
        compose.onNodeWithText("2 selected").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/pick-light.png")
        compose.onNodeWithText("Add").performClick()
        assertEquals(listOf("Coast 1.jpg", "Coast 2.jpg"), picked.map { it.name })
    }

    @Test fun one_tap_hides_the_viewer_bars_and_another_brings_them_back() {
        // Proton items with a small preview: no full-size decode runs, which
        // the test renderer can't do off the main thread.
        val preview = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        val items = fixtureItems().mapIndexed { index, item -> item.copy(localPath = "", remoteKey = "key-$index") }
        render(items, ThemeMode.Light, media = MediaActions(largePhoto = { preview }))
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Photo: Coast 0.jpg").performClick()
        compose.onNodeWithContentDescription("Back to gallery").assertExists()
        compose.onNodeWithText("In Proton Photos").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Coast 0.jpg").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Back to gallery").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("In Proton Photos").assertDoesNotExist()
        compose.onNodeWithContentDescription("Coast 0.jpg").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Back to gallery").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun background_work_shows_as_one_line_a_bar_and_the_account_ring() {
        val progress = BackupProgress(uploading = true, done = 1, total = 5, bytes = 50, size = 100)
        render(fixtureItems(), ThemeMode.Light, initialLocation = GalleryLocation(), photosTab = true,
            backup = BackupState(progress = progress),
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "tester", null, false)))
        compose.onNodeWithText("Backing up 2 of 5").assertExists()
        compose.onNodeWithContentDescription("Account and settings").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Backing up 2 of 5"))
        // The bar and the ring both show how far the upload is.
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.3f, 0f..1f))).assertCountEquals(2)
        compose.onRoot().captureRoboImage("build/screenshots/activity-light.png")
    }

    @Test fun free_up_space_keeps_recent_days_and_asks_before_removing() {
        val folders = listOf(PhoneFolder("camera", "Camera", 120uL, 0uL, true))
        var freed = 0
        val kept = mutableListOf<Int>()
        render(emptyList(), ThemeMode.Light, initialLocation = GalleryLocation(),
            backup = BackupState(folders = folders, freeable = FreeUpSummary(40uL, 1_500_000_000uL)),
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "tester", null, false)),
            actions = BackupActions(setKeepDays = { kept += it }, freeUp = { freed++ }))
        compose.onNodeWithContentDescription("Account and settings").performClick()
        compose.onNodeWithText("Backup is on. Everything is backed up.").performClick()
        compose.onNodeWithText("1 year").performScrollTo().performClick()
        assertEquals(listOf(365), kept)
        compose.onNodeWithText("40 items older than that are backed up", substring = true).performScrollTo().assertExists()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/free-up-light.png")
        compose.onNode(hasText("Free up 1.", substring = true) and hasClickAction()).performScrollTo().performClick()
        assertEquals(1, freed)
    }

    @Test fun an_album_shows_its_details_and_edit_options() {
        val options = AlbumOptions(null, "Summer by the sea", null, AlbumGrouping.DAY, AlbumOrder.NEWEST, false, false, listOf("Trip"))
        render(fixtureItems(), ThemeMode.Light, options = options, initialLocation = GalleryLocation())
        compose.onNodeWithText("Drive").performClick()
        compose.onNodeWithText("Trips").performClick()
        compose.onNodeWithText("2026").performClick()
        compose.onNodeWithText("Coast").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        waitForThumbnails()
        compose.onRoot().captureRoboImage("build/screenshots/album-light.png")
        compose.onNodeWithContentDescription("Edit album").performClick()
        compose.onNodeWithText("Pin to top").assertExists()
        compose.onNodeWithText("Group by").assertDoesNotExist()
        compose.onNodeWithText("Remove gallery from this app").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/screenshots/album-options-light.png")
    }

    private fun render(items: List<GalleryItem>, mode: ThemeMode, onImport: () -> Unit = {}, fontScale: Float = 1f, initialLocation: GalleryLocation = GalleryLocation(GallerySection.Device), options: AlbumOptions? = null, onZoom: (Float) -> Unit = {},
        backup: BackupState = BackupState(), actions: BackupActions = BackupActions(), account: AccountState = AccountState(), photosTab: Boolean = false,
        media: MediaActions = MediaActions(), pick: PickRequest? = null, onPick: (List<GalleryItem>) -> Unit = {}, state: LibraryState? = null) {
        val directory = File("build/test-thumbnails-smart").apply { mkdirs() }
        compose.setContent {
            var location by remember { mutableStateOf(initialLocation) }
            val root = remember { FolderAlbum("gallery", "root", null, "Trips", 0uL, 1uL) }
            val year = remember { FolderAlbum("gallery", "year", "root", "2026", 0uL, 1uL) }
            val coast = remember { FolderAlbum("gallery", "coast", "year", "Coast", items.size.toULong(), 0uL, description = options?.description, tags = options?.tags ?: emptyList()) }
            val albums = when (location.album?.id) {
                null -> if (location.section == GallerySection.Drive) listOf(root) else emptyList()
                "root" -> listOf(year)
                "year" -> listOf(coast)
                else -> emptyList()
            }
            val visible = if (location.section == GallerySection.Device || location.album?.id == "coast" || (photosTab && location == GalleryLocation())) items else emptyList()
            val photos = remember(visible) { flowOf(PagingData.from(visible)) }.collectAsLazyPagingItems()
            var theme by remember { mutableStateOf(mode) }
            var zoom by remember { mutableFloatStateOf(ZOOM_DEFAULT) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                GalleryTheme(theme) {
                    GalleryApp(state ?: LibraryState(ready = true, count = visible.size.toLong(), bytes = 12_000_000), photos,
                        remember { ThumbnailLoader(directory, phone = { ThumbnailLoader.decodeImage(File(it.localPath), 384) }) }, theme, zoom, onImport, { theme = it },
                        { zoom = it; onZoom(it) }, {}, {}, location, albums, { location = it },
                        options = if (location.album?.id == "coast") options else null,
                        account = account, backup = backup, backupActions = actions, media = media, pick = pick, onPick = onPick)
                }
            }
        }
    }

    private fun waitForThumbnails() {
        compose.waitUntil(15_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Preview ready")).fetchSemanticsNodes().size >= 6
        }
    }

    /** Procedural test fixtures, not user photos and not shipped demo content. */
    private fun fixtureItems(): List<GalleryItem> {
        val dir = File("build/test-media").apply { mkdirs() }
        return (0..23).map { index ->
            val file = File(dir, "coast-$index.jpg")
            val dimensions = listOf(768 to 512, 384 to 576, 640 to 360, 512 to 512, 384 to 512, 768 to 432)[index % 6]
            val bitmap = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { scale(dimensions.first / 384f, dimensions.second / 384f) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val palettes = listOf(intArrayOf(0xFF91BBD1.toInt(), 0xFF3C7391.toInt(), 0xFFDFD5B5.toInt()), intArrayOf(0xFFBFCBC4.toInt(), 0xFF56756B.toInt(), 0xFF2B514C.toInt()), intArrayOf(0xFFD4B6A1.toInt(), 0xFFA27666.toInt(), 0xFF614E5A.toInt()))
            val palette = palettes[index % palettes.size]
            canvas.drawColor(palette[0]); paint.color = palette[1]
            canvas.drawCircle((80 + index * 37 % 250).toFloat(), 320f, 190f, paint)
            paint.color = palette[2]; canvas.drawCircle(330f, 420f, 210f, paint)
            paint.color = Color.WHITE; paint.alpha = 120
            canvas.drawCircle(290f, 70f, 26f, paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            GalleryItem("fixture-smart-$index", "Coast $index.jpg", "image/jpeg", false, file.length().toULong(), 1_789_000_000L - index / 9 * 86_400, file.absolutePath, dimensions.first.toDouble() / dimensions.second)
        }
    }
}

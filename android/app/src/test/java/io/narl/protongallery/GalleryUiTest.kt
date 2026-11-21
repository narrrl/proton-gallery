package io.narl.protongallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.luminance
import com.github.takahirom.roborazzi.captureRoboImage
import io.narl.protongallery.bridge.GalleryItem
import io.narl.protongallery.bridge.PlacePhoto
import io.narl.protongallery.bridge.BackupAlbumTarget
import io.narl.protongallery.bridge.FolderAlbum
import io.narl.protongallery.bridge.AlbumOptions
import io.narl.protongallery.bridge.AlbumOrder
import io.narl.protongallery.bridge.AlbumGrouping
import io.narl.protongallery.bridge.AccountStatus
import io.narl.protongallery.bridge.PhoneFolder
import io.narl.protongallery.bridge.FreeUpSummary
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import io.narl.protongallery.bridge.SignInStage
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers

/** Robolectric runs slowly on shared CI runners; give first frames time. */
private const val UI_TIMEOUT_MS = 30_000L

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
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
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
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(galleryDate(1_789_000_000L)).assertCountEquals(1)
        compose.onRoot().performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(240f, 0f), center + Offset(40f, 0f), center + Offset(240f, 0f))
        }
        assertTrue("zoom stayed at $zoom", zoom > ZOOM_DEFAULT)
        // Two separate gestures: the full plan after the first lands first.
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            pinch(center - Offset(400f, 0f), center - Offset(10f, 0f), center + Offset(400f, 0f), center + Offset(10f, 0f))
        }
        compose.waitForIdle()
        assertTrue("zoom $zoom is not small", zoom < 64f)
        // Small tiles head each year rather than each day.
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText("2026").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(galleryDate(1_789_000_000L)).assertCountEquals(0)
        waitForThumbnails()
        compose.onRoot().captureRoboImage("build/screenshots/zoomed-out-light.png")
    }

    @Test fun a_photo_opens_with_device_availability_and_returns_to_the_grid() {
        val items = fixtureItems()
        render(items, ThemeMode.Light)
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        waitForThumbnails()
        compose.onRoot().captureRoboImage("build/screenshots/library-light.png")
        compose.onNodeWithContentDescription("Photo: Coast 0.jpg").performClick()
        compose.onNodeWithText("Available on this device").assertExists()
        compose.onNodeWithContentDescription("Back to gallery").performClick()
        compose.onNodeWithText("On this device").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Drive").performClick()
        compose.onNodeWithText("Trips").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/collections-light.png")
    }

    @Test fun places_opens_a_located_photo_in_the_gallery_viewer() {
        val fixtures = fixtureItems().take(3)
        val item = fixtures.first()
        val outline = GalleryOutline.of(fixtures)
        val photos = listOf(
            PlacePhoto(item.id, item.name, 0.537, 0.328, "Berlin", 0u),
            PlacePhoto(fixtures[1].id, fixtures[1].name, 0.506, 0.344, "Paris", 1u),
            PlacePhoto(fixtures[2].id, fixtures[2].name, 0.439, 0.265, "Reykjavík", 2u),
        )
        var opened: String? = null
        render(fixtures, ThemeMode.Dark, initialLocation = GalleryLocation(), photosTab = true,
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "demo", null, false)),
            places = PlacesActions(
                scan = { update -> update(PlacesProgress(photos, checked = 3, total = 3, scanning = false)) },
                open = { id -> opened = id; outline to fixtures.indexOfFirst { it.id == id } },
            ))
        compose.onNodeWithContentDescription("Places").performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo map").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("3 photos on the map").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/places-dark.png")
        compose.onNodeWithText("List photos").performClick()
        compose.onNodeWithContentDescription("Photo: ${item.name}").performClick()
        compose.onNodeWithContentDescription("Back to gallery").assertExists()
        assertEquals(item.id, opened)
    }

    @Test fun tapping_colocated_markers_lists_both_photos_immediately() {
        val items = fixtureItems().take(2)
        val photos = items.mapIndexed { index, it -> PlacePhoto(it.id, it.name, 0.537, 0.328, "Berlin", index.toUInt()) }
        render(items, ThemeMode.Dark, initialLocation = GalleryLocation(GallerySection.Places),
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "demo", null, false)),
            places = PlacesActions(scan = { update ->
                update(PlacesProgress(photos, checked = 2, total = 2, scanning = false))
            }))
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo map").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(UI_TIMEOUT_MS) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 place")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Photo map").performTouchInput { click(center) }
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText("2 photos").fetchSemanticsNodes().isNotEmpty() }
        items.forEach { compose.onNodeWithContentDescription("Photo: ${it.name}").assertExists() }
    }

    @Test fun the_zoom_pill_changes_the_scale() {
        val items = fixtureItems().take(2)
        val photos = items.mapIndexed { index, it -> PlacePhoto(it.id, it.name, 0.537 + index * .01, 0.328, "Berlin", index.toUInt()) }
        render(items, ThemeMode.Light, initialLocation = GalleryLocation(GallerySection.Places),
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "demo", null, false)),
            places = PlacesActions(scan = { update ->
                update(PlacesProgress(photos, checked = 2, total = 2, scanning = false))
            }))
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo map").fetchSemanticsNodes().isNotEmpty() }
        fun scale() = compose.onAllNodes(SemanticsMatcher("scale bar") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Scale ") } == true
        }).fetchSemanticsNodes().single().config[SemanticsProperties.ContentDescription].single()
        val before = scale()
        compose.onNodeWithContentDescription("Zoom out").performClick()
        compose.onNodeWithContentDescription("Zoom out").performClick()
        compose.waitForIdle()
        assertNotEquals(before, scale())
        compose.onRoot().captureRoboImage("build/screenshots/places-light.png")
    }

    @Test fun an_album_only_gallery_has_no_media_loading_indicator() {
        val trips = FolderAlbum("gallery", "root", null, "Trips", 0uL, 1uL)
        compose.setContent {
            GalleryTheme(ThemeMode.Dark) {
                GalleryApp(LibraryState(ready = true), null, ThumbnailLoader(File("build/test-thumbnails-smart")),
                    ThemeMode.Dark, ZOOM_DEFAULT, {}, {}, {}, {}, {},
                    location = GalleryLocation(GallerySection.Drive), albums = listOf(trips))
            }
        }
        compose.onNodeWithText("Trips").assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    @Test fun a_private_thumbnail_retries_and_stays_in_memory_until_lock() {
        val directory = java.nio.file.Files.createTempDirectory("private-thumbnail").toFile()
        try {
            val original = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val bytes = java.io.ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val item = fixtureItems().first().copy(localPath = "", remoteKey = "photo-fixture", phoneId = null)
            var attempts = 0
            val thumbnails = ThumbnailLoader(directory, remote = {
                if (++attempts == 1) error("Temporary thumbnail failure")
                bytes.copyOf()
            })

            assertNotNull(runBlocking { thumbnails.load(item, private = true) })
            assertEquals(2, attempts)
            assertNull(thumbnails.peek(item))
            assertNotNull(thumbnails.peek(item, private = true))
            assertTrue(directory.listFiles().isNullOrEmpty())
            thumbnails.forgetPrivate()
            assertNull(thumbnails.peek(item, private = true))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun tiles_that_scroll_away_free_their_fetch_slots() {
        val directory = java.nio.file.Files.createTempDirectory("cancelled-thumbnails").toFile()
        try {
            val original = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val bytes = java.io.ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val items = (0..ThumbnailLoader.SHOWN_FETCHES).map { index ->
                fixtureItems().first().copy(id = "stuck-$index", localPath = "", remoteKey = "photo-$index", phoneId = null)
            }
            val stuck = items.dropLast(1).map { it.id }.toSet()
            val started = java.util.concurrent.atomic.AtomicInteger()
            val thumbnails = ThumbnailLoader(directory, remote = { item ->
                if (item.id in stuck) { started.incrementAndGet(); kotlinx.coroutines.awaitCancellation() }
                bytes.copyOf()
            })
            runBlocking {
                // Every shown slot hangs on a slow fetch, like a fast scroll.
                val scrolledAway = items.dropLast(1).map { item -> launch(Dispatchers.Default) { thumbnails.load(item) } }
                withTimeout(5_000) { while (started.get() < ThumbnailLoader.SHOWN_FETCHES) delay(10) }
                scrolledAway.forEach { it.cancel() }
                // The tile now on screen loads without waiting for them.
                assertNotNull(withTimeout(5_000) { thumbnails.load(items.last()) })
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun a_committed_deletion_closes_the_open_viewer() {
        val items = fixtureItems()
        val original = GalleryOutline.of(items)
        var latest by mutableStateOf(original)
        var showing by mutableStateOf(true)
        val thumbnails = ThumbnailLoader(File(System.getProperty("java.io.tmpdir"), "viewer-deletion"), { null })
        compose.setContent {
            GalleryTheme(ThemeMode.Light) {
                if (showing) MediaViewer(original, 0, thumbnails, MediaActions(),
                    onClose = { showing = false }, latestOutline = latest)
            }
        }
        compose.onNodeWithContentDescription("Back to gallery").assertExists()
        compose.runOnIdle { latest = GalleryOutline.of(items.drop(1)) }
        compose.onNodeWithContentDescription("Back to gallery").assertDoesNotExist()
    }

    @Test @Config(sdk = [34], qualifiers = "w800dp-h1100dp-xhdpi")
    fun the_grid_adapts_to_a_tablet_without_fixed_column_counts() {
        render(fixtureItems(), ThemeMode.Dark)
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
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
        compose.onNodeWithContentDescription("Drive").performClick()
        compose.onNodeWithText("Trips").performClick()
        compose.onNodeWithText("2026").performClick()
        compose.onNodeWithText("Coast").performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        if (System.getProperty("pg.screenshot.photos") != null) {
            compose.onRoot().performTouchInput { swipeDown() }
            compose.onNodeWithText("Coast").assertIsDisplayed()
        }
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
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg, not backed up").fetchSemanticsNodes().isNotEmpty() }
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

    @Test fun backups_can_go_into_albums_per_folder_and_for_everything() {
        val folders = listOf(PhoneFolder("camera", "Camera", 120uL, 7uL, true))
        val summer = FolderAlbum("album-1", "album-1", null, "Summer", 3uL, 0uL)
        val pixel = FolderAlbum("album-2", "album-2", null, "Pixel 6", 9uL, 0uL)
        val secret = FolderAlbum("album-3", "album-3", null, "Private", 2uL, 0uL, hidden = true)
        val changes = mutableListOf<String>()
        render(emptyList(), ThemeMode.Light, initialLocation = GalleryLocation(),
            backup = BackupState(folders = folders, albums = listOf(BackupAlbumTarget(null, pixel), BackupAlbumTarget("camera", secret))),
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "tester", null, false)),
            actions = BackupActions(setAlbum = { folder, album, add, existing -> changes += "$folder:${album.name}:$add:$existing" }),
            albumActions = AlbumActions(choices = listOf(summer, pixel, secret)))
        compose.onNodeWithContentDescription("Account and settings").performClick()
        compose.onNodeWithText("Backup is on. 7 waiting.").performClick()
        // A hidden album keeps its name to itself while locked.
        compose.onNodeWithText("Private").assertDoesNotExist()
        compose.onNodeWithText("Hidden album").assertExists()
        compose.onNodeWithText("Pixel 6").assertExists()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/backup-albums-light.png")

        compose.onAllNodesWithText("Another album").onFirst().performClick()
        compose.onNodeWithText("Back up “Camera” into").assertExists()
        compose.onNodeWithText("Also add the 113 items already backed up").assertExists()
        // Albums the folder goes into already, and hidden ones while locked, are not offered.
        compose.onNodeWithText("Private").assertDoesNotExist()
        compose.onNode(hasText("Summer") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Stop backing up into Pixel 6").performClick()
        assertEquals(listOf("camera:Summer:true:true", "null:Pixel 6:false:false"), changes)
    }

    @Test fun selected_photos_hide_into_the_hidden_album() {
        val items = fixtureItems().map { it.copy(inProton = true) }
        val summer = FolderAlbum("album-1", "album-1", null, "Summer", 3uL, 0uL)
        val secret = FolderAlbum("album-3", "album-3", null, "Private", 2uL, 0uL, hidden = true)
        val hidden = mutableListOf<String>()
        var asked = 0
        render(items, ThemeMode.Light, initialLocation = GalleryLocation(), photosTab = true,
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "tester", null, false)),
            albumActions = AlbumActions(choices = listOf(summer, secret),
                hide = { chosen, album, name -> hidden += "${chosen.size}:${album?.name ?: "new $name"}" }),
            privacy = PrivacyLock(available = { true }, unlock = { asked++; it(true) }))
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg").fetchSemanticsNodes().isNotEmpty() }

        // Adding to an album while locked leaves hidden albums out.
        compose.onNodeWithContentDescription("Photo: Coast 1.jpg").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Add to album").performClick()
        compose.onNode(hasText("Summer") and hasClickAction()).assertExists()
        compose.onNodeWithText("Private").assertDoesNotExist()
        compose.onNode(hasText("Summer") and hasClickAction()).performClick()
        compose.waitForIdle()

        // Hiding asks which hidden album, after the phone's lock.
        compose.onNodeWithContentDescription("Photo: Coast 1.jpg").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Photo: Coast 2.jpg").performClick()
        compose.onNodeWithContentDescription("Hide").performClick()
        compose.waitForIdle()
        assertEquals(1, asked)
        compose.onNodeWithText("Hide in").assertExists()
        compose.onNodeWithText("Summer").assertDoesNotExist()
        compose.onNode(hasText("Private") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(listOf("2:Private"), hidden)
        compose.onNodeWithText("2 selected").assertDoesNotExist()
    }

    @Test fun hiding_without_a_hidden_album_offers_to_make_one() {
        val items = fixtureItems().map { it.copy(inProton = true) }
        val hidden = mutableListOf<String>()
        var asked = 0
        render(items, ThemeMode.Light, initialLocation = GalleryLocation(), photosTab = true,
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "tester", null, false)),
            albumActions = AlbumActions(choices = listOf(FolderAlbum("album-1", "album-1", null, "Summer", 3uL, 0uL)),
                hide = { chosen, album, name -> hidden += "${chosen.size}:${album?.name ?: "new $name"}" }),
            privacy = PrivacyLock(available = { true }, unlock = { asked++; it(true) }))
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Photo: Coast 1.jpg").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Hide").performClick()
        compose.waitForIdle()
        // Nothing hidden to reveal yet, so no unlock; the name is suggested.
        assertEquals(0, asked)
        compose.onNodeWithText("Summer").assertDoesNotExist()
        compose.onNodeWithText("Create").performClick()
        compose.waitForIdle()
        assertEquals(listOf("1:new Hidden"), hidden)
    }

    @Test fun selected_photos_can_be_shared() {
        val items = fixtureItems().mapIndexed { index, item -> item.copy(phoneId = "$index", backedUp = true) }
        var shared = emptyList<GalleryItem>()
        render(items, ThemeMode.Light, initialLocation = GalleryLocation(), photosTab = true, media = MediaActions(share = { shared = it }))
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg").fetchSemanticsNodes().isNotEmpty() }
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
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg").fetchSemanticsNodes().isNotEmpty() }
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
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg", substring = true).onFirst().performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithContentDescription("Delete").assertDoesNotExist()
    }

    @Test fun the_photos_tab_lists_albums_and_adds_a_selection_to_one() {
        val items = fixtureItems().map { it.copy(inProton = true) }
        val summer = FolderAlbum("album-1", "album-1", null, "Summer", 3uL, 0uL, cover = items[4])
        val added = mutableListOf<String>()
        render(items, ThemeMode.Light, initialLocation = GalleryLocation(), photosTab = true, photoAlbums = listOf(summer),
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "tester", null, false)),
            albumActions = AlbumActions(choices = listOf(summer), add = { album, chosen -> added += "${album.name}:${chosen.size}" }))
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg").fetchSemanticsNodes().isNotEmpty() }
        // Albums have their own tab; the timeline is only photos.
        compose.onNodeWithText("New album").assertDoesNotExist()
        compose.onNodeWithContentDescription("Albums").performClick()
        compose.onNodeWithText("New album").assertExists()
        compose.onNodeWithContentDescription("Album Summer, 3 items").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/photo-albums-light.png")
        compose.onNodeWithContentDescription("Photos").performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Photo: Coast 1.jpg").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Add to album").performClick()
        compose.onNodeWithText("Add to album").assertExists()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/add-to-album-light.png")
        compose.onNode(hasText("Summer") and hasClickAction() and !hasContentDescription("Album Summer, 3 items")).performClick()
        assertEquals(listOf("Summer:1"), added)
        compose.onNodeWithText("1 selected").assertDoesNotExist()
    }

    @Test fun a_new_album_can_be_named_from_the_albums_tab() {
        val created = mutableListOf<String>()
        render(fixtureItems().map { it.copy(inProton = true) }, ThemeMode.Light, initialLocation = GalleryLocation(GallerySection.Albums), photosTab = true,
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "tester", null, false)),
            albumActions = AlbumActions(create = { name, chosen -> created += "$name:${chosen.size}" }))
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithText("New album").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("New album").performClick()
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Hiking")
        compose.onNodeWithText("Create").performClick()
        assertEquals(listOf("Hiking:0"), created)
    }

    @Test fun hidden_albums_open_only_with_the_phones_lock() {
        val secret = FolderAlbum("gallery-2", "gallery-2", null, "Private", 4uL, 0uL, hidden = true)
        var asked = 0
        var confirm = false
        var setUp = 0
        var lock = true
        render(emptyList(), ThemeMode.Dark, initialLocation = GalleryLocation(GallerySection.Drive), driveRoots = listOf(secret),
            privacy = PrivacyLock(available = { lock }, unlock = { asked++; it(confirm) }, setUp = { setUp++ }))
        compose.onNodeWithText("Trips").assertExists()
        compose.onNodeWithText("Private").assertDoesNotExist()
        compose.onNodeWithText("1 album · Unlock with your screen lock").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/hidden-locked-dark.png")

        // Cancelled or failed: still locked.
        compose.onNodeWithText("Hidden albums").performClick()
        assertEquals(1, asked)
        compose.onNodeWithText("Private").assertDoesNotExist()

        confirm = true
        compose.onNodeWithText("Hidden albums").performClick()
        compose.onNodeWithText("Private").assertExists()
        compose.onNodeWithContentDescription("Hidden", useUnmergedTree = true).assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/hidden-unlocked-dark.png")
        compose.onNodeWithText("Lock hidden albums").performClick()
        compose.onNodeWithText("Private").assertDoesNotExist()
        assertEquals(2, asked)

        // Without a screen lock there is nothing to unlock with.
        lock = false
        compose.onNodeWithText("Hidden albums").performClick()
        compose.onNodeWithText("Set up a screen lock").assertExists()
        compose.onNodeWithText("Set up").performClick()
        assertEquals(1, setUp)
        assertEquals(2, asked)
    }

    @Test fun another_app_can_pick_several_photos() {
        val items = fixtureItems().mapIndexed { index, item -> item.copy(phoneId = "$index", backedUp = true) }
        var picked = emptyList<GalleryItem>()
        render(items, ThemeMode.Light, initialLocation = GalleryLocation(), photosTab = true,
            pick = PickRequest(multiple = true, mime = "image/*"), onPick = { picked = it })
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 1.jpg").fetchSemanticsNodes().isNotEmpty() }
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
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Photo: Coast 0.jpg").performClick()
        compose.onNodeWithContentDescription("Back to gallery").assertExists()
        compose.onNodeWithText("In Proton Photos").assertExists()
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Coast 0.jpg").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Back to gallery").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("In Proton Photos").assertDoesNotExist()
        compose.onNodeWithContentDescription("Coast 0.jpg").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Back to gallery").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun the_viewer_backs_up_favorites_and_adds_to_albums() {
        val preview = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        // One photo only on this phone, then Proton photos.
        val items = fixtureItems().mapIndexed { index, item ->
            if (index == 0) item.copy(localPath = "", phoneId = "0", backedUp = false)
            else item.copy(localPath = "", remoteKey = "key-$index", inProton = true)
        }
        val uploaded = mutableListOf<String>()
        val favorites = mutableListOf<String>()
        val summer = FolderAlbum("album-1", "album-1", null, "Summer", 3uL, 0uL)
        render(items, ThemeMode.Dark, initialLocation = GalleryLocation(), photosTab = true,
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "tester", null, false)),
            media = MediaActions(largePhoto = { preview }, favorite = { chosen, on -> favorites += "${chosen.single().name}:$on" }),
            actions = BackupActions(upload = { chosen -> uploaded += chosen.map { it.name } }),
            albumActions = AlbumActions(choices = listOf(summer)))
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Photo: Coast 0.jpg", substring = true).performClick()
        compose.onNodeWithContentDescription("Add to album").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back up").performClick()
        assertEquals(listOf("Coast 0.jpg"), uploaded)
        compose.onNodeWithContentDescription("Add to favorites").performClick()
        assertEquals(listOf("Coast 0.jpg:true"), favorites)
        compose.onNodeWithContentDescription("Back to gallery").performClick()

        compose.onNodeWithContentDescription("Photo: Coast 1.jpg").performClick()
        compose.onNodeWithContentDescription("Back up").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add to album").performClick()
        compose.onNodeWithText("Add to album").assertExists()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/viewer-add-to-album-dark.png")
    }

    @Test fun the_selection_bar_shows_without_scrolling_back_up() {
        val base = fixtureItems()
        val items = (0 until 6).flatMap { copy -> base.map { it.copy(id = "${it.id}-$copy", name = "${it.name.removeSuffix(".jpg")}-$copy.jpg") } }
        render(items, ThemeMode.Light)
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 0-0.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top + 10f) }
        compose.waitForIdle()
        compose.onNodeWithText("On this device").assertIsNotDisplayed()
        val shown = compose.onAllNodes(hasContentDescription("Photo:", substring = true) and hasClickAction()).fetchSemanticsNodes()
            .first { node -> node.boundsInRoot.top > 400f }
        compose.onNode(hasContentDescription(shown.config[SemanticsProperties.ContentDescription].first())).performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test fun requested_backup_shows_progress_in_the_bar_and_account_ring() {
        val progress = BackupProgress(uploading = true, done = 1, total = 5, bytes = 50, size = 100)
        render(fixtureItems(), ThemeMode.Light, initialLocation = GalleryLocation(), photosTab = true,
            backup = BackupState(progress = progress, request = BackupRequest("Backing up", true, true)),
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "tester", null, false)))
        compose.onNodeWithText("Backing up 2 of 5").assertExists()
        compose.onNodeWithContentDescription("Account and settings").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Backing up 2 of 5"))
        // The bar and the ring both show how far the upload is.
        compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.3f, 0f..1f))).assertCountEquals(2)
        compose.onRoot().captureRoboImage("build/screenshots/activity-light.png")
    }

    @Test fun opening_photos_never_offers_refresh_while_the_outline_is_loading() {
        render(emptyList(), ThemeMode.Light, initialLocation = GalleryLocation(),
            account = AccountState(status = AccountStatus(SignInStage.CONNECTED, "tester", null, false)),
            backup = BackupState(progress = BackupProgress(checking = true)),
            load = GalleryLoad.Loading)
        compose.onNodeWithText("Loading Photos…").assertIsDisplayed()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("Comparing with Proton Photos").assertDoesNotExist()
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
        compose.onNodeWithContentDescription("Drive").performClick()
        compose.onNodeWithText("Trips").performClick()
        compose.onNodeWithText("2026").performClick()
        compose.onNodeWithText("Coast").performClick()
        compose.waitUntil(UI_TIMEOUT_MS) { compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty() }
        waitForThumbnails()
        compose.onRoot().captureRoboImage("build/screenshots/album-light.png")
        compose.onNodeWithContentDescription("Edit album").performClick()
        compose.onNodeWithText("Pin to top").assertExists()
        compose.onNodeWithText("Group by").assertDoesNotExist()
        compose.onNodeWithText("Remove gallery from this app").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/screenshots/album-options-light.png")
    }

    @Test fun a_descendant_photo_sets_the_original_album_cover() {
        val options = AlbumOptions(null, null, null, AlbumGrouping.DAY, AlbumOrder.NEWEST, false, false, emptyList())
        val chosen = mutableListOf<Pair<String?, String>>()
        render(fixtureItems(), ThemeMode.Light, options = options, initialLocation = GalleryLocation(),
            optionsAlbum = "year", onCoverChosen = { target, item -> chosen += target.album?.id to item.name })
        compose.onNodeWithContentDescription("Drive").performClick()
        compose.onNodeWithText("Trips").performClick()
        compose.onNodeWithText("2026").performClick()
        compose.onNodeWithContentDescription("Edit album").performClick()
        compose.onNodeWithText("Choose").performScrollTo().performClick()
        val stopChoosing = compose.onNodeWithContentDescription("Stop choosing a cover")
        stopChoosing.assertExists()
        compose.onNodeWithText("Coast").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(UI_TIMEOUT_MS) {
            compose.onAllNodesWithContentDescription("Photo: Coast 0.jpg").fetchSemanticsNodes().isNotEmpty()
        }
        stopChoosing.assertExists()
        compose.onNodeWithContentDescription("Photo: Coast 0.jpg").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf("year" to "Coast 0.jpg"), chosen) }
        compose.onNodeWithText("Coast").assertExists()
    }

    private fun render(items: List<GalleryItem>, mode: ThemeMode, onImport: () -> Unit = {}, fontScale: Float = 1f, initialLocation: GalleryLocation = GalleryLocation(GallerySection.Device), options: AlbumOptions? = null, onZoom: (Float) -> Unit = {},
        backup: BackupState = BackupState(), actions: BackupActions = BackupActions(), account: AccountState = AccountState(), photosTab: Boolean = false,
        media: MediaActions = MediaActions(), pick: PickRequest? = null, onPick: (List<GalleryItem>) -> Unit = {}, state: LibraryState? = null,
        photoAlbums: List<FolderAlbum> = emptyList(), albumActions: AlbumActions = AlbumActions(),
        driveRoots: List<FolderAlbum> = emptyList(), privacy: PrivacyLock = PrivacyLock(), load: GalleryLoad = GalleryLoad.Ready,
        optionsAlbum: String = "coast", onCoverChosen: (GalleryLocation, GalleryItem) -> Unit = { _, _ -> },
        places: PlacesActions = PlacesActions()) {
        val thumbnailDirectory = if (System.getProperty("pg.screenshot.photos") == null) "build/test-thumbnails-smart" else "build/screenshot-photo-thumbnails"
        val directory = File(thumbnailDirectory).apply { mkdirs() }
        val thumbnailLoader = ThumbnailLoader(directory, phone = { ThumbnailLoader.decodeImage(File(it.localPath), 384) })
        if (System.getProperty("pg.screenshot.photos") != null) {
            // Capture the cached-library state, as Drive's demo does. Album
            // navigation must not race the first JPEG decode during capture.
            runBlocking { items.forEach { requireNotNull(thumbnailLoader.load(it)) { "Cannot decode demo photo: ${it.localPath}" } } }
        }
        compose.setContent {
            var location by remember { mutableStateOf(initialLocation) }
            val root = remember { FolderAlbum("gallery", "root", null, "Trips", 0uL, 1uL) }
            val year = remember { FolderAlbum("gallery", "year", "root", "2026", 0uL, 1uL) }
            val coast = remember { FolderAlbum("gallery", "coast", "year", "Coast", items.size.toULong(), 0uL, description = options?.description, tags = options?.tags ?: emptyList()) }
            val albums = when (location.album?.id) {
                null -> if (location.section == GallerySection.Drive) listOf(root) + driveRoots else if (location.section == GallerySection.Albums) photoAlbums else emptyList()
                "root" -> listOf(year)
                "year" -> listOf(coast)
                else -> emptyList()
            }
            val visible = if (location.section == GallerySection.Device || location.album?.id == "coast" || (photosTab && location == GalleryLocation())) items else emptyList()
            val outline = remember(visible) { GalleryOutline.of(visible) }
            var theme by remember { mutableStateOf(mode) }
            var zoom by remember { mutableFloatStateOf(ZOOM_DEFAULT) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                GalleryTheme(theme) {
                    GalleryApp(state ?: LibraryState(ready = true, bytes = 12_000_000), outline,
                        remember { thumbnailLoader }, theme, zoom, onImport, { theme = it },
                        { zoom = it; onZoom(it) }, {}, {}, location, albums, { location = it },
                        options = if (location.album?.id == optionsAlbum) options else null, onCover = onCoverChosen,
                        account = account, backup = backup, backupActions = actions, media = media, pick = pick, onPick = onPick,
                        albumActions = albumActions, privacy = privacy, load = load, placesActions = places)
                }
            }
        }
    }

    private fun waitForThumbnails() {
        compose.waitUntil(15_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Preview ready")).fetchSemanticsNodes().size >= 6
        }
    }

    /** Normal tests use drawings; screenshot captures can opt into Drive's public demo photos. */
    private fun fixtureItems(): List<GalleryItem> {
        System.getProperty("pg.screenshot.photos")?.let { return photoFixtureItems(File(it)) }
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

    private fun photoFixtureItems(directory: File): List<GalleryItem> {
        val photos = directory.listFiles()?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }?.sortedBy { it.name }
            ?: error("Cannot read screenshot photo directory: $directory")
        require(photos.isNotEmpty()) { "No JPEG demo photos in $directory" }
        val output = File("build/screenshot-photo-media").apply { mkdirs() }
        return (0..23).map { index ->
            val source = photos[index % photos.size]
            val file = File(output, "coast-$index.jpg")
            source.copyTo(file, overwrite = true)
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, dimensions)
            require(dimensions.outWidth > 0 && dimensions.outHeight > 0) { "Invalid demo photo: $source" }
            GalleryItem("screenshot-photo-$index-${source.name}", "Coast $index.jpg", "image/jpeg", false,
                file.length().toULong(), 1_789_000_000L - index / 9 * 86_400, file.absolutePath,
                dimensions.outWidth.toDouble() / dimensions.outHeight)
        }
    }
}

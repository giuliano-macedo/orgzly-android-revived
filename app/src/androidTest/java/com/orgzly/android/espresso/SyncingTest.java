package com.orgzly.android.espresso;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.contrib.DrawerActions.open;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.orgzly.android.espresso.util.EspressoUtils.clickSetting;
import static com.orgzly.android.espresso.util.EspressoUtils.contextualToolbarOverflowMenu;
import static com.orgzly.android.espresso.util.EspressoUtils.onActionItemClick;
import static com.orgzly.android.espresso.util.EspressoUtils.onBook;
import static com.orgzly.android.espresso.util.EspressoUtils.onListItem;
import static com.orgzly.android.espresso.util.EspressoUtils.onNoteInBook;
import static com.orgzly.android.espresso.util.EspressoUtils.onNotesInBook;
import static com.orgzly.android.espresso.util.EspressoUtils.onSnackbar;
import static com.orgzly.android.espresso.util.EspressoUtils.recyclerViewItemCount;
import static com.orgzly.android.espresso.util.EspressoUtils.replaceTextCloseKeyboard;
import static com.orgzly.android.espresso.util.EspressoUtils.settingsSetTodoKeywords;
import static com.orgzly.android.espresso.util.EspressoUtils.sync;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;

import android.net.Uri;
import android.os.SystemClock;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;

import com.orgzly.R;
import com.orgzly.android.OrgzlyTest;
import com.orgzly.android.RetryTestRule;
import com.orgzly.android.db.entity.Repo;
import com.orgzly.android.repos.RepoType;
import com.orgzly.android.sync.BookSyncStatus;
import com.orgzly.android.sync.SyncRunner;
import com.orgzly.android.ui.main.MainActivity;

import org.json.JSONException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

@SuppressWarnings("unchecked")
public class SyncingTest extends OrgzlyTest {
    private ActivityScenario<MainActivity> scenario;

    @Rule
    public RetryTestRule mRetryTestRule = new RetryTestRule();

    @After
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        if (scenario != null) {
            scenario.close();
        }
    }

    @Test
    public void testRunSync() {
        scenario = ActivityScenario.launch(MainActivity.class);
        sync();
    }

    @Test
    public void testAutoSyncIsTriggeredAfterCreatingNote() {
        Repo repo = testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupRook(repo, "mock://repo-a/booky.org", "", "abc", 1234567890000L);
        scenario = ActivityScenario.launch(MainActivity.class);
        sync();

        // Set preference
        onActionItemClick(R.id.activity_action_settings, R.string.settings);
        clickSetting(R.string.sync);
        clickSetting(R.string.auto_sync);
        clickSetting(R.string.auto_sync);
        clickSetting(R.string.pref_title_sync_after_note_create);
        pressBack();
        pressBack();
        pressBack();

        // Open book
        onBook(0).perform(click());

        // Create note
        onView(withId(R.id.fab)).perform(click());
        onView(withId(R.id.title_edit))
                .perform(replaceTextCloseKeyboard("new note created by test"));
        onView(withId(R.id.done)).perform(click()); // Note done

        // Check it is synced
        onView(withId(R.id.drawer_layout)).perform(open());
        onView(allOf(withText(R.string.notebooks), isDescendantOfA(withId(R.id.drawer_navigation_view)))).perform(click());
        onView(withId(R.id.fragment_books_view_flipper)).check(matches(isDisplayed()));
        onView(allOf(withText("booky"), withId(R.id.item_book_title))).check(matches(isDisplayed()));
        onView(allOf(withId(R.id.item_book_sync_needed_icon))).check(matches(not(isDisplayed())));
    }

    @Test
    public void testPrefaceModificationMakesBookOutOfSync() {
        Repo repo = testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupRook(repo, "mock://repo-a/booky.org", "", "abc", 1234567890000L);
        scenario = ActivityScenario.launch(MainActivity.class);
        sync();

        onBook(0, R.id.item_book_sync_needed_icon).check(matches(not(isDisplayed())));

        // Change preface
        onBook(0).perform(click());
        onActionItemClick(R.id.books_options_menu_book_preface, R.string.edit_book_preface);
        onView(withId(R.id.fragment_book_preface_content)).perform(click());
        onView(withId(R.id.fragment_book_preface_content_edit))
                .perform(replaceTextCloseKeyboard("Modified preface"));
        onView(withId(R.id.done)).perform(click()); // Preface done
        pressBack();

        onBook(0, R.id.item_book_sync_needed_icon).check(matches(isDisplayed()));
    }

    @Test
    public void nonLinkedBookCannotBeMadeOutOfSync() {
        testUtils.setupBook("booky", "* Note A");
        scenario = ActivityScenario.launch(MainActivity.class);

        onBook(0, R.id.item_book_sync_needed_icon).check(matches(not(isDisplayed())));

        // Modify book
        onBook(0).perform(click());
        onNoteInBook(1).perform(longClick());
        onView(withId(R.id.toggle_state)).perform(click());
        pressBack();
        pressBack();

        onBook(0, R.id.item_book_sync_needed_icon).check(matches(not(isDisplayed())));
    }

    /*
     * Book is left with out-of-sync icon when it's modified, then force-loaded.
     * This is because book's mtime was not being updated and was greater then remote book's mtime.
     */
    @Test
    public void testForceLoadingAfterModification() {
        testUtils.setupBook("book-one", "* Note");
        testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        scenario = ActivityScenario.launch(MainActivity.class);

        // Force save
        onView(allOf(withText("book-one"), isDisplayed())).perform(longClick());
        onView(withId(R.id.books_context_menu_force_save)).perform(click());
        onView(withText(R.string.overwrite)).perform(click());

        // Modify book
        onBook(0).perform(click());
        onNoteInBook(1).perform(longClick());
        onView(withId(R.id.toggle_state)).perform(click());
        pressBack();
        pressBack();

        // Force load
        onView(allOf(withText("book-one"), isDisplayed())).perform(longClick());
        onView(withId(R.id.books_context_menu_force_load)).perform(click());
        onView(withText(R.string.overwrite)).perform(click());

        // Check sync icon
        onView(allOf(withId(R.id.item_book_sync_needed_icon))).check(matches(not(isDisplayed())));
    }

    @Test
    public void testForceLoadingMultipleBooks() {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupBook("book-one", "First book used for testing\n* Note A");
        testUtils.setupBook("book-two", "Second book used for testing\n* Note 1\n* Note 2");
        scenario = ActivityScenario.launch(MainActivity.class);

        sync(); // To ensure that all books have repo links
        onBook(0).perform(click());
        // Check that the content of book 1 is unchanged
        onNoteInBook(1, R.id.item_head_title_view).check(matches(withText("Note A")));
        // Modify the content of book 1
        onNoteInBook(1).perform(longClick());
        onView(withId(R.id.toggle_state)).perform(click());
        // Check that the content of book 1 is changed.
        onNoteInBook(1, R.id.item_head_title_view).check(matches(not(withText("Note A"))));
        pressBack();
        pressBack();
        // Change the content of book 2
        onBook(1).perform(click());
        onNoteInBook(1).perform(longClick());
        onView(withId(R.id.toggle_state)).perform(click());
        pressBack();
        pressBack();
        // Select both books
        onBook(0).perform(longClick());
        onBook(1).perform(click());
        onView(withId(R.id.books_context_menu_force_load)).perform(click());
        onView(withText(R.string.overwrite)).perform(click());
        // Check that the content of book 1 was restored
        onBook(0).perform(click());
        onNoteInBook(1, R.id.item_head_title_view).check(matches(withText("Note A")));
        pressBack();
        // Check that the content of book 2 was restored
        onBook(1).perform(click());
        onNoteInBook(1, R.id.item_head_title_view).check(matches(withText("Note 1")));
    }

    @Test
    public void testForceSavingMultipleBooks() {
        Repo repo = testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupRook(repo, "mock://repo-a/book-one.org", "Content from repo", "abc",
                1234567890000L);
        testUtils.setupRook(repo, "mock://repo-a/book-two.org", "Content from repo", "abc",
                1234567890000L);
        testUtils.setupBook("book-one", "First book used for testing\n* Note A", repo);
        testUtils.setupBook("book-two", "Second book used for testing\n* Note A", repo);
        scenario = ActivityScenario.launch(MainActivity.class);

        onBook(0).perform(longClick());
        onBook(1).perform(click());
        onView(withId(R.id.books_context_menu_force_save)).perform(click());
        onView(withText(R.string.overwrite)).perform(click());

        onBook(0, R.id.item_book_last_action)
                .check(matches(withText(endsWith(
                        context.getString(R.string.force_saved_to_uri,
                                "mock://repo-a/book-one.org")))));
        onBook(1, R.id.item_book_last_action)
                .check(matches(withText(endsWith(
                        context.getString(R.string.force_saved_to_uri,
                                "mock://repo-a/book-two.org")))));
        // Check that a subsequent sync changes nothing
        sync();
        onBook(0, R.id.item_book_last_action).check(matches(withText(endsWith(
                context.getString(R.string.sync_status_no_change)))));
        onBook(1, R.id.item_book_last_action).check(matches(withText(endsWith(
                context.getString(R.string.sync_status_no_change)))));
        // Check contents
        onBook(0).perform(click());
        onNoteInBook(1, R.id.item_head_title_view).check(matches(withText("Note A")));
        pressBack();
        onBook(1).perform(click());
        onNoteInBook(1, R.id.item_head_title_view).check(matches(withText("Note A")));
    }

    @Test
    public void testSyncButton() {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupBook("book-one", "First book used for testing\n* Note A");
        testUtils.setupBook("book-two", "Second book used for testing\n* Note 1\n* Note 2");
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();
    }

    @Test
    public void testSavingAndLoadingBookBySyncing() {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupBook("booky",
                "Sample book used for tests\n" +
                "* Note #1.\n" +
                "* Note #2.\n" +
                "");
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();
        onView(allOf(withText("booky"), isDisplayed())).check(matches(isDisplayed()));
        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withId(R.id.item_book_card_view)).check(matches(not(isDisplayed())));
        sync();
        onView(withId(R.id.item_book_card_view)).check(matches(isDisplayed()));
        onView(allOf(withText("booky"), isDisplayed())).check(matches(isDisplayed()));
        onBook(0).perform(click());
        onNoteInBook(2).perform(click());
        onView(withId(R.id.scroll_view)).check(matches(isDisplayed()));
    }

    @Test
    public void testBackToModifiedBookAfterSyncing() {
        Repo repo = testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");

        testUtils.setupBook("booky",
                "Sample book used for tests\n" +
                "* Note #1.\n" +
                "* Note #2.\n" +
                "** TODO Note #3.\n" +
                "** Note #4.\n" +
                "*** DONE Note #5.\n" +
                "**** Note #6.\n" +
                "** Note #7.\n" +
                "* Note #8.\n" +
                "**** Note #9.\n" +
                "** ANTIVIVISECTIONISTS Note #10.\n" +
                "** Note #11. DIE PERSER (Ü: Andreas Röhler) Schauspiel 1 D 3 H Stand:\n" +
                "");
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();
        onView(allOf(withText("booky"), isDisplayed())).perform(click());

        onView(withId(R.id.drawer_layout)).perform(open());
        onView(allOf(withText(R.string.notebooks), isDisplayed())).perform(click());

        /* Make sure book has been uploaded to repo and is linked now. */
        onBook(0, R.id.item_book_link_repo).check(matches(allOf(withText("mock://repo-a"), isDisplayed())));
        onBook(0, R.id.item_book_synced_url).check(matches(allOf(withText("mock://repo-a/booky.org"), isDisplayed())));

        /* Modify remote book directly. */
        testUtils.setupRook(repo, "mock://repo-a/booky.org", "NEW CONTENT", "abc", 1234567890000L);

        sync();

        /* Go back to book. */
        pressBack();
        onView(allOf(withId(R.id.item_preface_text_view), withText("NEW CONTENT")))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testBookParsingAfterKeywordsSettingChange() {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupBook("booky",
                "Sample book used for tests\n" +
                "* Note #1.\n" +
                "* Note #2.\n" +
                "** TODO Note #3.\n" +
                "** Note #4.\n" +
                "*** DONE Note #5.\n" +
                "**** Note #6.\n" +
                "** Note #7.\n" +
                "* Note #8.\n" +
                "**** Note #9.\n" +
                "** ANTIVIVISECTIONISTS Note #10.\n" +
                "** Note #11. DIE PERSER (Ü: Andreas Röhler) Schauspiel 1 D 3 H Stand:\n" +
                "");
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();

        onView(allOf(withText("booky"), withId(R.id.item_book_title))).perform(click());

        /* Open note "ANTIVIVISECTIONISTS Note #10." and check title. */
        onNoteInBook(10).perform(click());
        onView(withId(R.id.title_view)).check(matches(allOf(withText("ANTIVIVISECTIONISTS Note #10."), isDisplayed())));

        settingsSetTodoKeywords("ANTIVIVISECTIONISTS");

        onView(withId(R.id.drawer_layout)).perform(open());
        onView(allOf(withText(R.string.notebooks), isDisplayed())).perform(click());
        onView(withId(R.id.fragment_books_view_flipper)).check(matches(isDisplayed()));

        /* Delete book */
        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withText(R.string.delete)).perform(click());

        sync();

        onView(allOf(withText("booky"), withId(R.id.item_book_title))).perform(click());

        /* Open note "ANTIVIVISECTIONISTS Note #10." and check title. */
        onNoteInBook(10).perform(click());
        onView(withId(R.id.title_view)).check(matches(allOf(withText("Note #10."), isDisplayed())));
    }

    @Test
    public void testChangeBookLink() {
        Repo repoA = testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        Repo repoB = testUtils.setupRepo(RepoType.MOCK, "mock://repo-b");
        testUtils.setupRook(repoA, "mock://repo-a/book-1.org", "Remote content for book in repo a", "abc", 1234567890);
        testUtils.setupRook(repoB, "mock://repo-b/book-1.org", "Remote content for book in repo b", "def", 1234567891);
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();

        onBook(0, R.id.item_book_last_action)
                .check(matches(allOf(withText(containsString(BookSyncStatus.DUMMY_WITHOUT_LINK_AND_MULTIPLE_ROOKS.msg())), isDisplayed())));

        /* Set link to repo-b. */
        onView(allOf(withText("book-1"), isDisplayed())).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.books_context_menu_item_set_link)).perform(click());
        onView(withText("mock://repo-b")).perform(click());

        onBook(0, R.id.item_book_link_repo).check(matches(withText("mock://repo-b")));

        sync();

        onBook(0, R.id.item_book_last_action)
                .check(matches(allOf(withText(containsString(BookSyncStatus.DUMMY_WITH_LINK.msg())), isDisplayed())));

        onBook(0).perform(click());
        onView(withId(R.id.item_preface_text_view))
                .check(matches(withText("Remote content for book in repo b")));
        pressBack();

        /* Set link to repo-a. */
        onView(allOf(withText("book-1"), isDisplayed())).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.books_context_menu_item_set_link)).perform(click());
        onView(withText("mock://repo-a")).perform(click());

        onBook(0, R.id.item_book_link_repo).check(matches(withText("mock://repo-a")));

        sync();

        onBook(0, R.id.item_book_last_action)
                .check(matches(allOf(withText(containsString(BookSyncStatus.CONFLICT_LAST_SYNCED_ROOK_AND_LATEST_ROOK_ARE_DIFFERENT.msg())), isDisplayed())));

        /* Still the same content due to conflict. */
        onBook(0).perform(click());

        onView(allOf(withId(R.id.item_preface_text_view), withText("Remote content for book in repo b")))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testSyncTwiceInARow() {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        Repo repoB = testUtils.setupRepo(RepoType.MOCK, "mock://repo-b");
        testUtils.setupRook(repoB, "mock://repo-b/book-2.org", "Remote content for book 2", "abc", 1234567890000L);
        testUtils.setupRook(repoB, "mock://repo-b/book-3.org", "Remote content for book 3", "def", 1234567891000L);
        testUtils.setupBook("book-1", "Local content for book 1");
        testUtils.setupBook("book-2", "Local content for book 2", repoB);
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();

        onBook(0, R.id.item_book_last_action)
                .check(matches(allOf(withText(containsString(BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS.msg())), isDisplayed())));
        onBook(1, R.id.item_book_last_action)
                .check(matches(allOf(withText(containsString(BookSyncStatus.CONFLICT_BOOK_WITH_LINK_AND_ROOK_BUT_NEVER_SYNCED_BEFORE.msg())), isDisplayed())));
        onBook(2, R.id.item_book_last_action)
                .check(matches(allOf(withText(containsString(BookSyncStatus.DUMMY_WITHOUT_LINK_AND_ONE_ROOK.msg())), isDisplayed())));

        onBook(0).perform(click());
        onView(withId(R.id.item_preface_text_view))
                .check(matches(withText("Local content for book 1")));
        pressBack();
        onBook(1).perform(click());
        onView(withId(R.id.item_preface_text_view))
                .check(matches(withText("Local content for book 2")));
        pressBack();
        /* Whole notebook view is too big to fit on small devices' screen, so we get
         * "at least 90 percent of the view's area is displayed to the user"
         * when trying to click on it. Clicking on specific view inside (book name) instead.
         */
        onBook(2, R.id.item_book_title).perform(click());
        onView(withId(R.id.item_preface_text_view))
                .check(matches(withText("Remote content for book 3")));
        pressBack();

        sync();

        onBook(0, R.id.item_book_last_action)
                .check(matches(allOf(withText(containsString(BookSyncStatus.ONLY_BOOK_WITHOUT_LINK_AND_MULTIPLE_REPOS.msg())), isDisplayed())));

        onBook(1, R.id.item_book_last_action)
                .check(matches(allOf(withText(containsString(BookSyncStatus.CONFLICT_BOOK_WITH_LINK_AND_ROOK_BUT_NEVER_SYNCED_BEFORE.msg())), isDisplayed())));

        onBook(2, R.id.item_book_last_action)
                .check(matches(allOf(withText(containsString(BookSyncStatus.NO_CHANGE.msg())), isDisplayed())));

        onBook(0).perform(click());
        onView(withId(R.id.item_preface_text_view))
                .check(matches(withText("Local content for book 1")));
        pressBack();

        onBook(1).perform(click());
        onView(withId(R.id.item_preface_text_view))
                .check(matches(withText("Local content for book 2")));
        pressBack();

        onBook(2).perform(click());
        onView(withId(R.id.item_preface_text_view))
                .check(matches(withText("Remote content for book 3")));
    }

    @Test
    public void testEncodingAfterSyncSaving() {
        Repo repo = testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupRook(repo, "mock://repo-a/book-one.org", "Täht", "1abcde", 1400067156000L);
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();
        onBook(0, R.id.item_book_encoding_used)
                .check(matches((withText(context.getString(R.string.argument_used, "UTF-8")))));
        onBook(0, R.id.item_book_encoding_detected)
                .check(matches((withText(context.getString(R.string.argument_detected, "UTF-8")))));
        onBook(0, R.id.item_book_encoding_selected)
                .check(matches(not(isDisplayed())));

        sync();
        onBook(0, R.id.item_book_encoding_used)
                .check(matches((withText(context.getString(R.string.argument_used, "UTF-8")))));
        onBook(0, R.id.item_book_encoding_detected)
                .check(matches((withText(context.getString(R.string.argument_detected, "UTF-8")))));
        onBook(0, R.id.item_book_encoding_selected)
                .check(matches(not(isDisplayed())));
    }

    @Test
    public void testSettingLinkToRenamedRepo() throws JSONException {
        testUtils.dropboxTestPreflight();
        Repo repo = testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupRook(repo, "mock://repo-a/booky.org", "Täht", "1abcde", 1400067156000L);
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();
        onBook(0, R.id.item_book_link_repo)
                .check(matches(allOf(withText("mock://repo-a"), isDisplayed())));
        onBook(0, R.id.item_book_synced_url)
                .check(matches(allOf(withText("mock://repo-a/booky.org"), isDisplayed())));
        onBook(0, R.id.item_book_encoding_used)
                .check(matches((withText(context.getString(R.string.argument_used, "UTF-8")))));
        onBook(0, R.id.item_book_encoding_detected)
                .check(matches((withText(context.getString(R.string.argument_detected, "UTF-8")))));
        onBook(0, R.id.item_book_encoding_selected)
                .check(matches(not(isDisplayed())));

        /* Rename repository. */
        onActionItemClick(R.id.activity_action_settings, R.string.settings);
        clickSetting(R.string.sync);
        clickSetting(R.string.repos_preference_title);
        onListItem(0).perform(click());
        onView(withId(R.id.activity_repo_dropbox_directory)).perform(replaceTextCloseKeyboard("repo-b"));
        onView(withId(R.id.fab)).perform(click()); // Repo done
        pressBack();
        pressBack();
        pressBack();

        /* Set link to new repository. */
        onView(withId(R.id.drawer_layout)).perform(open());
        onView(allOf(withText(R.string.notebooks), isDescendantOfA(withId(R.id.drawer_navigation_view)))).perform(click());
        onView(allOf(withText("booky"), isDisplayed())).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.books_context_menu_item_set_link)).perform(click());
        onView(withText("dropbox:/repo-b")).perform(click());

        onBook(0, R.id.item_book_link_repo)
                .check(matches(allOf(withText("dropbox:/repo-b"), isDisplayed())));
        onBook(0, R.id.item_book_synced_url)
                .check(matches(not(isDisplayed())));
        onBook(0, R.id.item_book_encoding_used)
                .check(matches((withText(context.getString(R.string.argument_used, "UTF-8")))));
        onBook(0, R.id.item_book_encoding_detected)
                .check(matches((withText(context.getString(R.string.argument_detected, "UTF-8")))));
        onBook(0, R.id.item_book_encoding_selected)
                .check(matches(not(isDisplayed())));

        onView(allOf(withText("booky"), isDisplayed())).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.books_context_menu_item_set_link)).perform(click());
        onView(withText("dropbox:/repo-b")).check(matches(isDisplayed())); // Current value
    }

    @Test
    public void testRenamingReposRemovesLinksWhatUsedThem() throws JSONException {
        testUtils.dropboxTestPreflight();

        testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupRepo(RepoType.MOCK, "mock://repo-b");
        testUtils.setupBook("booky", "");
        scenario = ActivityScenario.launch(MainActivity.class);

        onView(allOf(withText("booky"), isDisplayed())).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.books_context_menu_item_set_link)).perform(click());
        onView(withText("mock://repo-a")).perform(click());

        onBook(0, R.id.item_book_link_repo).check(matches(allOf(withText("mock://repo-a"), isDisplayed())));

        /* Rename all repositories. */
        onActionItemClick(R.id.activity_action_settings, R.string.settings);
        clickSetting(R.string.sync);
        clickSetting(R.string.repos_preference_title);
        onListItem(0).perform(click());
        onView(withId(R.id.activity_repo_dropbox_directory)).perform(replaceTextCloseKeyboard("repo-1"));
        onView(withId(R.id.fab)).perform(click()); // Repo done
        onListItem(0).perform(click());
        onView(withId(R.id.activity_repo_dropbox_directory)).perform(replaceTextCloseKeyboard("repo-2"));
        onView(withId(R.id.fab)).perform(click()); // Repo done
        pressBack();
        pressBack();
        pressBack();

        onView(withId(R.id.drawer_layout)).perform(open());
        onView(allOf(withText(R.string.notebooks), isDescendantOfA(withId(R.id.drawer_navigation_view)))).perform(click());

        onBook(0, R.id.item_book_link_repo).check(matches(not(isDisplayed())));
    }

    @Test
    public void testRemovingLinkFromBook() {
        Repo repo = testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupBook("booky", "", repo);
        scenario = ActivityScenario.launch(MainActivity.class);

        onBook(0, R.id.item_book_link_repo).check(matches(allOf(withText("mock://repo-a"), isDisplayed())));

        onView(allOf(withText("booky"), isDisplayed())).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.books_context_menu_item_set_link)).perform(click());
        onView(withText(R.string.remove_notebook_link)).perform(click());

        onBook(0, R.id.item_book_link_container).check(matches(not(isDisplayed())));
    }

    @Test
    public void testSettingLinkForLoadedOrgTxtBook() {
        Repo repo = testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupRook(repo, "mock://repo-a/booky.org.txt", "", "1abcdef", 1400067155);
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();

        onBook(0, R.id.item_book_link_repo).check(matches(allOf(withText("mock://repo-a"), isDisplayed())));
        onBook(0, R.id.item_book_last_action).check(matches(withText(containsString("Loaded from mock://repo-a/booky.org.txt"))));

        onView(allOf(withText("booky"), isDisplayed())).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.books_context_menu_item_set_link)).perform(click());
        onView(withText("mock://repo-a")).perform(click());

        onBook(0, R.id.item_book_link_repo).check(matches(allOf(withText("mock://repo-a"), isDisplayed())));
        onBook(0, R.id.item_book_synced_url).check(matches(allOf(withText("mock://repo-a/booky.org.txt"), isDisplayed())));
    }

    @Test
    public void testRenameModifiedBook() {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupBook("booky", "* Note");
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();

        onBook(0).perform(click()); // Open notebook
        onNoteInBook(1).perform(click()); // Open note
        onView(withId(R.id.title)).perform(click());
        onView(withId(R.id.title_edit)).perform(replaceTextCloseKeyboard("New title"));
        onView(withId(R.id.done)).perform(click()); // Note done

        pressBack(); // Back to the list of notebooks

        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.books_context_menu_item_rename)).perform(click());
        onView(withId(R.id.name)).perform(replaceTextCloseKeyboard("book-two"));
        onView(withText(R.string.rename)).perform(click());

        String errMsg = context.getString(
                R.string.failed_renaming_book_with_reason,
                "Notebook is not synced");

        onBook(0, R.id.item_book_last_action).check(matches(withText(endsWith(errMsg))));
    }

    @Test
    public void testDeSelectRemovedNote() {
        Repo repo = testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");

        testUtils.setupRook(
                repo,
                "mock://repo-a/book-a.org",
                "* TODO Note [a-1]\n* TODO Note [a-2]",
                "1520077116000",
                1520077116000L);
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();

        onBook(0).perform(click());

        onNotesInBook().check(matches(recyclerViewItemCount(3)));

        onNoteInBook(1).perform(longClick());

        onView(allOf(instanceOf(TextView.class), withParent(withId(R.id.top_toolbar))))
                .check(matches(withText("1")));

        testUtils.setupRook(
                repo,
                "mock://repo-a/book-a.org",
                "* TODO Note [a-1]",
                "1520681916000",
                1520681916000L);

        // Sync by starting the service directly, to keep note selected
        SyncRunner.startSync();
        SystemClock.sleep(1000);

        onNotesInBook().check(matches(recyclerViewItemCount(2)));

        onView(allOf(instanceOf(TextView.class), withParent(withId(R.id.top_toolbar))))
                .check(matches(withText("book-a")));
    }

    @Test
    public void testDeleteNonExistentRemoteFile() throws IOException {
        testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupBook("booky", "Sample book used for tests");
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();

        dbRepoBookRepository.deleteBook(Uri.parse("mock://repo-a/booky.org"));

        onView(allOf(withText("booky"), isDisplayed())).check(matches(isDisplayed()));
        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withId(R.id.delete_linked_checkbox)).perform(click());
        onView(withText(R.string.delete)).perform(click());
    }

    @Test
    public void testDeleteExistingRemoteFile() {
        Repo repo = testUtils.setupRepo(RepoType.MOCK, "mock://repo-a");
        testUtils.setupBook("booky", "Sample book used for tests");
        scenario = ActivityScenario.launch(MainActivity.class);

        sync();

        onView(allOf(withText("booky"), isDisplayed())).check(matches(isDisplayed()));
        onBook(0).perform(longClick());
        contextualToolbarOverflowMenu().perform(click());
        onView(withText(R.string.delete)).perform(click());
        onView(withId(R.id.delete_linked_checkbox)).perform(click());
        onView(withText(R.string.delete)).perform(click());
        SystemClock.sleep(500);
        Assert.assertEquals(0, dbRepoBookRepository.getBooks(
                repo.getId(), Uri.parse("mock://repo-a")).size());
    }
}

package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.TermuxUltraPreferences;
import com.termux.app.activities.AboutActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Controls the tab bar shown over the top of the terminal. It replaces the former left drawer.
 *
 * The bar is revealed by swiping down from the top edge of the terminal, hidden by swiping up on it,
 * and hides itself after a delay without interaction.
 */
public class TermuxTabBarController implements TermuxTopSwipeLayout.Listener {

    private static final int ANIMATION_DURATION_MS = 180;
    private static final int AUTO_SCROLL_EDGE_DP = 56;
    private static final int AUTO_SCROLL_STEP_DP = 8;

    private final TermuxActivity mActivity;
    private final TermuxTopSwipeLayout mContainer;
    private final View mOverlay;
    private final View mTabBar;
    private final HorizontalScrollView mScroll;
    private final LinearLayout mTabs;
    private final float mDensity;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideRunnable = this::hide;

    private TermuxSearchController mSearchController;

    private boolean mShown = false;
    private boolean mTouching = false;
    private boolean mMenuOpen = false;
    private boolean mSearchMode = false;
    private boolean mDragging = false;
    private boolean mRefreshPending = false;

    /** Sessions with new output that was not seen yet. */
    private final Set<TerminalSession> mUnread = new HashSet<>();

    public TermuxTabBarController(@NonNull TermuxActivity activity) {
        mActivity = activity;
        mDensity = activity.getResources().getDisplayMetrics().density;
        mContainer = activity.findViewById(R.id.terminal_container);
        mOverlay = activity.findViewById(R.id.top_overlay);
        mTabBar = activity.findViewById(R.id.tab_bar);
        mScroll = activity.findViewById(R.id.tab_bar_scroll);
        mTabs = activity.findViewById(R.id.tab_bar_tabs);

        mContainer.setListener(this);
        mContainer.setOverlay(mOverlay);

        mOverlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!mShown) mOverlay.setTranslationY(-mOverlay.getHeight());
        });

        ImageButton addButton = activity.findViewById(R.id.tab_bar_add_button);
        addButton.setOnClickListener(v -> {
            scheduleHide();
            mActivity.getTermuxTerminalSessionClient().addNewSession(false, null);
        });
        addButton.setOnLongClickListener(v -> {
            showNamedSessionDialog();
            return true;
        });

        activity.findViewById(R.id.tab_bar_more_button).setOnClickListener(this::showMoreMenu);
    }

    public void setSearchController(TermuxSearchController searchController) {
        mSearchController = searchController;
    }

    /** Whether the swipe gestures are enabled, they are not while selecting text. */
    public void setGestureEnabled(boolean enabled) {
        mContainer.setGestureEnabled(enabled);
        if (!enabled) hide();
    }

    public boolean isShown() {
        return mShown;
    }

    // ------------------------------------------------------------------------------------------
    // Showing and hiding

    /** Reveal the tab bar, and hide it again after the configured delay. */
    public void show() {
        if (!mActivity.isVisible()) return;
        mShown = true;
        mOverlay.animate().cancel();
        mOverlay.setVisibility(View.VISIBLE);
        mOverlay.animate().translationY(0).alpha(1f).setDuration(ANIMATION_DURATION_MS).setListener(null);
        refresh();
        scheduleHide();
    }

    public void hide() {
        mHandler.removeCallbacks(mHideRunnable);
        if (!mShown || mSearchMode || mMenuOpen || mDragging) return;
        mShown = false;
        mOverlay.animate().cancel();
        mOverlay.animate().translationY(-mOverlay.getHeight()).alpha(0f).setDuration(ANIMATION_DURATION_MS)
            .withEndAction(() -> {
                if (!mShown) mOverlay.setVisibility(View.INVISIBLE);
            });
    }

    public void toggle() {
        if (mShown) {
            // Hide right away, the pending state of menus or search is not relevant to a toggle.
            mSearchMode = false;
            hide();
        } else {
            show();
        }
    }

    /** Show the bar for a few seconds, for example when the session changed. */
    public void showTemporarily() {
        if (!mShown) show();
        else scheduleHide();
    }

    /** (Re)start the countdown after which the bar hides itself. */
    private void scheduleHide() {
        mHandler.removeCallbacks(mHideRunnable);
        if (!mShown || mSearchMode || mMenuOpen || mTouching || mDragging) return;
        int delay = getHideDelayMs();
        if (delay <= 0) return; // Never hide automatically.
        mHandler.postDelayed(mHideRunnable, delay);
    }

    private int getHideDelayMs() {
        SharedPreferences preferences = TermuxUltraPreferences.get(mActivity);
        try {
            return Integer.parseInt(preferences.getString(TermuxUltraPreferences.KEY_TAB_BAR_HIDE_DELAY_MS,
                String.valueOf(TermuxUltraPreferences.DEFAULT_TAB_BAR_HIDE_DELAY_MS)));
        } catch (NumberFormatException e) {
            return TermuxUltraPreferences.DEFAULT_TAB_BAR_HIDE_DELAY_MS;
        }
    }

    /** Switch between the tab bar and the search bar, which take the same place. */
    public void setSearchMode(boolean searchMode) {
        mSearchMode = searchMode;
        mTabBar.setVisibility(searchMode ? View.GONE : View.VISIBLE);
        if (searchMode) {
            mHandler.removeCallbacks(mHideRunnable);
            mShown = true;
            mOverlay.animate().cancel();
            mOverlay.setVisibility(View.VISIBLE);
            mOverlay.setAlpha(1f);
            mOverlay.setTranslationY(0);
        } else {
            refresh();
            scheduleHide();
        }
    }

    @Override
    public void onSwipeDownFromTop() {
        show();
    }

    @Override
    public void onSwipeUpOnOverlay() {
        if (mSearchMode) return;
        hide();
    }

    @Override
    public void onOverlayTouchChanged(boolean touching) {
        mTouching = touching;
        if (touching) mHandler.removeCallbacks(mHideRunnable);
        else scheduleHide();
    }

    // ------------------------------------------------------------------------------------------
    // Tabs

    /** Mark a background session as having new output. */
    public void markUnread(TerminalSession session) {
        if (session == null || session == mActivity.getCurrentSession()) return;
        if (mUnread.add(session)) refresh();
    }

    /** Rebuild the tabs from the sessions of the service. */
    public void refresh() {
        if (mDragging) {
            mRefreshPending = true;
            return;
        }
        mRefreshPending = false;

        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TerminalSession current = mActivity.getCurrentSession();
        if (current != null) mUnread.remove(current);

        List<TermuxSession> sessions = new ArrayList<>(service.getTermuxSessions());
        mUnread.retainAll(terminalSessionsOf(sessions));

        mTabs.removeAllViews();
        View currentTab = null;
        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession session = sessions.get(i).getTerminalSession();
            if (session == null) continue;
            View tab = createTabView(i, session, session == current);
            mTabs.addView(tab);
            if (session == current) currentTab = tab;
        }

        if (currentTab != null) scrollToTab(currentTab);
    }

    private static List<TerminalSession> terminalSessionsOf(List<TermuxSession> sessions) {
        List<TerminalSession> result = new ArrayList<>();
        for (TermuxSession session : sessions) result.add(session.getTerminalSession());
        return result;
    }

    private void scrollToTab(View tab) {
        mScroll.post(() -> {
            int target = tab.getLeft() - (mScroll.getWidth() - tab.getWidth()) / 2;
            mScroll.smoothScrollTo(Math.max(0, target), 0);
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private View createTabView(int index, TerminalSession session, boolean selected) {
        LinearLayout tab = new LinearLayout(mActivity);
        tab.setOrientation(LinearLayout.HORIZONTAL);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tab.setPadding(dp(10), 0, dp(2), 0);
        tab.setBackgroundResource(selected ? R.drawable.tab_item_selected_background : R.drawable.tab_item_background);

        if (mUnread.contains(session)) {
            View dot = new View(mActivity);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(ContextCompat.getColor(mActivity, R.color.termuxultra_unread_dot));
            dot.setBackground(shape);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotParams.setMarginEnd(dp(6));
            tab.addView(dot, dotParams);
        }

        TextView title = new TextView(mActivity);
        title.setText(getTabLabel(index, session));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setMaxWidth(dp(150));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        boolean running = session.isRunning();
        title.setTextColor(running || session.getExitStatus() == 0 ? Color.WHITE : Color.RED);
        if (!running) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        tab.addView(title);

        ImageButton close = new ImageButton(mActivity);
        close.setImageResource(R.drawable.ic_tab_close);
        close.setBackgroundResource(android.R.color.transparent);
        close.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        close.setPadding(dp(9), dp(9), dp(9), dp(9));
        close.setAlpha(0.7f);
        close.setContentDescription(mActivity.getString(R.string.tab_bar_close_tab));
        close.setOnClickListener(v -> closeTab(session));
        tab.addView(close, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.MATCH_PARENT));

        tab.setOnTouchListener(new TabTouchListener(session));
        return tab;
    }

    private String getTabLabel(int index, TerminalSession session) {
        String name = session.mSessionName;
        String label = (name != null && !name.isEmpty()) ? name : session.getTitle();
        if (label == null || label.isEmpty()) label = mActivity.getString(R.string.search_unnamed_tab);
        return "[" + (index + 1) + "] " + label;
    }

    private int dp(int value) {
        return Math.round(value * mDensity);
    }

    /** Name of a session as shown in search results. */
    public String getSessionLabel(TerminalSession session) {
        TermuxService service = mActivity.getTermuxService();
        int index = service == null ? -1 : service.getIndexOfSession(session);
        String name = session.mSessionName;
        String label = (name != null && !name.isEmpty()) ? name : session.getTitle();
        if (label == null || label.isEmpty()) label = mActivity.getString(R.string.search_unnamed_tab);
        return index >= 0 ? "[" + (index + 1) + "] " + label : label;
    }

    private void closeTab(TerminalSession session) {
        scheduleHide();
        if (session.isRunning()) {
            mMenuOpen = true;
            new AlertDialog.Builder(mActivity)
                .setTitle(R.string.tab_bar_close_running_title)
                .setMessage(R.string.tab_bar_close_running_message)
                .setPositiveButton(R.string.tab_bar_close_running_confirm, (d, w) -> removeSession(session))
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    mMenuOpen = false;
                    scheduleHide();
                })
                .show();
        } else {
            removeSession(session);
        }
    }

    private void removeSession(TerminalSession session) {
        mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session);
        refresh();
    }

    // ------------------------------------------------------------------------------------------
    // Menus

    private void showTabMenu(View anchor, TerminalSession session) {
        PopupMenu menu = new PopupMenu(mActivity, anchor);
        final int renameId = 1, killId = 2, closeId = 3;
        menu.getMenu().add(0, renameId, 0, R.string.tab_bar_tab_menu_rename);
        if (session.isRunning()) menu.getMenu().add(0, killId, 1, R.string.tab_bar_tab_menu_kill);
        menu.getMenu().add(0, closeId, 2, R.string.tab_bar_tab_menu_close);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case renameId:
                    mActivity.getTermuxTerminalSessionClient().renameSession(session);
                    return true;
                case killId:
                    session.finishIfRunning();
                    refresh();
                    return true;
                case closeId:
                    closeTab(session);
                    return true;
                default:
                    return false;
            }
        });
        openMenu(menu);
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(mActivity, anchor);
        final int searchId = 1, keyboardId = 2, extraKeysId = 3, settingsId = 4, aboutId = 5;
        menu.getMenu().add(0, searchId, 0, R.string.tab_bar_menu_search);
        menu.getMenu().add(0, keyboardId, 1, R.string.tab_bar_menu_keyboard);
        menu.getMenu().add(0, extraKeysId, 2, R.string.action_toggle_extra_keys);
        menu.getMenu().add(0, settingsId, 3, R.string.tab_bar_menu_settings);
        menu.getMenu().add(0, aboutId, 4, R.string.tab_bar_menu_about);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case searchId:
                    if (mSearchController != null) mSearchController.open();
                    return true;
                case keyboardId:
                    mActivity.getTermuxTerminalViewClient().onToggleSoftKeyboardRequest();
                    return true;
                case extraKeysId:
                    mActivity.toggleTerminalToolbar();
                    return true;
                case settingsId:
                    ActivityUtils.startActivity(mActivity, new Intent(mActivity, SettingsActivity.class));
                    return true;
                case aboutId:
                    ActivityUtils.startActivity(mActivity, new Intent(mActivity, AboutActivity.class));
                    return true;
                default:
                    return false;
            }
        });
        openMenu(menu);
    }

    private void openMenu(PopupMenu menu) {
        mMenuOpen = true;
        mHandler.removeCallbacks(mHideRunnable);
        menu.setOnDismissListener(m -> {
            mMenuOpen = false;
            scheduleHide();
        });
        menu.show();
    }

    private void showNamedSessionDialog() {
        mMenuOpen = true;
        TextInputDialogUtils.textInput(mActivity, R.string.title_create_named_session, null,
            R.string.action_create_named_session_confirm,
            text -> mActivity.getTermuxTerminalSessionClient().addNewSession(false, text),
            R.string.action_new_session_failsafe,
            text -> mActivity.getTermuxTerminalSessionClient().addNewSession(true, text),
            -1, null,
            dialog -> {
                mMenuOpen = false;
                scheduleHide();
            });
    }

    // ------------------------------------------------------------------------------------------
    // Tap, long press menu and drag to reorder

    /** Handles the touches on a tab: tap to select, long press then drag to reorder, long press to open the menu. */
    private class TabTouchListener implements View.OnTouchListener {
        private final TerminalSession mSession;
        private final int mTouchSlop = ViewConfiguration.get(mActivity).getScaledTouchSlop();

        private boolean mLongPressed;
        private boolean mMoved;
        private float mDownRawX, mDownRawY;
        private float mLastRawX;
        private float mDownContentX;
        private int mDragIndex;
        private int mTargetIndex;
        private View mTabView;

        private final Runnable mLongPressRunnable = () -> {
            if (mTabView == null) return;
            mLongPressed = true;
            mDragging = true;
            mDragIndex = mTabs.indexOfChild(mTabView);
            mTargetIndex = mDragIndex;
            mDownContentX = fingerContentX(mDownRawX);
            mTabView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            mTabView.setAlpha(0.85f);
            mTabView.setElevation(dp(8));
            mScroll.requestDisallowInterceptTouchEvent(true);
        };

        private final Runnable mAutoScrollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mLongPressed) return;
                int[] location = new int[2];
                mScroll.getLocationOnScreen(location);
                float x = mLastRawX - location[0];
                int step = dp(AUTO_SCROLL_STEP_DP);
                int edge = dp(AUTO_SCROLL_EDGE_DP);
                if (x < edge) mScroll.scrollBy(-step, 0);
                else if (x > mScroll.getWidth() - edge) mScroll.scrollBy(step, 0);
                else {
                    mHandler.postDelayed(this, 16);
                    return;
                }
                updateDrag();
                mHandler.postDelayed(this, 16);
            }
        };

        TabTouchListener(TerminalSession session) {
            mSession = session;
        }

        private float fingerContentX(float rawX) {
            int[] location = new int[2];
            mScroll.getLocationOnScreen(location);
            return rawX - location[0] + mScroll.getScrollX();
        }

        private void updateDrag() {
            if (mTabView == null) return;
            float contentX = fingerContentX(mLastRawX);
            mTabView.setTranslationX(contentX - mDownContentX);

            float center = mTabView.getLeft() + mTabView.getTranslationX() + mTabView.getWidth() / 2f;
            int target = mDragIndex;
            for (int i = 0; i < mTabs.getChildCount(); i++) {
                View child = mTabs.getChildAt(i);
                if (center >= child.getLeft() && center < child.getRight()) {
                    target = i;
                    break;
                }
            }
            if (center < mTabs.getChildAt(0).getLeft()) target = 0;
            if (center >= mTabs.getChildAt(mTabs.getChildCount() - 1).getRight()) target = mTabs.getChildCount() - 1;
            mTargetIndex = target;

            int dragWidth = mTabView.getWidth();
            for (int i = 0; i < mTabs.getChildCount(); i++) {
                if (i == mDragIndex) continue;
                View child = mTabs.getChildAt(i);
                float shift = 0;
                if (mDragIndex < mTargetIndex && i > mDragIndex && i <= mTargetIndex) shift = -dragWidth;
                else if (mDragIndex > mTargetIndex && i >= mTargetIndex && i < mDragIndex) shift = dragWidth;
                if (child.getTranslationX() != shift) child.animate().translationX(shift).setDuration(120);
            }
        }

        private void resetDragViews() {
            for (int i = 0; i < mTabs.getChildCount(); i++) {
                View child = mTabs.getChildAt(i);
                child.animate().cancel();
                child.setTranslationX(0);
                child.setAlpha(1f);
                child.setElevation(0);
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mTabView = v;
                    mLongPressed = false;
                    mMoved = false;
                    mDownRawX = mLastRawX = event.getRawX();
                    mDownRawY = event.getRawY();
                    mHandler.postDelayed(mLongPressRunnable, ViewConfiguration.getLongPressTimeout());
                    return true;

                case MotionEvent.ACTION_MOVE:
                    mLastRawX = event.getRawX();
                    if (Math.abs(event.getRawX() - mDownRawX) > mTouchSlop ||
                        Math.abs(event.getRawY() - mDownRawY) > mTouchSlop) {
                        mMoved = true;
                        if (!mLongPressed) mHandler.removeCallbacks(mLongPressRunnable);
                    }
                    if (mLongPressed) {
                        updateDrag();
                        mHandler.removeCallbacks(mAutoScrollRunnable);
                        mHandler.post(mAutoScrollRunnable);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mHandler.removeCallbacks(mLongPressRunnable);
                    mHandler.removeCallbacks(mAutoScrollRunnable);
                    boolean up = event.getActionMasked() == MotionEvent.ACTION_UP;

                    if (mLongPressed) {
                        boolean dragged = mMoved;
                        int from = mDragIndex;
                        int to = mTargetIndex;
                        resetDragViews();
                        mDragging = false;
                        mLongPressed = false;

                        if (up && dragged && from != to) {
                            TermuxService service = mActivity.getTermuxService();
                            if (service != null) service.moveTermuxSession(from, to);
                            mRefreshPending = true;
                        } else if (up && !dragged) {
                            showTabMenu(v, mSession);
                        }
                        if (mRefreshPending) refresh();
                        scheduleHide();
                    } else if (up && !mMoved) {
                        mActivity.getTermuxTerminalSessionClient().setCurrentSession(mSession);
                        scheduleHide();
                    }
                    mTabView = null;
                    return true;

                default:
                    return true;
            }
        }
    }

}

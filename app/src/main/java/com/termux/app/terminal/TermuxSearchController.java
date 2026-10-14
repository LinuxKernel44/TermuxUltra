package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.data.DataUtils;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSearch;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Controls the search bar that replaces the tab bar while searching. It searches the transcript of
 * the current session, optionally the one of all sessions, or the shell history files.
 */
public class TermuxSearchController {

    /** Maximum number of results displayed in the results list. */
    private static final int MAX_LISTED_RESULTS = 1000;
    private static final long SEARCH_DEBOUNCE_MS = 150;
    private static final long REFRESH_DEBOUNCE_MS = 300;

    /** One line of the results list. */
    private static class Result {
        final String header;
        final String text;
        /** Set for matches in a session transcript. */
        final TerminalSession session;
        final TerminalSearch.Match match;
        /** Set for matches in the shell history. */
        final ShellHistory.Entry historyEntry;

        Result(String header, String text, TerminalSession session, TerminalSearch.Match match, ShellHistory.Entry historyEntry) {
            this.header = header;
            this.text = text;
            this.session = session;
            this.match = match;
            this.historyEntry = historyEntry;
        }
    }

    private final TermuxActivity mActivity;
    private final TermuxTabBarController mTabBarController;
    private final TerminalView mTerminalView;

    private final View mSearchBar;
    private final EditText mInput;
    private final TextView mCounter;
    private final View mToggleCase, mToggleRegex, mToggleAllTabs, mToggleHistory;
    private final ListView mResultsList;
    private final ResultsAdapter mAdapter = new ResultsAdapter();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSearchRunnable = () -> runSearch(true);
    private final Runnable mRefreshRunnable = () -> runSearch(false);

    private boolean mOpen = false;
    private boolean mCaseSensitive = false;
    private boolean mRegex = false;
    private boolean mAllTabs = false;
    private boolean mHistory = false;
    private boolean mResultsExpanded = true;

    private final List<TerminalSearch.Match> mMatches = new ArrayList<>();
    private int mCurrentIndex = -1;
    private final List<Result> mResults = new ArrayList<>();

    private List<ShellHistory.Entry> mHistoryCache = null;
    private boolean mHistoryLoading = false;

    public TermuxSearchController(@NonNull TermuxActivity activity, @NonNull TermuxTabBarController tabBarController) {
        mActivity = activity;
        mTabBarController = tabBarController;
        mTerminalView = activity.getTerminalView();

        mSearchBar = activity.findViewById(R.id.search_bar);
        mInput = activity.findViewById(R.id.search_input);
        mCounter = activity.findViewById(R.id.search_counter);
        mToggleCase = activity.findViewById(R.id.search_toggle_case);
        mToggleRegex = activity.findViewById(R.id.search_toggle_regex);
        mToggleAllTabs = activity.findViewById(R.id.search_toggle_all_tabs);
        mToggleHistory = activity.findViewById(R.id.search_toggle_history);
        mResultsList = activity.findViewById(R.id.search_results_list);

        mResultsList.setAdapter(mAdapter);
        mResultsList.setOnItemClickListener((parent, view, position, id) -> onResultClicked(mResults.get(position)));
        mResultsList.setOnItemLongClickListener((parent, view, position, id) -> {
            Result result = mResults.get(position);
            if (result.historyEntry == null) return false;
            ShareUtils.copyTextToClipboard(mActivity, result.historyEntry.command);
            mActivity.showToast(mActivity.getString(R.string.search_history_copied), false);
            return true;
        });

        mInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                mHandler.removeCallbacks(mSearchRunnable);
                mHandler.postDelayed(mSearchRunnable, SEARCH_DEBOUNCE_MS);
            }
        });
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                if (mHistory || mAllTabs) setResultsExpanded(true);
                else moveToMatch(true);
                return true;
            }
            return false;
        });
        mInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.getAction() == KeyEvent.ACTION_DOWN) {
                close();
                return true;
            }
            return false;
        });

        activity.findViewById(R.id.search_previous).setOnClickListener(v -> moveToMatch(false));
        activity.findViewById(R.id.search_next).setOnClickListener(v -> moveToMatch(true));
        activity.findViewById(R.id.search_close).setOnClickListener(v -> close());
        mCounter.setOnClickListener(v -> {
            if ((mHistory || mAllTabs) && !mResults.isEmpty()) setResultsExpanded(!mResultsExpanded);
        });

        mToggleCase.setOnClickListener(v -> {
            mCaseSensitive = !mCaseSensitive;
            updateToggles();
            runSearch(true);
        });
        mToggleRegex.setOnClickListener(v -> {
            mRegex = !mRegex;
            updateToggles();
            runSearch(true);
        });
        mToggleAllTabs.setOnClickListener(v -> {
            mAllTabs = !mAllTabs;
            if (mAllTabs) mHistory = false;
            updateToggles();
            mResultsExpanded = true;
            runSearch(true);
        });
        mToggleHistory.setOnClickListener(v -> {
            mHistory = !mHistory;
            if (mHistory) mAllTabs = false;
            updateToggles();
            mResultsExpanded = true;
            runSearch(true);
        });
        updateToggles();

        // Matches are located by row, so they have to be found again when the screen size changes.
        mTerminalView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (mOpen && !mHistory && (r - l != or - ol || b - t != ob - ot)) scheduleRefresh();
        });
    }

    public boolean isOpen() {
        return mOpen;
    }

    // ------------------------------------------------------------------------------------------
    // Open and close

    public void open() {
        if (mOpen) {
            focusInput();
            return;
        }
        mOpen = true;
        mTabBarController.setSearchMode(true);
        mSearchBar.setVisibility(View.VISIBLE);
        mResultsExpanded = true;
        focusInput();
        runSearch(true);
    }

    public void close() {
        if (!mOpen) return;
        mOpen = false;
        mHandler.removeCallbacks(mSearchRunnable);
        mHandler.removeCallbacks(mRefreshRunnable);
        mTerminalView.setSearchHighlights(null, null);
        mMatches.clear();
        mResults.clear();
        mCurrentIndex = -1;
        mAdapter.notifyDataSetChanged();
        mResultsList.setVisibility(View.GONE);
        mSearchBar.setVisibility(View.GONE);
        mTabBarController.setSearchMode(false);
        mTerminalView.requestFocus();
    }

    private void focusInput() {
        mInput.requestFocus();
        // Replace the previous query when typing.
        mInput.selectAll();
        // Wait for the popup menu or dialog that triggered the search to be gone, otherwise the
        // window does not have the focus yet and the keyboard is not shown.
        mInput.postDelayed(() -> {
            if (!mOpen) return;
            InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(mInput, 0);
        }, 250);
    }

    private void updateToggles() {
        mToggleCase.setSelected(mCaseSensitive);
        mToggleRegex.setSelected(mRegex);
        mToggleAllTabs.setSelected(mAllTabs);
        mToggleHistory.setSelected(mHistory);
    }

    // ------------------------------------------------------------------------------------------
    // Events from the terminal

    /** The text of a session changed. */
    public void onTerminalTextChanged(TerminalSession session) {
        if (!mOpen || mHistory) return;
        if (session == mActivity.getCurrentSession() || mAllTabs) scheduleRefresh();
    }

    /** Another session is now displayed. */
    public void onCurrentSessionChanged() {
        if (!mOpen || mHistory) return;
        mHandler.removeCallbacks(mRefreshRunnable);
        runSearch(false);
    }

    private void scheduleRefresh() {
        mHandler.removeCallbacks(mRefreshRunnable);
        mHandler.postDelayed(mRefreshRunnable, REFRESH_DEBOUNCE_MS);
    }

    // ------------------------------------------------------------------------------------------
    // Searching

    private void clearResults() {
        mMatches.clear();
        mResults.clear();
        mCurrentIndex = -1;
        mTerminalView.setSearchHighlights(null, null);
        mAdapter.notifyDataSetChanged();
        mResultsList.setVisibility(View.GONE);
    }

    /**
     * @param jump Whether to scroll to a match, true after the user changed the query or an option,
     *             false when only refreshing after the terminal changed.
     */
    private void runSearch(boolean jump) {
        if (!mOpen) return;

        final String query = mInput.getText().toString();
        mInput.setTextColor(Color.WHITE);
        if (query.isEmpty()) {
            clearResults();
            mCounter.setText("");
            return;
        }

        final Pattern pattern;
        try {
            pattern = TerminalSearch.compile(query, mRegex, mCaseSensitive);
        } catch (PatternSyntaxException e) {
            clearResults();
            mInput.setTextColor(Color.parseColor("#FF5252"));
            mCounter.setText(R.string.search_invalid_regex);
            return;
        }

        if (mHistory) {
            searchHistory(pattern);
            return;
        }

        // Remember the current match so it stays selected when the results are refreshed.
        TerminalSearch.Match previous = (mCurrentIndex >= 0 && mCurrentIndex < mMatches.size()) ? mMatches.get(mCurrentIndex) : null;

        TerminalSession session = mActivity.getCurrentSession();
        List<TerminalSearch.Match> matches = searchSession(session, pattern);
        mMatches.clear();
        mMatches.addAll(matches);

        if (mMatches.isEmpty()) {
            mCurrentIndex = -1;
        } else if (jump || previous == null) {
            mCurrentIndex = mMatches.size() - 1; // Start from the most recent match.
        } else {
            mCurrentIndex = indexOfSimilar(previous);
        }

        buildResultsForTabs(pattern);
        updateHighlights();
        updateCounter();
        if (jump && mCurrentIndex >= 0) mTerminalView.scrollToRow(mMatches.get(mCurrentIndex).row);
    }

    private List<TerminalSearch.Match> searchSession(TerminalSession session, Pattern pattern) {
        if (session == null) return new ArrayList<>();
        TerminalEmulator emulator = session.getEmulator();
        if (emulator == null) return new ArrayList<>();
        return TerminalSearch.search(emulator.getScreen(), pattern);
    }

    private int indexOfSimilar(TerminalSearch.Match previous) {
        for (int i = 0; i < mMatches.size(); i++) {
            TerminalSearch.Match match = mMatches.get(i);
            if (match.lineNumber == previous.lineNumber && match.segments[1] == previous.segments[1]) return i;
        }
        // The transcript scrolled: keep the position relative to the end.
        return Math.min(mCurrentIndex, mMatches.size() - 1);
    }

    private void buildResultsForTabs(Pattern pattern) {
        mResults.clear();
        if (mAllTabs) {
            TermuxService service = mActivity.getTermuxService();
            if (service != null) {
                for (TermuxSession termuxSession : new ArrayList<>(service.getTermuxSessions())) {
                    TerminalSession session = termuxSession.getTerminalSession();
                    if (session == null) continue;
                    List<TerminalSearch.Match> matches = session == mActivity.getCurrentSession() ?
                        mMatches : searchSession(session, pattern);
                    String label = mTabBarController.getSessionLabel(session);
                    for (TerminalSearch.Match match : matches) {
                        if (mResults.size() >= MAX_LISTED_RESULTS) break;
                        mResults.add(new Result(
                            mActivity.getString(R.string.search_result_header_tab,
                                Math.max(1, service.getIndexOfSession(session) + 1), stripIndex(label), match.lineNumber),
                            match.preview, session, match, null));
                    }
                }
            }
        }
        refreshResultsList();
    }

    /** The label looks like "[2] name", only keep the name. */
    private static String stripIndex(String label) {
        int end = label.indexOf("] ");
        return label.startsWith("[") && end > 0 ? label.substring(end + 2) : label;
    }

    private void searchHistory(Pattern pattern) {
        mMatches.clear();
        mCurrentIndex = -1;
        mTerminalView.setSearchHighlights(null, null);

        if (mHistoryCache == null) {
            mResults.clear();
            mAdapter.notifyDataSetChanged();
            mResultsList.setVisibility(View.GONE);
            mCounter.setText("…");
            if (!mHistoryLoading) {
                mHistoryLoading = true;
                new Thread(() -> {
                    List<ShellHistory.Entry> loaded = ShellHistory.load();
                    mHandler.post(() -> {
                        mHistoryCache = loaded;
                        mHistoryLoading = false;
                        if (mOpen && mHistory) runSearch(false);
                    });
                }).start();
            }
            return;
        }

        mResults.clear();
        int total = 0;
        for (ShellHistory.Entry entry : mHistoryCache) {
            Matcher matcher = pattern.matcher(entry.command);
            if (!matcher.find()) continue;
            total++;
            if (mResults.size() < MAX_LISTED_RESULTS) {
                mResults.add(new Result(mActivity.getString(R.string.search_result_header_history, entry.shell),
                    entry.command, null, null, entry));
            }
        }
        refreshResultsList();
        if (mHistoryCache.isEmpty()) mCounter.setText(R.string.search_history_empty);
        else mCounter.setText(total == 0 ? mActivity.getString(R.string.search_no_results) : String.valueOf(total));
    }

    private void refreshResultsList() {
        mAdapter.notifyDataSetChanged();
        boolean show = !mResults.isEmpty() && (mAllTabs || mHistory) && mResultsExpanded;
        applyResultsVisibility(show);
    }

    private void setResultsExpanded(boolean expanded) {
        mResultsExpanded = expanded;
        refreshResultsList();
    }

    private void applyResultsVisibility(boolean show) {
        ViewGroup.LayoutParams params = mResultsList.getLayoutParams();
        // The list covers at most 45% of the terminal height.
        View container = mActivity.findViewById(R.id.terminal_container);
        int maxHeight = (int) (container.getHeight() * 0.45f);
        params.height = Math.max(maxHeight, 1);
        mResultsList.setLayoutParams(params);
        mResultsList.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------------------------------
    // Navigating the results

    private void moveToMatch(boolean forward) {
        if (mMatches.isEmpty()) return;
        // Terminal output may have changed since the matches were computed.
        if (mCurrentIndex < 0) mCurrentIndex = forward ? 0 : mMatches.size() - 1;
        else mCurrentIndex = (mCurrentIndex + (forward ? 1 : -1) + mMatches.size()) % mMatches.size();
        updateHighlights();
        updateCounter();
        mTerminalView.scrollToRow(mMatches.get(mCurrentIndex).row);
    }

    private void updateHighlights() {
        int size = 0;
        for (TerminalSearch.Match match : mMatches) size += match.segments.length;
        int[] all = new int[size];
        int offset = 0;
        for (TerminalSearch.Match match : mMatches) {
            System.arraycopy(match.segments, 0, all, offset, match.segments.length);
            offset += match.segments.length;
        }
        int[] current = (mCurrentIndex >= 0 && mCurrentIndex < mMatches.size()) ? mMatches.get(mCurrentIndex).segments : null;
        mTerminalView.setSearchHighlights(all, current);
    }

    private void updateCounter() {
        if (mMatches.isEmpty()) {
            mCounter.setText(mAllTabs && !mResults.isEmpty() ? String.valueOf(mResults.size()) : mActivity.getString(R.string.search_no_results));
        } else {
            mCounter.setText(mActivity.getString(R.string.search_counter, mCurrentIndex + 1, mMatches.size()));
        }
    }

    private void onResultClicked(Result result) {
        if (result.historyEntry != null) {
            insertCommand(result.historyEntry.command);
            return;
        }

        TerminalSession session = result.session;
        if (session != mActivity.getCurrentSession())
            mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);

        // Compute the matches of the session that is now displayed, and select the one that was clicked.
        Pattern pattern;
        try {
            pattern = TerminalSearch.compile(mInput.getText().toString(), mRegex, mCaseSensitive);
        } catch (PatternSyntaxException e) {
            return;
        }
        mMatches.clear();
        mMatches.addAll(searchSession(session, pattern));
        mCurrentIndex = -1;
        for (int i = 0; i < mMatches.size(); i++) {
            TerminalSearch.Match match = mMatches.get(i);
            if (match.lineNumber == result.match.lineNumber && match.segments[1] == result.match.segments[1]) {
                mCurrentIndex = i;
                break;
            }
        }
        if (mCurrentIndex < 0 && !mMatches.isEmpty()) mCurrentIndex = mMatches.size() - 1;

        updateHighlights();
        updateCounter();
        if (mCurrentIndex >= 0) mTerminalView.scrollToRow(mMatches.get(mCurrentIndex).row);
        setResultsExpanded(false);
    }

    /** Put a command of the shell history at the prompt of the current session without running it. */
    private void insertCommand(String command) {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;
        if (DataUtils.isNullOrEmpty(command)) return;

        if (command.contains("\n")) {
            // A multi-line command would run its first lines as soon as it is typed.
            ShareUtils.copyTextToClipboard(mActivity, command);
            mActivity.showToast(mActivity.getString(R.string.search_history_copied), false);
            return;
        }

        byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
        session.write(bytes, 0, bytes.length);
        mActivity.showToast(mActivity.getString(R.string.search_history_inserted), false);
        close();
    }

    // ------------------------------------------------------------------------------------------

    private class ResultsAdapter extends BaseAdapter {
        @Override public int getCount() { return mResults.size(); }
        @Override public Object getItem(int position) { return mResults.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null)
                view = LayoutInflater.from(mActivity).inflate(R.layout.item_search_result, parent, false);
            Result result = mResults.get(position);
            ((TextView) view.findViewById(R.id.search_result_header)).setText(result.header);
            ((TextView) view.findViewById(R.id.search_result_text)).setText(result.text);
            return view;
        }
    }

}

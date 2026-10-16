package com.termux.app.terminal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A {@link FrameLayout} that detects a vertical swipe starting in a zone at its top, a swipe down
 * to reveal the tab bar and a swipe up to hide it again. All other touches are left untouched so
 * scrolling and selecting in the terminal keep working.
 */
public class TermuxTopSwipeLayout extends FrameLayout {

    public interface Listener {
        /** A swipe down that started in the top zone. */
        void onSwipeDownFromTop();

        /** An upward swipe that started in the top zone, or on the overlay. */
        void onSwipeUpToHide();

        /** Whether a swipe down in the top zone should be taken, which is when the overlay is hidden. */
        boolean canSwipeDownToShow();

        /** Whether a swipe up in the top zone should be taken, which is when the overlay is shown. */
        boolean canSwipeUpToHide();

        /** A finger started or stopped touching the overlay. */
        void onOverlayTouchChanged(boolean touching);
    }

    /** Provides the height in pixels of the zone at the top where the swipes start. */
    public interface ZoneProvider {
        int getTopZonePx();
    }

    @Nullable private Listener mListener;
    @Nullable private View mOverlay;
    private boolean mGestureEnabled = true;

    private final int mTouchSlop;
    @Nullable private ZoneProvider mZoneProvider;
    private int mTopEdgePx;

    private boolean mTracking;
    private boolean mStartedOnOverlay;
    private boolean mIntercepting;
    private float mDownX, mDownY;

    public TermuxTopSwipeLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mTopEdgePx = Math.round(48 * context.getResources().getDisplayMetrics().density);
    }

    /** Set where the height of the top zone is read, each time a touch starts. */
    public void setZoneProvider(@Nullable ZoneProvider provider) {
        mZoneProvider = provider;
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    /** The view that is revealed by the swipe down. */
    public void setOverlay(@Nullable View overlay) {
        mOverlay = overlay;
    }

    /** Disable the gestures, for example while text is being selected. */
    public void setGestureEnabled(boolean enabled) {
        mGestureEnabled = enabled;
    }

    private boolean isOverlayShownAt(float x, float y) {
        if (mOverlay == null || mOverlay.getVisibility() != VISIBLE || mOverlay.getAlpha() < 0.5f) return false;
        return x >= mOverlay.getLeft() && x <= mOverlay.getRight() &&
            y >= mOverlay.getTop() + mOverlay.getTranslationY() &&
            y <= mOverlay.getBottom() + mOverlay.getTranslationY();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mListener != null) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && isOverlayShownAt(ev.getX(), ev.getY())) {
                mStartedOnOverlay = true;
            }
            if (mStartedOnOverlay) {
                final boolean ending = ev.getActionMasked() == MotionEvent.ACTION_UP ||
                    ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
                mListener.onOverlayTouchChanged(!ending);
                if (ending) mStartedOnOverlay = false;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!mGestureEnabled || mListener == null) return false;

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mIntercepting = false;
                if (mZoneProvider != null) mTopEdgePx = mZoneProvider.getTopZonePx();
                mTracking = mStartedOnOverlay || ev.getY() <= mTopEdgePx;
                mDownX = ev.getX();
                mDownY = ev.getY();
                return false;

            case MotionEvent.ACTION_MOVE:
                if (!mTracking || mIntercepting) return mIntercepting;
                final float dx = ev.getX() - mDownX;
                final float dy = ev.getY() - mDownY;
                if (Math.abs(dy) > mTouchSlop * 2 && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                    if (dy < 0 && mListener.canSwipeUpToHide()) {
                        mIntercepting = true;
                        mListener.onSwipeUpToHide();
                    } else if (dy > 0 && !mStartedOnOverlay && mListener.canSwipeDownToShow()) {
                        mIntercepting = true;
                        mListener.onSwipeDownFromTop();
                    } else {
                        mTracking = false;
                    }
                }
                return mIntercepting;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTracking = false;
                mIntercepting = false;
                return false;

            default:
                return mIntercepting;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Once intercepted, swallow the rest of the gesture.
        if (mIntercepting) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                mIntercepting = false;
                mTracking = false;
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

}

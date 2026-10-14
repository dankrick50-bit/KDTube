/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tweaks;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;

class ControlBar extends LinearLayout {

    public interface OnChildFocusedListener {

        public void onChildFocusedListener(View child, View focused);
    }

    private static final float MIN_BUTTON_SCALE = 0.6f;
    private int mChildMarginFromCenter;
    private OnChildFocusedListener mOnChildFocusedListener;
    // Can't set to static. Because we have two control bars.
    private int mLastFocusIndex = -1;
    // MOD: Sync focus index between rows
    private static int sSharedFocusIndex = -1;
    private boolean mIsSharedFocusEnabled = true;
    private boolean mDefaultFocusToMiddle = true;
    private boolean mFocusRecovery = true;

    public ControlBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        resetFocus(); // MOD: reset global focus index
    }

    public ControlBar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        resetFocus(); // MOD: reset global focus index
    }

    void setDefaultFocusToMiddle(boolean defaultFocusToMiddle) {
        mDefaultFocusToMiddle = defaultFocusToMiddle;
    }

    /**
     * MOD: enable/disable focus restoration
     */
    void setFocusRecovery(boolean focusRecovery) {
        mFocusRecovery = focusRecovery;
    }

    /**
     * MOD: synced navigation between rows
     */
    void setSharedFocusEnabled(boolean enabled) {
        mIsSharedFocusEnabled = enabled;
    }

    void resetFocus() {
        setFocusIndex(-1);
    }

    int getDefaultFocusIndex() {
        return mDefaultFocusToMiddle ? getChildCount() / 2 : 0;
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        if (getChildCount() > 0) {
            // MOD: fix situation when rows have different length
            if (getFocusIndex() >= getChildCount()) {
                setFocusIndex(getChildCount() - 1);
            }

            int index = getFocusIndex() >= 0 && getFocusIndex() < getChildCount()
                    ? getFocusIndex() : getDefaultFocusIndex();

            if (getChildAt(index).requestFocus(direction, previouslyFocusedRect)) {
                return true;
            }
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if ((direction == ViewGroup.FOCUS_UP || direction == ViewGroup.FOCUS_DOWN)) {
            if (getFocusIndex() >= 0 && getFocusIndex() < getChildCount()) {
                views.add(getChildAt(getFocusIndex()));
            } else if (getChildCount() > 0) {
                views.add(getChildAt(getDefaultFocusIndex()));
            }
        } else {
            super.addFocusables(views, direction, focusableMode);
        }
    }

    public void setOnChildFocusedListener(OnChildFocusedListener listener) {
        mOnChildFocusedListener = listener;
    }

    public void setChildMarginFromCenter(int marginFromCenter) {
        mChildMarginFromCenter = marginFromCenter;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (mFocusRecovery) {
            setFocusIndex(indexOfChild(child));
        }
        if (mOnChildFocusedListener != null) {
            mOnChildFocusedListener.onChildFocusedListener(child, focused);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mChildMarginFromCenter <= 0) {
            return;
        }

        // MOD: the row fits the screen. With many buttons the distance from center to center is made
        // smaller, and when even touching buttons won't fit, they are drawn smaller (not below 60%).
        int marginFromCenter = mChildMarginFromCenter;
        float scale = 1f;
        int count = getChildCount();
        if (count > 1) {
            int childWidth = getChildAt(0).getMeasuredWidth();
            int fitting = (getAvailableWidth(widthMeasureSpec) - childWidth) / (count - 1);
            if (fitting < marginFromCenter) {
                marginFromCenter = Math.max(fitting, 1);
                if (childWidth > 0 && marginFromCenter < childWidth) {
                    scale = Math.max(MIN_BUTTON_SCALE, (float) marginFromCenter / childWidth);
                    marginFromCenter = Math.max(marginFromCenter, Math.round(childWidth * scale));
                }
            }
        }
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            child.setScaleX(scale);
            child.setScaleY(scale);
        }

        int totalExtraMargin = 0;
        for (int i = 0; i < getChildCount() - 1; i++) {
            View first = getChildAt(i);
            View second = getChildAt(i+1);
            int measuredWidth = first.getMeasuredWidth() + second.getMeasuredWidth();
            int marginStart = marginFromCenter - measuredWidth / 2;
            LayoutParams lp = (LayoutParams) second.getLayoutParams();
            int extraMargin = marginStart - lp.getMarginStart();
            lp.setMarginStart(marginStart);
            second.setLayoutParams(lp);
            totalExtraMargin += extraMargin;
        }
        setMeasuredDimension(getMeasuredWidth() + totalExtraMargin, getMeasuredHeight());
    }

    /**
     * The width the row may take: what the parent offers, or the screen when the parent doesn't limit it
     */
    private int getAvailableWidth(int widthMeasureSpec) {
        int screen = getResources().getDisplayMetrics().widthPixels;
        int offered = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED ? MeasureSpec.getSize(widthMeasureSpec) : 0;
        int available = offered > 0 ? Math.min(offered, screen) : screen;
        return Math.max(available - getPaddingLeft() - getPaddingRight(), 1);
    }

    private void setFocusIndex(int index) {
        mLastFocusIndex = index;
        if (index != -1) // don't reset global focus between player launches
            sSharedFocusIndex = index;
    }

    private int getFocusIndex() {
        return mIsSharedFocusEnabled ? sSharedFocusIndex : mLastFocusIndex;
    }
}

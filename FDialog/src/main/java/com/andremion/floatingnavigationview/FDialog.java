/*
 * Copyright (c) 2016. André Mion
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.andremion.floatingnavigationview;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.os.ParcelableCompat;
import android.support.v4.os.ParcelableCompatCreatorCallbacks;
import android.support.v4.view.MotionEventCompat;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.AbsSavedState;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.RelativeLayout;

@SuppressLint({"InflateParams", "PrivateResource", "RtlHardcoded"})
public class FDialog extends FloatingActionButton {
    private Context mContext;

    private final WindowManager mWindowManager;

    private final View mMaskView;

    private final RelativeLayout dialogParentView;

    private View mDialog;

    /**
     * 标记是否支持点击dialog外部进行关闭
     */
    private boolean mCancelable;

    /**
     * 记录FloatingActionButton边界数据
     */
    private final Rect mFabRect = new Rect();
    /**
     * 记录Dialog边界数据
     */
    private Rect mDialogRect;

    private final int screenWidth;
    private final int screenHeight;

    /**
     * FAB移动动画执行时间（毫秒）
     */
    private int fabMoveDuration = 150;

    /**
     * dialog展示/关闭动画执行时间（毫秒）
     */
    private int dialogShowDuration = 200;

    private boolean isDisplayComplete = false;

    public FDialog(Context context) {
        this(context, null);
    }

    public FDialog(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FDialog(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;

        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = mWindowManager.getDefaultDisplay();
        screenWidth = display.getWidth();  // deprecated
        screenHeight = display.getHeight();  // deprecated

        mMaskView = LayoutInflater.from(context).inflate(R.layout.f_dialog_view, null);
        dialogParentView = (RelativeLayout) mMaskView.findViewById(R.id.dialogParentView);

        // attr
//        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.FDialog, defStyleAttr, 0);
//        if (a.hasValue(R.styleable.FDialog_fd_mask_bg_color)) {
//            mMaskView.setBackgroundColor(a.getColor(R.styleable.FDialog_fd_mask_bg_color, DEFAULT_MASK_BG_COLOR));
//        }
//        a.recycle();
    }

    @NonNull
    private static WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
//                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);
        //设置FLAG_LAYOUT_IN_SCREEN可能导致5.0以下版本关闭软键盘时Window位置无法复原
        layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        /* All dialogs should have the window dimmed -- 背景蒙版 */
        layoutParams.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        layoutParams.dimAmount = 0.5f; //Dialog默认取值

        layoutParams.softInputMode |= WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
        return layoutParams;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        detachView(); // Prevent Leaked Window when configuration changes
    }

    public void open() {
        if (mDialog == null) {
            throw new RuntimeException("Don't forget setDialogView!");
        }

        if (isOpened()) {
            return;
        }

        attachView();

        mDialog.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (mDialog.getWidth() == 0 || mDialog.getHeight() == 0) {
                    setDefaultWidth();
                } else {
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                        mDialog.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    } else {
                        mDialog.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                    }
                    updateFabRect();
                    updateDialogRect();
                    startOpenAnimations();
                }
            }
        });
    }

    public void close() {
        if (!isOpened()) {
            return;
        }
        startCloseAnimations();
    }

    public boolean isOpened() {
        return mMaskView.getParent() != null;
    }

    /**
     * 添加视图
     */
    private void attachView() {
        mWindowManager.addView(mMaskView, createLayoutParams());
    }

    /**
     * 移除视图
     */
    private void detachView() {
        if (isOpened()) {
            mWindowManager.removeViewImmediate(mMaskView);
        }
    }

    private void startOpenAnimations() {
        //move to center
        Animator moveFabToCenterX = ObjectAnimator.ofFloat(this, "translationX", (screenWidth / 2 - mFabRect.centerX())).setDuration(fabMoveDuration);
        Animator moveFabToCenterY = ObjectAnimator.ofFloat(this, "translationY", (screenHeight / 2 - mFabRect.centerY())).setDuration(fabMoveDuration);
        moveFabToCenterY.addListener(moveFabToCAnimatorListener);

        // Fade in
        mDialog.setAlpha(0);
        Animator fadeIn = ObjectAnimator.ofFloat(mDialog, "alpha", 0.5f, 1);
        fadeIn.addListener(openDialogAnimatorListener);

        // Reveal
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int centerX = mDialogRect.width() / 2;
            int centerY = mDialogRect.height() / 2;
            float startRadius = getMinRadius();
            float endRadius = getMaxRadius();

            Animator reveal = ViewAnimationUtils.createCircularReveal(mDialog,
                    centerX, centerY, startRadius, endRadius).setDuration(dialogShowDuration);
            // Animations
            AnimatorSet set = new AnimatorSet();
            set.play(moveFabToCenterX).with(moveFabToCenterY).before(reveal);
            set.play(reveal).with(fadeIn);
            set.start();
        } else {
            float mDialogSize = mDialogRect.width() < mDialogRect.height() ? mDialogRect.width() : mDialogRect.height();
            float scaleSize = mFabRect.width() / mDialogSize;
            Animator scaleX = ObjectAnimator.ofFloat(mDialog, "scaleX", scaleSize, 1.0f).setDuration((int) (dialogShowDuration * 0.8));
            Animator scaleY = ObjectAnimator.ofFloat(mDialog, "scaleY", scaleSize, 1.0f).setDuration((int) (dialogShowDuration * 0.8));
            // Animations
            AnimatorSet set = new AnimatorSet();
            set.play(moveFabToCenterX).with(moveFabToCenterY).before(scaleX);
            set.play(scaleX).with(scaleY).with(fadeIn);
            set.start();
        }

    }

    private void startCloseAnimations() {
        this.setVisibility(View.VISIBLE);

        updateFabRect();

        //move fab back
        ObjectAnimator moveFabBackX = ObjectAnimator.ofFloat(this, "translationX", (screenWidth / 2 - mFabRect.centerX())).setDuration(fabMoveDuration);
        ObjectAnimator moveFabBackY = ObjectAnimator.ofFloat(this, "translationY", (screenHeight / 2 - mFabRect.centerY())).setDuration(fabMoveDuration);

        //fadeout
        Animator fadeout = ObjectAnimator.ofFloat(mDialog, "alpha", 1.0f, 0.5f);

        // Unreveal
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int centerX = mDialogRect.width() / 2;
            int centerY = mDialogRect.height() / 2;
            float startRadius = getMaxRadius();
            float endRadius = getMinRadius();
            Animator reveal = ViewAnimationUtils.createCircularReveal(mDialog,
                    centerX, centerY, startRadius, endRadius).setDuration(dialogShowDuration);
            reveal.addListener(closeDialogAnimatorListener);
            // Animations
            AnimatorSet set = new AnimatorSet();
            set.play(moveFabBackX).with(moveFabBackY).after(reveal);
            set.play(reveal).with(fadeout);
            set.start();
        } else {
            float mDialogSize = mDialogRect.width() < mDialogRect.height() ? mDialogRect.width() : mDialogRect.height();
            float scaleSize = mFabRect.width() / mDialogSize;
            Animator scaleX = ObjectAnimator.ofFloat(mDialog, "scaleX", 1.0f, scaleSize).setDuration((int) (dialogShowDuration * 0.8));
            Animator scaleY = ObjectAnimator.ofFloat(mDialog, "scaleY", 1.0f, scaleSize).setDuration((int) (dialogShowDuration * 0.8));
            scaleY.addListener(closeDialogAnimatorListener);
            // Animations
            AnimatorSet set = new AnimatorSet();
            set.play(moveFabBackX).with(moveFabBackY).after(scaleX);
            set.play(scaleX).with(scaleY).with(fadeout);
            set.start();
        }
    }

    /**
     * 更新Fab按钮边界值
     */
    private void updateFabRect() {
        getGlobalVisibleRect(mFabRect);
    }

    /**
     * 更新对话框边界值
     */
    private void updateDialogRect() {
        if (mDialogRect == null) {
            mDialogRect = new Rect();
            mDialog.getGlobalVisibleRect(mDialogRect);
        }
    }

    /**
     * 获取水波最小半径
     *
     * @return 水波最小半径
     */
    private float getMinRadius() {
//        if (getMaxRadius() > mFabRect.width()) {
//            return mFabRect.width();
//        }
        return mFabRect.width() / 2f;
    }

    private float getMaxRadius() {
        return (float) Math.hypot(mDialogRect.width(), mDialogRect.height());
    }

    /**
     * 设置自定义的对话框
     *
     * @param dialog 自定义的对话框
     */
    public void setDialogView(View dialog) {
        if (mDialog == null) {
            mDialog = dialog;
            dialogParentView.addView(mDialog);
            dialogParentView.setOnTouchListener(mDialogTouchOutSideListener);
        }
    }

    /**
     * 如果你无法确定自己的对话框改设置多宽合适，则可以使用该方法进行设置统一值
     */
    public void setDefaultWidth() {
        if (mDialog == null) {
            throw new RuntimeException("Please setDialogView first!");
        }

        final DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        final boolean isPortrait = metrics.widthPixels < metrics.heightPixels;

        float percent;
        if (isPortrait) {
            percent = mContext.getResources().getFraction(R.fraction.dialog_min_width_minor, 1, 1);
        } else {
            percent = mContext.getResources().getFraction(R.fraction.dialog_min_width_major, 1, 1);
        }
        int width = (int) (mContext.getResources().getDisplayMetrics().widthPixels * percent);

        ViewGroup.LayoutParams params = mDialog.getLayoutParams();
        if (params == null) {
            mDialog.setLayoutParams(new ViewGroup.LayoutParams(width, width));
        } else {
            params.width = width;
        }
    }

    public void setCanceledOnTouchOutside(boolean cancel) {
        if (cancel && !mCancelable) {
            mCancelable = true;
        }
    }

    /**
     * 设置FAB移动动画执行时间（毫秒）
     */
    public void setFabMoveDuration(int fabMoveDuration) {
        this.fabMoveDuration = fabMoveDuration;
    }


    /**
     * 设置dialog展示/关闭动画执行时间（毫秒）
     */
    public void setDialogShowDuration(int dialogShowDuration) {
        this.dialogShowDuration = dialogShowDuration;
    }

    private final OnTouchListener mDialogTouchOutSideListener = new OnTouchListener() {
        @Override
        public boolean onTouch(@NonNull View v, @NonNull MotionEvent event) {
            if (mCancelable && isDisplayComplete) {
                final int x = (int) event.getX();
                final int y = (int) event.getY();
                if ((MotionEventCompat.getActionMasked(event) == MotionEvent.ACTION_DOWN)
                        && ((x < mDialogRect.left) || (x > mDialogRect.right)
                        || (y < mDialogRect.top) || (y > mDialogRect.bottom))) {
                    close();
                    return true;
                } else if (MotionEventCompat.getActionMasked(event) == MotionEvent.ACTION_OUTSIDE) {
                    close();
                    return true;
                }
            }
            return false;
        }
    };

    private final AnimatorListenerAdapter closeDialogAnimatorListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationStart(Animator animation) {
            FDialog.this.setVisibility(INVISIBLE);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            detachView();
            FDialog.this.setVisibility(VISIBLE);
            isDisplayComplete = false;
        }
    };

    private final AnimatorListenerAdapter openDialogAnimatorListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            isDisplayComplete = true;
        }
    };

    private final AnimatorListenerAdapter moveFabToCAnimatorListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationStart(Animator animation) {
            FDialog.this.setVisibility(VISIBLE);
            mDialog.setVisibility(INVISIBLE);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            FDialog.this.setVisibility(INVISIBLE);
            mDialog.setVisibility(VISIBLE);
        }
    };

    /**
     * {@link SavedState} methods
     */

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        ss.dialogViewState = new SparseArray();

        //noinspection unchecked
        mMaskView.saveHierarchyState(ss.dialogViewState);

        ss.opened = isOpened();
        return ss;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        //noinspection unchecked
        mMaskView.restoreHierarchyState(ss.dialogViewState);
        if (ss.opened) {
//            mFabView.setImageResource(R.drawable.ic_close_vector);
            // Run on post to prevent "unable to add window -- token null is not valid" error
            post(new Runnable() {
                @Override
                public void run() {
                    attachView();
                    mMaskView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                                mMaskView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            } else {
                                mMaskView.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                            }
                            updateFabRect();
                            updateDialogRect();
                            startOpenAnimations();
                        }
                    });
                }
            });
        }
    }

    public static class SavedState extends AbsSavedState {

        private SparseArray dialogViewState;
        private boolean opened;

        private SavedState(Parcel in, ClassLoader loader) {
            super(in);
            dialogViewState = in.readSparseArray(loader);
            opened = (Boolean) in.readValue(loader);
        }

        private SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            //noinspection unchecked
            dest.writeSparseArray(dialogViewState);
            dest.writeValue(opened);
        }

        @Override
        public String toString() {
            return FDialog.class.getSimpleName() + "." + SavedState.class.getSimpleName() + "{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " opened=" + opened + "}";
        }

        public static final Creator<SavedState> CREATOR
                = ParcelableCompat.newCreator(new ParcelableCompatCreatorCallbacks<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel parcel, ClassLoader loader) {
                return new SavedState(parcel, loader);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        });
    }
}

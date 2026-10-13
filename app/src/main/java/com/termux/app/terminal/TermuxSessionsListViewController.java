package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.texmux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

public class TermuxSessionsListViewController extends ArrayAdapter<TermuxSession> implements AdapterView.OnItemClickListener {

    final TermuxActivity mActivity;
    final List<TermuxSession> mSessionList;

    final StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
    final StyleSpan italicSpan = new StyleSpan(Typeface.ITALIC);

    private static final long LONG_PRESS_DURATION = 180;
    private static final int TOUCH_SLOP = 8;

    private boolean mDragActive = false;
    private int mDragFromPosition = -1;
    private int mDragTargetPosition = -1;
    private float mTouchStartX, mTouchStartY;
    private float mLastRawX, mLastRawY;
    private int mOverlayOffsetX, mOverlayOffsetY;
    private long mDownEventTime;
    private final Handler mDragHandler = new Handler();
    private Runnable mLongPressRunnable;
    private List<TermuxSession> mOriginalSessionList;
    private long mSuppressClickUntil = 0;
    private long mLastEditClickTime = 0;
    private ListView mListView;
    private ImageView mDragOverlay;
    private View mSelectedDropTarget;

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList) {
        super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, sessionList);
        this.mActivity = activity;
        this.mSessionList = sessionList;
    }

    public void attachToListView(ListView listView) {
        mListView = listView;
        listView.setAdapter(this);
        listView.setOnItemClickListener(this);
        listView.setOnTouchListener(mListTouchListener);
    }

    private final View.OnTouchListener mListTouchListener = (v, event) -> {
        if (mDragActive) {
            mLastRawX = event.getRawX();
            mLastRawY = event.getRawY();
            return handleDragEvent(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mLastRawX = event.getRawX();
            mLastRawY = event.getRawY();
            int pos = mListView.pointToPosition((int) event.getX(), (int) event.getY());
            if (pos >= 0 && isSessionRow(pos) && !isTouchOnEditButton(event, pos)) {
                startLongPressCheck(event, pos);
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (mLongPressRunnable != null) {
                float dx = Math.abs(event.getX() - mTouchStartX);
                float dy = Math.abs(event.getY() - mTouchStartY);
                if (dx > TOUCH_SLOP || dy > TOUCH_SLOP) {
                    cancelLongPressCheck();
                }
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            cancelLongPressCheck();
        }
        return false;
    };

    private boolean isSessionRow(int position) {
        return position >= 0 && position < mSessionList.size();
    }

    private void updateDropTarget() {
        if (mSelectedDropTarget != null) {
            mSelectedDropTarget.setSelected(false);
            mSelectedDropTarget = null;
        }
        int first = mListView.getFirstVisiblePosition();
        int last = mListView.getLastVisiblePosition();
        if (mDragTargetPosition >= first && mDragTargetPosition <= last) {
            View target = mListView.getChildAt(mDragTargetPosition - first);
            if (target != null) {
                target.setSelected(true);
                mSelectedDropTarget = target;
            }
        }
    }

    private void clearDropTargets() {
        if (mSelectedDropTarget != null) {
            mSelectedDropTarget.setSelected(false);
            mSelectedDropTarget = null;
        }
        int first = mListView.getFirstVisiblePosition();
        int count = mListView.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = mListView.getChildAt(i);
            if (child != null && child.isSelected()) {
                child.setSelected(false);
            }
        }
    }

    private boolean isTouchOnEditButton(MotionEvent event, int position) {
        if (mListView == null || position < 0) return false;
        int first = mListView.getFirstVisiblePosition();
        int last = mListView.getLastVisiblePosition();
        if (position >= first && position <= last) {
            View item = mListView.getChildAt(position - first);
            if (item != null) {
                View editButton = item.findViewById(R.id.session_edit_button);
                if (editButton != null && editButton.getVisibility() == View.VISIBLE) {
                    int[] loc = new int[2];
                    editButton.getLocationOnScreen(loc);
                    return mLastRawX >= loc[0] && mLastRawX <= loc[0] + editButton.getWidth()
                        && mLastRawY >= loc[1] && mLastRawY <= loc[1] + editButton.getHeight();
                }
            }
        }
        return false;
    }

    private void startLongPressCheck(MotionEvent event, int position) {
        mTouchStartX = event.getX();
        mTouchStartY = event.getY();
        mDownEventTime = event.getDownTime();
        mLongPressRunnable = () -> {
            if (position >= 0 && position < mSessionList.size()) {
                beginDrag(position);
            }
        };
        mDragHandler.postDelayed(mLongPressRunnable, LONG_PRESS_DURATION);
    }

    private void cancelLongPressCheck() {
        if (mLongPressRunnable != null) {
            mDragHandler.removeCallbacks(mLongPressRunnable);
            mLongPressRunnable = null;
        }
    }

    @SuppressLint({"ClickableViewAccessibility", "WrongConstant"})
    private void beginDrag(int position) {
        int first = mListView.getFirstVisiblePosition();
        int childIndex = position - first;
        View itemView = mListView.getChildAt(childIndex);

        if (itemView != null) {
            Bitmap bitmap = Bitmap.createBitmap(itemView.getWidth(), itemView.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            itemView.draw(canvas);

            mDragOverlay = new ImageView(mActivity);
            mDragOverlay.setImageBitmap(bitmap);
            mDragOverlay.setClickable(false);

            int[] loc = new int[2];
            itemView.getLocationOnScreen(loc);
            mOverlayOffsetX = (int) (mLastRawX - loc[0]);
            mOverlayOffsetY = (int) (mLastRawY - loc[1]);

            ViewGroup decorView = (ViewGroup) mActivity.getWindow().getDecorView();
            decorView.addView(mDragOverlay, new ViewGroup.LayoutParams(itemView.getWidth(), itemView.getHeight()));
            mDragOverlay.setX(mLastRawX - mOverlayOffsetX);
            mDragOverlay.setY(mLastRawY - mOverlayOffsetY);
            mDragOverlay.setElevation(999f);
            mDragOverlay.setAlpha(0.98f);
        }

        if (mListView != null) {
            MotionEvent cancelEvent = MotionEvent.obtain(mDownEventTime,
                SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0);
            mListView.onTouchEvent(cancelEvent);
            cancelEvent.recycle();
        }
        mDragActive = true;
        mDragFromPosition = position;
        mDragTargetPosition = position;
        mOriginalSessionList = new ArrayList<>(mSessionList);
        notifyDataSetChanged();
    }

    @SuppressLint("WrongConstant")
    private boolean handleDragEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                if (mDragOverlay != null) {
                    mDragOverlay.setX(mLastRawX - mOverlayOffsetX);
                    mDragOverlay.setY(mLastRawY - mOverlayOffsetY);
                }
                int newPos = mListView.pointToPosition((int) event.getX(), (int) event.getY());
                if (newPos >= 0 && newPos < mSessionList.size() && newPos != mDragTargetPosition) {
                    mDragTargetPosition = newPos;
                    updateDropTarget();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                finishDrag();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                cancelDrag();
                return true;
            }
        }
        return false;
    }

    private void finishDrag() {
        removeDragOverlay();
        clearDropTargets();

        if (mDragFromPosition >= 0 && mDragFromPosition < mSessionList.size()
            && mDragTargetPosition >= 0 && mDragTargetPosition < mSessionList.size()
            && mDragFromPosition != mDragTargetPosition) {
            TermuxSession draggedSession = mSessionList.remove(mDragFromPosition);
            mSessionList.add(mDragTargetPosition, draggedSession);
        }

        int finalPos = (mDragTargetPosition >= 0 && mDragTargetPosition < mSessionList.size())
            ? mDragTargetPosition : mDragFromPosition;
        if (finalPos >= 0 && finalPos < mSessionList.size()) {
            TermuxSession draggedSession = mSessionList.get(finalPos);
            mActivity.getTermuxTerminalSessionClient().setCurrentSession(draggedSession.getTerminalSession());
        }

        mDragActive = false;
        mDragFromPosition = -1;
        mDragTargetPosition = -1;
        mOriginalSessionList = null;
        mSuppressClickUntil = System.currentTimeMillis() + 250;
        notifyDataSetChanged();
    }

    private void cancelDrag() {
        removeDragOverlay();
        clearDropTargets();
        if (mOriginalSessionList != null) {
            mSessionList.clear();
            mSessionList.addAll(mOriginalSessionList);
        }
        mDragActive = false;
        mDragFromPosition = -1;
        mDragTargetPosition = -1;
        mOriginalSessionList = null;
        notifyDataSetChanged();
    }

    private void removeDragOverlay() {
        if (mDragOverlay != null) {
            ViewGroup parent = (ViewGroup) mDragOverlay.getParent();
            if (parent != null) parent.removeView(mDragOverlay);
            mDragOverlay = null;
        }
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View sessionRowView = convertView;
        if (sessionRowView == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false);
        }

        TextView sessionTitleView = sessionRowView.findViewById(R.id.session_title);
        ImageButton editButton = sessionRowView.findViewById(R.id.session_edit_button);

        sessionRowView.setVisibility(View.VISIBLE);
        sessionRowView.setSelected(false);

        TerminalSession sessionAtRow = getItem(position).getTerminalSession();
        if (sessionAtRow == null) {
            sessionTitleView.setText("null session");
            return sessionRowView;
        }

        TerminalSession currentSession = mActivity.getCurrentSession();
        boolean isActive = (sessionAtRow == currentSession);
        sessionTitleView.setSelected(isActive);

        String name = sessionAtRow.mSessionName;
        String sessionTitle = sessionAtRow.getTitle();

        String numberPart = "[" + (position + 1) + "] ";
        String sessionNamePart = (TextUtils.isEmpty(name) ? "" : name);
        String sessionTitlePart = (TextUtils.isEmpty(sessionTitle) ? "" : ((sessionNamePart.isEmpty() ? "" : "\n") + sessionTitle));

        String fullSessionTitle = numberPart + sessionNamePart + sessionTitlePart;
        SpannableString fullSessionTitleStyled = new SpannableString(fullSessionTitle);
        fullSessionTitleStyled.setSpan(boldSpan, 0, numberPart.length() + sessionNamePart.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        fullSessionTitleStyled.setSpan(italicSpan, numberPart.length() + sessionNamePart.length(), fullSessionTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        sessionTitleView.setText(fullSessionTitleStyled);

        boolean sessionRunning = sessionAtRow.isRunning();

        if (sessionRunning) {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            sessionTitleView.setPaintFlags(sessionTitleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }

        int titleColor = sessionRunning || sessionAtRow.getExitStatus() == 0 ? 0xFFF8FAFC : Color.RED;
        sessionTitleView.setTextColor(titleColor);
        editButton.setColorFilter(titleColor);

        editButton.setOnClickListener(v -> {
            mLastEditClickTime = System.currentTimeMillis();
            mActivity.getTermuxTerminalSessionClient().renameSession(sessionAtRow);
        });

        return sessionRowView;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        long now = System.currentTimeMillis();
        if (now < mSuppressClickUntil) return;
        if (now - mLastEditClickTime < 300) return;
        TermuxSession clickedSession = getItem(position);
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
        mActivity.getDrawer().closeDrawers();
    }

}

package local.jile.v5;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.Context;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class DesktopPager extends LinearLayout {
    interface Listener {
        void pageChanged(int page);
        void orderChanged(List<String> ids);
    }

    static final class Entry {
        final String id;
        final View tile;
        Entry(String id, View tile) { this.id = id; this.tile = tile; }
    }

    private final List<Entry> entries;
    private final Listener listener;
    private final Grid grid;
    private final LinearLayout controls;
    private final int columns, rows, slots, preferredRowHeight;
    private int rowHeight;
    private int current;

    DesktopPager(Context context, boolean landscape, int tileHeight, List<Entry> entries,
                 int initialPage, Listener listener) {
        super(context);
        this.entries = new ArrayList<>(entries);
        this.listener = listener;
        columns = landscape ? 4 : 3;
        rows = landscape ? 2 : 3;
        slots = columns * rows;
        preferredRowHeight = dp(tileHeight + 8);
        rowHeight = preferredRowHeight;
        current = Math.min(Math.max(0, initialPage), pageCount() - 1);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_VERTICAL);
        grid = new Grid(context);
        addView(grid, new LayoutParams(-1, rowHeight * rows));
        controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER);
        addView(controls, new LayoutParams(-1, dp(33)));
        for (Entry entry : this.entries) {
            grid.addView(entry.tile);
            entry.tile.setOnLongClickListener(v -> grid.begin(entry));
        }
        updateControls();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        rowHeight = preferredRowHeight;
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            int controlsHeight = controls.getVisibility() == GONE ? 0 : dp(33);
            int available = Math.max(0, MeasureSpec.getSize(heightSpec) - controlsHeight);
            rowHeight = Math.min(rowHeight, available / rows);
        }
        grid.getLayoutParams().height = rowHeight * rows;
        super.onMeasure(widthSpec, heightSpec);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int pageCount() { return Math.max(1, (entries.size() + slots - 1) / slots); }

    private void updateControls() {
        controls.removeAllViews();
        controls.setVisibility(pageCount() > 1 ? VISIBLE : GONE);
        for (int i = 0; i < pageCount() && pageCount() > 1; i++) {
            final int page = i;
            TextView dot = control(i == current ? "●" : "○", "第 " + (i + 1) + " 页");
            dot.setSelected(i == current);
            dot.setOnClickListener(v -> turn(page));
            controls.addView(dot, new LayoutParams(dp(32), -1));
        }
    }

    private TextView control(String label, String description) {
        TextView view = new TextView(getContext());
        view.setGravity(Gravity.CENTER);
        view.setTextColor(0xffa2e8f2);
        view.setTextSize(14);
        view.setText(label);
        view.setContentDescription(description);
        return view;
    }

    private void turn(int next) {
        current = Math.max(0, Math.min(next, pageCount() - 1));
        grid.settle();
        grid.updateAccessibility();
        if (grid.dragging != null) grid.refreshEditing();
        updateControls();
        listener.pageChanged(current);
    }

    private final class Grid extends ViewGroup {
        private final android.view.animation.Interpolator easing = new DecelerateInterpolator(1.8f);
        private ValueAnimator slide;
        private Entry dragging;
        private List<Entry> original;
        private float downX, downY, lastShiftX, lastShiftY;
        private int edgeDirection;
        private boolean shifted, dragHadLocation;
        private final List<ObjectAnimator> wiggles = new ArrayList<>();
        private final Runnable edgeTurn = () -> {
            if (dragging == null || edgeDirection == 0) return;
            turn(current + edgeDirection);
            shifted = false;
        };

        Grid(Context context) {
            super(context);
            setClipChildren(true);
            setOnDragListener((v, event) -> drag(event));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            setMeasuredDimension(width, rowHeight * rows);
            int margin = dp(4);
            for (int i = 0; i < entries.size(); i++) {
                int col = i % columns;
                entries.get(i).tile.measure(
                        MeasureSpec.makeMeasureSpec(Math.max(0, width * (col + 1) / columns
                                - width * col / columns - margin * 2), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.max(0, rowHeight - dp(8)), MeasureSpec.EXACTLY));
            }
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            for (int i = 0; i < entries.size(); i++) {
                View tile = entries.get(i).tile;
                int x = xAt(i, width);
                int y = yAt(i);
                tile.layout(x, y, x + tile.getMeasuredWidth(), y + tile.getMeasuredHeight());
            }
            if (changed) {
                cancelSlide();
                scrollTo(current * width, 0);
            }
            updateAccessibility();
        }

        private int xAt(int index, int width) {
            return index / slots * width + index % columns * width / columns + dp(4);
        }

        private int yAt(int index) { return index % slots / columns * rowHeight; }

        @Override protected void onDetachedFromWindow() {
            cancelSlide();
            stopWiggles();
            removeCallbacks(edgeTurn);
            super.onDetachedFromWindow();
        }

        void updateAccessibility() {
            for (int i = 0; i < entries.size(); i++) {
                View tile = entries.get(i).tile;
                boolean visible = i / slots == current;
                tile.setFocusable(visible);
                tile.setImportantForAccessibility(visible
                        ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
        }

        private void cancelSlide() { if (slide != null) slide.cancel(); }

        void settle() {
            cancelSlide();
            int end = current * getWidth();
            if (!ValueAnimator.areAnimatorsEnabled()) {
                scrollTo(end, 0);
                return;
            }
            slide = ValueAnimator.ofInt(getScrollX(), end);
            slide.setInterpolator(easing);
            slide.setDuration(230);
            slide.addUpdateListener(animation -> scrollTo((int) animation.getAnimatedValue(), 0));
            slide.start();
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (pageCount() < 2 || dragging != null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(event.getX() - downX);
                    if (dx > dp(13) && dx > Math.abs(event.getY() - downY)) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                        cancelSlide();
                        return true;
                    }
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (pageCount() < 2 || dragging != null) return false;
            int width = getWidth();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) downX = event.getX();
            float dx = event.getX() - downX;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: return true;
                case MotionEvent.ACTION_MOVE:
                    cancelSlide();
                    scrollTo(Math.max(0, Math.min((pageCount() - 1) * width,
                            Math.round(current * width - dx))), 0);
                    return true;
                case MotionEvent.ACTION_UP:
                    int threshold = Math.min(dp(55), width / 5);
                    turn(current + (dx > threshold ? -1 : dx < -threshold ? 1 : 0));
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    settle();
                    return true;
                default: return true;
            }
        }

        boolean begin(Entry entry) {
            if (dragging != null) return false;
            if (!entry.tile.startDragAndDrop(ClipData.newPlainText("", ""), new DragShadowBuilder(entry.tile),
                    entry, 0)) return false;
            dragging = entry;
            original = new ArrayList<>(entries);
            shifted = false;
            dragHadLocation = false;
            refreshEditing();
            return true;
        }

        private void stopWiggles() {
            for (ObjectAnimator wiggle : wiggles) wiggle.cancel();
            wiggles.clear();
        }

        void refreshEditing() {
            stopWiggles();
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                entry.tile.setRotation(0);
                entry.tile.setAlpha(entry == dragging ? .28f : 1f);
                if (dragging == null || i / slots != current || !ValueAnimator.areAnimatorsEnabled()) continue;
                float tilt = i % 2 == 0 ? -1.6f : 1.6f;
                ObjectAnimator wiggle = ObjectAnimator.ofFloat(entry.tile, View.ROTATION, tilt, -tilt);
                wiggle.setDuration(220);
                wiggle.setRepeatMode(ValueAnimator.REVERSE);
                wiggle.setRepeatCount(ValueAnimator.INFINITE);
                wiggle.start();
                wiggles.add(wiggle);
            }
        }

        private boolean drag(DragEvent event) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: return event.getLocalState() == dragging && dragging != null;
                case DragEvent.ACTION_DRAG_LOCATION:
                    dragHadLocation = true;
                    preview(event.getX(), event.getY(), true);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    setEdge(0);
                    return true;
                case DragEvent.ACTION_DROP:
                    if (dragHadLocation) preview(event.getX(), event.getY(), false);
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    setEdge(0);
                    if (dragging == null) return true;
                    if (!event.getResult()) {
                        entries.clear();
                        entries.addAll(original);
                        requestLayout();
                    }
                    if (event.getResult() && !entries.equals(original)) {
                        ArrayList<String> ids = new ArrayList<>();
                        for (Entry entry : entries) ids.add(entry.id);
                        listener.orderChanged(ids);
                    }
                    dragging = null;
                    original = null;
                    refreshEditing();
                    return true;
                default: return true;
            }
        }

        private void setEdge(int dir) {
            if (edgeDirection == dir) return;
            removeCallbacks(edgeTurn);
            edgeDirection = dir;
            if (dir != 0 && current + dir >= 0 && current + dir < pageCount()) {
                postDelayed(edgeTurn, 550);
            }
        }

        private void preview(float x, float y, boolean allowPageTurn) {
            if (dragging == null || getWidth() <= 0) return;
            int width = getWidth();
            if (allowPageTurn) setEdge(x < dp(24) ? -1 : x > width - dp(24) ? 1 : 0);
            if (shifted && Math.hypot(x - lastShiftX, y - lastShiftY) < dp(12)) return;
            int col = Math.max(0, Math.min(columns - 1, (int) (x * columns / width)));
            int row = Math.max(0, Math.min(rows - 1, (int) (y / rowHeight)));
            int slot = current * slots + row * columns + col;
            Entry target = slot < entries.size() ? entries.get(slot) : null;
            if (target == dragging) return;
            List<Entry> before = new ArrayList<>(entries);
            entries.remove(dragging);
            boolean after = x * columns / width - col >= .5f;
            entries.add(target == null ? entries.size() : entries.indexOf(target) + (after ? 1 : 0), dragging);
            if (before.equals(entries)) return;
            shifted = true;
            lastShiftX = x;
            lastShiftY = y;
            boolean animate = ValueAnimator.areAnimatorsEnabled();
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                int old = before.indexOf(entry);
                if (old == i) continue;
                View tile = entry.tile;
                tile.animate().cancel();
                tile.setTranslationX(0);
                tile.setTranslationY(0);
                if (animate && entry != dragging) {
                    tile.setTranslationX(xAt(old, width) - xAt(i, width));
                    tile.setTranslationY(yAt(old) - yAt(i));
                    tile.animate().translationX(0).translationY(0).setDuration(210).setInterpolator(easing).start();
                }
            }
            requestLayout();
        }
    }
}

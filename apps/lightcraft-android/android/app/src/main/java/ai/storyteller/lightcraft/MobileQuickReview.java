package ai.storyteller.lightcraft;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Objects;

/**
 * Native review controls only. The Activity owns transactions, error handling,
 * selection changes and advancing. A write is latched before notifying the host.
 */
final class MobileQuickReview extends ScrollView {
    interface Callbacks {
        void onRating(long photoId, int rating);
        void onFlag(long photoId, String flag);
        void onAutoAdvance(boolean enabled);
    }

    private static final String[] FLAGS = {"none", "pick", "reject"};
    private static final int[] FLAG_NAMES = {R.string.qr_none, R.string.qr_picked, R.string.qr_rejected};
    private static final int BG = 0xff111721;
    private static final int SURFACE = 0xff212b39;
    private static final int OUTLINE = 0xff50607a;
    private static final int ACCENT = 0xffeeba6d;
    private static final int TEXT = 0xfff6f8fb;
    private static final int SECONDARY = 0xffbcc9dc;

    private final Callbacks callbacks;
    private final LinearLayout controls, ratingRows, flagRows;
    private final Choice[] ratings = new Choice[6];
    private final Choice[] flags = new Choice[FLAGS.length];
    private final TextView currentRating, currentFlag, stateLine;
    private final Switch advance;
    private int ratingColumns = -1, flagColumns = -1;
    private long photoId = -1;
    private int rating;
    private String flag = "none";
    private boolean hasNext, autoAdvance, busy, updatingAdvance;

    MobileQuickReview(Context context, Callbacks callbacks) {
        super(context);
        this.callbacks = Objects.requireNonNull(callbacks);
        setFillViewport(false);
        setClipToPadding(false);
        setBackgroundColor(BG);
        setVerticalScrollBarEnabled(true);
        controls = column();
        controls.setPadding(dp(14), dp(10), dp(14), dp(16));
        addView(controls, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView title = label(res(R.string.qr_title), 19, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        controls.addView(title);
        currentRating = label("", 14, SECONDARY);
        currentRating.setAccessibilityHeading(true);
        currentRating.setPadding(0, dp(10), 0, dp(4));
        ratingRows = column();
        currentFlag = label("", 14, SECONDARY);
        currentFlag.setAccessibilityHeading(true);
        currentFlag.setPadding(0, dp(10), 0, dp(4));
        controls.addView(currentFlag);
        flagRows = column();
        controls.addView(flagRows);

        for (int index = 0; index < ratings.length; index++) {
            int value = index;
            Choice choice = new Choice(context);
            choice.setText(value == 0 ? "0" : value + " ★");
            choice.setContentDescription(ratingName(value));
            choice.setOnClickListener(v -> writeRating(value));
            ratings[index] = choice;
        }
        for (int index = 0; index < flags.length; index++) {
            String value = FLAGS[index];
            Choice choice = new Choice(context);
            choice.setText(FLAG_NAMES[index]);
            choice.setContentDescription(res(FLAG_NAMES[index]));
            choice.setOnClickListener(v -> writeFlag(value));
            flags[index] = choice;
        }

        advance = new Switch(context);
        advance.setText(R.string.qr_advance);
        advance.setTextColor(TEXT);
        advance.setTextSize(15);
        advance.setMinHeight(dp(48));
        advance.setShowText(false);
        advance.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams toggle = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        toggle.topMargin = dp(8);
        controls.addView(advance, toggle);
        advance.setOnCheckedChangeListener((button, checked) -> {
            if (updatingAdvance) return;
            if (!canWrite() || !hasNext) {
                updateControls();
                return;
            }
            autoAdvance = checked;
            updateControls();
            callbacks.onAutoAdvance(checked);
        });
        stateLine = label("", 13, SECONDARY);
        stateLine.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stateLine.setPadding(dp(3), 0, dp(3), dp(3));
        controls.addView(stateLine);
        controls.addView(currentRating);
        controls.addView(ratingRows);

        layoutChoices(3, 3);
        updateControls();
    }

    /** Display a resolved model. Never emits a choice or clears a pending write. */
    void setState(long photoId, int rating, String flag, boolean hasNext) {
        this.photoId = photoId;
        this.rating = Math.max(0, Math.min(5, rating));
        this.flag = flagIndex(flag) == 0 ? "none" : flag;
        this.hasNext = hasNext;
        updateControls();
    }

    /** Release only once the engine has replied (success or failure). */
    void setBusy(boolean busy) {
        this.busy = busy;
        updateControls();
    }

    /** Restore an explicit host preference without notifying listeners. Default is false. */
    void setAutoAdvance(boolean enabled) {
        this.autoAdvance = enabled;
        updateControls();
    }

    /** Effective only when a next photo exists. The Activity still advances after success. */
    boolean isAutoAdvance() { return photoId >= 0 && hasNext && autoAdvance; }

    /** Boolean snapshot check for a host applying the result to its current photo. */
    boolean shouldAdvanceAfterSuccess(long editedPhotoId) {
        return photoId == editedPhotoId && isAutoAdvance();
    }

    private void writeRating(int next) {
        if (!canWrite() || next == rating) return;
        long id = photoId;
        setBusy(true);
        callbacks.onRating(id, next);
    }

    private void writeFlag(String next) {
        if (!canWrite() || next.equals(flag)) return;
        long id = photoId;
        setBusy(true);
        callbacks.onFlag(id, next);
    }

    private boolean canWrite() { return photoId >= 0 && !busy; }

    private void updateControls() {
        boolean available = canWrite();
        int selectedFlag = flagIndex(flag);
        for (int i = 0; i < ratings.length; i++) ratings[i].showState(photoId >= 0 && i == rating, available);
        for (int i = 0; i < flags.length; i++) flags[i].showState(photoId >= 0 && i == selectedFlag, available);
        currentRating.setText(photoId >= 0 ?
            getResources().getString(R.string.qr_rating_state, ratingName(rating)) : res(R.string.qr_rating));
        currentFlag.setText(photoId >= 0 ?
            getResources().getString(R.string.qr_flag_state, res(FLAG_NAMES[selectedFlag])) : res(R.string.qr_flag));
        updatingAdvance = true;
        try {
            advance.setChecked(autoAdvance);
            advance.setEnabled(available && hasNext);
        } finally {
            updatingAdvance = false;
        }
        stateLine.setText(busy ? R.string.qr_saving : photoId < 0 ? R.string.qr_no_photo
            : !hasNext ? R.string.qr_no_next : autoAdvance ? R.string.qr_advance_on : R.string.qr_advance_off);
    }

    private static int flagIndex(String key) {
        for (int i = 1; i < FLAGS.length; i++) if (FLAGS[i].equals(key)) return i;
        return 0;
    }

    private String ratingName(int value) {
        return value == 0 ? res(R.string.qr_zero)
            : getResources().getQuantityString(R.plurals.qr_stars, value, value);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        int panelWidth = width - controls.getPaddingLeft() - controls.getPaddingRight();
        float font = getResources().getConfiguration().fontScale;
        layoutChoices(panelWidth >= dp(font > 1.2f ? 412 : 354) ? 6 :
                panelWidth >= dp(165) ? 3 : panelWidth >= dp(110) ? 2 : 1,
            panelWidth >= dp(font > 1.2f ? 295 : 252) ? 3 : panelWidth >= dp(164) ? 2 : 1);
    }

    private void layoutChoices(int ratingCols, int flagCols) {
        if (ratingCols != ratingColumns) {
            ratingColumns = ratingCols;
            layOut(ratingRows, ratings, ratingCols);
        }
        if (flagCols != flagColumns) {
            flagColumns = flagCols;
            layOut(flagRows, flags, flagCols);
        }
    }

    private void layOut(LinearLayout target, Choice[] cells, int columns) {
        for (Choice cell : cells) if (cell.getParent() instanceof LinearLayout parent) parent.removeView(cell);
        target.removeAllViews();
        for (int row = 0; row < cells.length; row += columns) {
            LinearLayout lines = new LinearLayout(getContext());
            lines.setOrientation(LinearLayout.HORIZONTAL);
            target.addView(lines, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            for (int i = row; i < Math.min(row + columns, cells.length); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
                // Resolve start/end for RTL and leave a visible gap between touch targets.
                lp.setMarginStart(dp(3));
                lp.setMarginEnd(dp(3));
                lp.bottomMargin = dp(6);
                lines.addView(cells[i], lp);
            }
        }
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView label(String content, int sp, int color) {
        TextView v = new TextView(getContext());
        v.setText(content);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private String res(int id) { return getResources().getString(id); }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    /** Radio semantics for TalkBack without speculative state changes on tap. */
    private final class Choice extends Button {
        private boolean chosen;
        Choice(Context context) {
            super(context, null, android.R.attr.borderlessButtonStyle);
            setMinWidth(dp(48));
            setMinHeight(dp(48));
            setMinimumHeight(dp(48));
            setAllCaps(false);
            setGravity(Gravity.CENTER);
            setTextSize(15);
            setPadding(dp(3), dp(4), dp(3), dp(4));
            setMaxLines(2);
        }

        void showState(boolean selected, boolean enabled) {
            chosen = selected;
            setSelected(selected);
            setEnabled(enabled);
            setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            setTextColor(selected ? BG : enabled ? TEXT : SECONDARY);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(selected ? ACCENT : SURFACE);
            shape.setCornerRadius(dp(12));
            // Selection has a surface fill and heavy outline, not color alone.
            shape.setStroke(dp(selected ? 2 : 1), selected ? TEXT : OUTLINE);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x448d9eb5), shape, null));
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(RadioButton.class.getName());
            info.setCheckable(true);
            info.setChecked(chosen);
        }
    }
}

package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

/** CPU-only RAW denoise. Preparing the private cache is distinct from applying a photo edit. */
final class MobileDenoise {
    interface Host {
        void call(String op, JSONObject args, Consumer<JSONObject> success, Consumer<Throwable> failure);
    }

    private final Activity activity;
    private final long photo;
    private final Host host;
    private final Runnable changed;
    private final Handler ticks = new Handler(Looper.getMainLooper());
    private final Runnable tick = () -> query("noise_status", args(), false);
    private final LinearLayout amountBox;
    private final SeekBar amount;
    private final TextView amountText;
    private final TextView status;
    private final ProgressBar progress;
    private AlertDialog dialog;
    private JSONObject last;
    private boolean dismissed, busy, preparing, amountChosen;

    static void show(Activity activity, long photo, Host host, Runnable changed) {
        new MobileDenoise(activity, photo, host, changed).open();
    }

    private MobileDenoise(Activity activity, long photo, Host host, Runnable changed) {
        this.activity = activity;
        this.photo = photo;
        this.host = host;
        this.changed = changed;

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout content = column();
        int p = dp(20);
        content.setPadding(p, dp(10), p, dp(12));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = label(R.string.mobile_denoise_wait);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        content.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));

        amountBox = column();
        amountBox.setPadding(0, dp(12), 0, 0);
        content.addView(amountBox);
        amountText = label(R.string.mobile_denoise_strength);
        amountBox.addView(amountText);
        amount = new SeekBar(activity);
        amount.setMin(1);
        amount.setMax(100);
        amount.setProgress(50);
        amount.setMinimumHeight(dp(48));
        amount.setContentDescription(activity.getString(R.string.mobile_denoise_strength));
        amount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
            @Override public void onProgressChanged(SeekBar bar, int n, boolean user) {
                amountChosen |= user;
                amountLabel(n);
            }
        });
        amountBox.addView(amount, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        amountBox.setVisibility(View.GONE);

        dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.mobile_denoise_title)
            .setView(scroll)
            .setNegativeButton(R.string.mobile_denoise_close, null)
            .setNeutralButton(R.string.mobile_denoise_off, null)
            .setPositiveButton(R.string.mobile_denoise_prepare, null)
            .create();
    }

    private void open() {
        dialog.setOnDismissListener(ignored -> {
            dismissed = true;
            ticks.removeCallbacks(tick);
            // The old dialog may go away on rotation. Only cancel its own queued/running photo.
            if (preparing) host.call("noise_cancel", args(), noResult -> {}, noError -> {});
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> onPositive());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> apply(false));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        query("noise_status", args(), false);
    }

    private void onPositive() {
        if (busy || last == null) return;
        String state = last.optString("state");
        if (state.equals("ready")) {
            apply(true);
        } else if (state.equals("idle") || state.equals("failed")) {
            if (!memoryAllows(last.optLong("pixels"))) {
                status.setText(R.string.mobile_denoise_memory);
                return;
            }
            preparing = true;
            query("noise_prepare", args(), false);
        }
    }

    private boolean memoryAllows(long pixels) {
        ActivityManager manager = (ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return true;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        return !memory.lowMemory && (pixels < 40_000_000L || memory.availMem >= 1_000_000_000L);
    }

    private void apply(boolean enabled) {
        if (busy || last == null) return;
        JSONObject p = args();
        put(p, "enabled", enabled);
        put(p, "amount", amount.getProgress());
        query("noise_apply", p, true);
    }

    private void query(String op, JSONObject args, boolean edit) {
        if (dismissed || busy) return;
        busy = true;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        host.call(op, args, value -> {
            if (dismissed) {
                if (edit) changed.run();
                return;
            }
            busy = false;
            update(value);
            if (edit) {
                changed.run();
                dialog.dismiss();
            }
        }, error -> {
            if (dismissed) return;
            busy = false;
            status.setText(R.string.mobile_denoise_error);
            if (last != null) {
                String s = last.optString("state");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!s.equals("tooLarge") && !s.equals("notApplicable"));
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(last.optBoolean("enabled"));
            } else {
                // Keep a way to recover from a transient storage error.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.retry);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> query("noise_status", args(), false));
            }
        });
    }

    private void update(JSONObject value) {
        last = value;
        String state = value.optString("state", "failed");
        boolean running = state.equals("running") || state.equals("queued");
        preparing = running;
        progress.setVisibility(running ? View.VISIBLE : View.GONE);
        amountBox.setVisibility(state.equals("ready") ? View.VISIBLE : View.GONE);
        if (!amountChosen) {
            int previous = value.optInt("amount", 0);
            amount.setProgress(previous > 0 ? previous : 50);
            amountLabel(amount.getProgress());
        }
        TextView positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setOnClickListener(v -> onPositive());
        positive.setText(state.equals("ready") ? R.string.mobile_denoise_apply : R.string.mobile_denoise_prepare);
        positive.setEnabled(state.equals("ready") || state.equals("idle") || state.equals("failed"));
        TextView neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        neutral.setVisibility(value.optBoolean("enabled") ? View.VISIBLE : View.GONE);
        neutral.setEnabled(!running);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(running ? android.R.string.cancel : R.string.mobile_denoise_close);
        if (running) {
            int total = value.optInt("total");
            int done = value.optInt("done");
            progress.setIndeterminate(total == 0);
            if (total > 0) {
                progress.setProgress((int)(100L * done / total));
                status.setText(activity.getString(R.string.mobile_denoise_progress, done, total));
            } else {
                status.setText(R.string.mobile_denoise_busy);
            }
            ticks.removeCallbacks(tick);
            ticks.postDelayed(tick, 700);
            return;
        }
        int text = switch (state) {
            case "ready" -> R.string.mobile_denoise_ready;
            case "idle" -> R.string.mobile_denoise_intro;
            case "notApplicable" -> R.string.mobile_denoise_not_raw;
            case "tooLarge" -> R.string.mobile_denoise_too_big;
            default -> R.string.mobile_denoise_error;
        };
        status.setText(text);
    }

    private JSONObject args() {
        JSONObject p = new JSONObject();
        put(p, "id", photo);
        return p;
    }

    private static void put(JSONObject p, String key, Object value) {
        try { p.put(key, value); } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); }
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(activity);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private TextView label(int resource) {
        TextView text = new TextView(activity);
        text.setText(resource);
        text.setTextSize(15);
        return text;
    }

    private void amountLabel(int value) {
        String label = activity.getString(R.string.mobile_denoise_amount, value);
        amountText.setText(label);
        amount.setStateDescription(label);
    }

    private int dp(int n) {
        return Math.round(n * activity.getResources().getDisplayMetrics().density);
    }
}

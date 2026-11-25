package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native workflows shared with desktop looks, keeping filesystem access in the Android picker. */
final class MobileLooks {
    static final int REQUEST_IMPORT = 104;
    private static final long MAX_ASSET = 16L << 20;
    private static final String INBOX = "LookInbox";

    interface Host {
        void call(String op, JSONObject args, Consumer<JSONObject> accepted);
        void task(Callable<JSONObject> worker, Consumer<JSONObject> accepted);
        JSONObject request(String op, JSONObject args) throws Exception;
    }

    private final Activity activity;
    private final Host host;
    private record Asset(String key, String name, String group, boolean favorite) {}
    private record Choice(int label, Runnable run) {}

    MobileLooks(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    static boolean affectsPreview(String op) {
        return switch (op) {
            case "looks.applyPreset", "looks.applyProfile", "looks.paste", "looks.pastePrevious",
                "looks.restoreVersion", "looks.restoreHistory" -> true;
            default -> false;
        };
    }

    void show(long photo) {
        call("looks.summary", photo, result -> showMenu(photo, result.optBoolean("clipboard"), result.optBoolean("previous")));
    }

    private void showMenu(long photo, boolean clipboard, boolean previous) {
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(R.string.look_presets, () -> assets(photo, false)));
        choices.add(new Choice(R.string.look_profiles, () -> assets(photo, true)));
        choices.add(new Choice(R.string.look_history, () -> timeline(photo, false)));
        choices.add(new Choice(R.string.look_versions, () -> timeline(photo, true)));
        choices.add(new Choice(R.string.look_copy, () -> selectGroups(
            groups -> call("looks.copy", photo, r -> notifySaved(R.string.look_copied), "groups", groups))));
        if (clipboard) choices.add(new Choice(R.string.look_paste, () ->
            call("looks.paste", photo, r -> notifySaved(R.string.look_applied))));
        if (previous) choices.add(new Choice(R.string.look_paste_previous, () ->
            call("looks.pastePrevious", photo, r -> notifySaved(R.string.look_applied))));
        choices.add(new Choice(R.string.look_import, this::pickAsset));
        menu(R.string.look_tools, choices);
    }

    private void assets(long photo, boolean profiles) {
        call(profiles ? "looks.profiles" : "looks.presets", photo,
            result -> drawAssets(photo, profiles, result.optJSONArray("items")));
    }

    private void drawAssets(long photo, boolean profiles, JSONArray input) {
        List<Asset> list = new ArrayList<>();
        for (int i = 0; input != null && i < input.length(); ++i) {
            JSONObject row = input.optJSONObject(i);
            if (row != null && !row.optString("id").isEmpty()) {
                list.add(new Asset(row.optString("id"), row.optString("name"), row.optString("group"), row.optBoolean("favorite")));
            }
        }
        list.sort(Comparator.comparing(Asset::favorite).reversed()
            .thenComparing(Asset::group, String.CASE_INSENSITIVE_ORDER).thenComparing(Asset::name, String.CASE_INSENSITIVE_ORDER));
        int offset = profiles ? 0 : 1;
        int title = profiles ? R.string.look_profiles : R.string.look_presets;
        String[] names = new String[list.size() + offset];
        if (!profiles) names[0] = s(R.string.look_create_preset);
        for (int i = 0; i < list.size(); ++i) {
            Asset row = list.get(i);
            names[i + offset] = (row.favorite() ? "★  " : "")
                + (row.group().isEmpty() ? "" : shortLabel(row.group(), 36) + " · ")
                + shortLabel(row.name(), 90);
        }
        new AlertDialog.Builder(activity).setTitle(title).setItems(names, (d, i) -> {
            if (!profiles && i == 0) {
                enterName(R.string.look_create_preset, "", name -> selectGroups(groups ->
                    call("looks.createPreset", photo, r -> {
                        notifySaved(R.string.look_preset_created);
                        assets(photo, false);
                    }, "name", name, "groups", groups)));
            } else {
                assetChoices(photo, profiles, list.get(i - offset));
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void assetChoices(long photo, boolean profile, Asset item) {
        String op = profile ? "looks.applyProfile" : "looks.applyPreset";
        String star = profile ? "looks.profileFavorite" : "looks.presetFavorite";
        String[] options = {
            s(R.string.look_apply), s(R.string.look_apply_amount),
            s(item.favorite() ? R.string.look_unfavorite : R.string.look_favorite)
        };
        new AlertDialog.Builder(activity).setTitle(shortLabel(item.name(), 90))
            .setItems(options, (d, i) -> {
                if (i == 0) call(op, photo, r -> notifySaved(R.string.look_applied), "key", item.key(), "amount", 100);
                else if (i == 1) amount(shortLabel(item.name(), 90),
                    value -> call(op, photo, r -> notifySaved(R.string.look_applied), "key", item.key(), "amount", value));
                else call(star, photo, r -> assets(photo, profile), "key", item.key());
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void amount(String title, Consumer<Integer> onApply) {
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView value = new TextView(activity);
        page.addView(value);
        SeekBar slider = new SeekBar(activity);
        slider.setContentDescription(s(R.string.look_amount));
        slider.setMax(200);
        slider.setProgress(100);
        slider.setMinimumHeight(dp(48));
        value.setText(activity.getString(R.string.look_percent, 100));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            @Override public void onProgressChanged(SeekBar seekBar, int n, boolean byUser) {
                value.setText(activity.getString(R.string.look_percent, n));
            }
        });
        page.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        new AlertDialog.Builder(activity).setTitle(title).setView(page)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.look_apply, (d, w) -> onApply.accept(slider.getProgress())).show();
    }

    private void timeline(long photo, boolean versions) {
        call("looks.history", photo, data -> {
            JSONArray rows = data.optJSONArray(versions ? "versions" : "history");
            int count = rows == null ? 0 : rows.length();
            int cursor = data.optInt("cursor", -1);
            int offset = versions ? 1 : 0;
            String[] labels = new String[count + offset];
            if (versions) labels[0] = s(R.string.look_create_version);
            if (!versions && count == 0) {
                new AlertDialog.Builder(activity).setTitle(R.string.look_history)
                    .setMessage(R.string.look_no_history).setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            for (int i = 0; i < count; ++i) {
                int source = count - 1 - i;
                JSONObject version = versions ? rows.optJSONObject(source) : null;
                String text = versions && version != null ? version.optString("name") : rows.optString(source);
                if (!versions && text.equals("Before first mobile edit")) text = s(R.string.look_baseline);
                labels[i + offset] = (cursor == source && !versions ? "✓  " : "") + shortLabel(text, 100);
            }
            new AlertDialog.Builder(activity).setTitle(versions ? R.string.look_versions : R.string.look_history)
                .setItems(labels, (d, i) -> {
                    if (versions && i == 0) {
                        enterName(R.string.look_create_version, "", name -> call("looks.createVersion", photo,
                            r -> { notifySaved(R.string.look_version_created); timeline(photo, true); }, "name", name));
                        return;
                    }
                    int source = count - 1 - (i - offset);
                    if (!versions) call("looks.restoreHistory", photo, r -> notifySaved(R.string.look_applied), "index", source);
                    else versionChoices(photo, source, rows.optJSONObject(source));
                }).setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    private void versionChoices(long photo, int index, JSONObject row) {
        String name = row == null ? "" : shortLabel(row.optString("name"), 100);
        CharSequence[] choices = {s(R.string.look_restore_version), s(R.string.look_rename_version), s(R.string.look_update_version)};
        new AlertDialog.Builder(activity).setTitle(name).setItems(choices, (d, i) -> {
            if (i == 0) call("looks.restoreVersion", photo, r -> notifySaved(R.string.look_applied), "index", index);
            else if (i == 1) enterName(R.string.look_rename_version, name, result ->
                call("looks.renameVersion", photo, r -> timeline(photo, true), "index", index, "name", result));
            else new AlertDialog.Builder(activity).setTitle(R.string.look_update_version)
                .setMessage(R.string.look_replace_version).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                    call("looks.updateVersion", photo, r -> timeline(photo, true), "index", index)).show();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void enterName(int title, String old, Consumer<String> useName) {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        field.setText(old);
        field.setHint(R.string.look_name);
        field.setContentDescription(s(R.string.look_name));
        LinearLayout parent = new LinearLayout(activity);
        parent.setPadding(dp(20), dp(8), dp(20), 0);
        parent.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(parent)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = field.getText().toString().trim();
            if (text.isEmpty()) { field.setError(s(R.string.look_name_required)); return; }
            dialog.dismiss();
            useName.accept(text);
        }));
        dialog.show();
    }

    private void selectGroups(Consumer<JSONArray> onApply) {
        String[] keys = {"profile", "treatment", "whiteBalance", "light", "toneCurve", "color", "colorMixer",
            "colorGrading", "effects", "vignette", "grain", "detail", "optics", "geometry", "crop", "masks",
            "spots", "redEye", "lensBlur", "calibration"};
        int[] text = {R.string.look_group_profile, R.string.look_group_treatment, R.string.look_group_wb, R.string.look_group_light,
            R.string.look_group_curve, R.string.look_group_color, R.string.look_group_mixer, R.string.look_group_grading,
            R.string.look_group_effects, R.string.look_group_vignette, R.string.look_group_grain, R.string.look_group_detail,
            R.string.look_group_optics, R.string.look_group_geometry, R.string.look_group_crop, R.string.look_group_masks,
            R.string.look_group_spots, R.string.look_group_redeye, R.string.look_group_blur, R.string.look_group_calibration};
        String[] labels = new String[text.length];
        boolean[] checked = new boolean[text.length];
        for (int i = 0; i < text.length; ++i) {
            labels[i] = s(text[i]);
            checked[i] = i < 14 || i > 17;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.look_choose_groups)
            .setMultiChoiceItems(labels, checked, (d, i, on) -> checked[i] = on)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            JSONArray picked = new JSONArray();
            for (int i = 0; i < keys.length; ++i) if (checked[i]) picked.put(keys[i]);
            if (picked.length() == 0) { notifySaved(R.string.look_pick_group); return; }
            dialog.dismiss();
            onApply.accept(picked);
        }));
        dialog.show();
    }

    private void pickAsset() {
        new AlertDialog.Builder(activity).setTitle(R.string.look_import)
            .setMessage(R.string.look_import_info)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.look_choose_file, (d, w) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    activity.startActivityForResult(intent, REQUEST_IMPORT);
                } catch (RuntimeException e) {
                    notifySaved(R.string.look_error_import);
                }
            }).show();
    }

    void onImportResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        host.task(() -> {
            // The picker may return after Android has restarted the app process.
            host.request("open", args("root", activity.getFilesDir().getCanonicalPath()));
            File file;
            try {
                file = Documents.copy(activity.getContentResolver(), uri, new File(activity.getFilesDir(), INBOX), MAX_ASSET);
            } catch (IOException | RuntimeException e) {
                throw new LabEngine.Failure("look_import");
            }
            try {
                File parent = file.getParentFile();
                return host.request("looks.import", args("token", parent.getName(), "name", file.getName()));
            } finally {
                file.delete();
                file.getParentFile().delete();
            }
        }, result -> {
            int imported = result.optInt("imported");
            int skipped = result.optInt("skipped");
            int effects = result.optInt("effects");
            int failures = result.optInt("failed");
            new AlertDialog.Builder(activity).setTitle(R.string.look_import)
                .setMessage(activity.getString(R.string.look_import_result, imported, effects, skipped, failures))
                .setPositiveButton(android.R.string.ok, null).show();
        });
    }

    private void menu(int title, List<Choice> choices) {
        String[] labels = choices.stream().map(c -> s(c.label())).toArray(String[]::new);
        new AlertDialog.Builder(activity).setTitle(title).setItems(labels, (d, i) -> choices.get(i).run().run())
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void call(String op, long photo, Consumer<JSONObject> accepted, Object... values) {
        JSONObject p = args(values);
        try {
            p.put("photo", photo);
        } catch (JSONException x) {
            throw new IllegalArgumentException(x);
        }
        host.call(op, p, accepted);
    }

    private static JSONObject args(Object... values) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i + 1 < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        } catch (JSONException x) {
            throw new IllegalArgumentException(x);
        }
        return result;
    }

    private static String shortLabel(String value, int max) {
        String v = value.replaceAll("[\\p{Cntrl}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}]", " ").trim();
        if (v.codePointCount(0, v.length()) <= max) return v;
        return v.substring(0, v.offsetByCodePoints(0, max)) + "…";
    }

    private void notifySaved(int id) { Toast.makeText(activity, id, Toast.LENGTH_SHORT).show(); }
    private String s(int id) { return activity.getString(id); }
    private int dp(int n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }
}

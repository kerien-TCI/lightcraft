package ai.storyteller.lightcraft;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Presentation of the engine's numeric develop schema and desktop translation table. */
final class MobileDevelopTools {
    record Panel(String id, String label) {}
    static final List<Panel> PANELS = List.of(
        new Panel("curve", "Tone Curve"), new Panel("mixer", "Color Mixer"),
        new Panel("bwMix", "B&W Mixer"), new Panel("grading", "Color Grading"),
        new Panel("pointColor", "Point Color"), new Panel("vignette", "Vignette"),
        new Panel("grain", "Grain"), new Panel("detail", "Detail"),
        new Panel("optics", "Optics"), new Panel("geometry", "Geometry"),
        new Panel("calibration", "Calibration"), new Panel("lensBlur", "Lens Blur"),
        new Panel("hdr", "HDR"), new Panel("profile", "Profile"));

    record Slider(String key, String label, double min, double max, double step, double initial, int decimals,
                  String track, String from, String to) {
        int steps() { return "temperature".equals(track) || "temp".equals(track) ? 1000 : Math.max(1, (int)Math.round((max - min) / step)); }
        double value(int n) {
            double fraction = n / (double)steps();
            double raw = min + fraction * (max - min);
            if ("temp".equals(track) && min < 7000 && max > 7000) {
                raw = fraction <= .5 ? min + 2 * fraction * (7000 - min)
                    : 7000 + 1000 * Math.expm1((2 * fraction - 1) * Math.log1p((max - 7000) / 1000));
            }
            return Math.max(min, Math.min(max, min + Math.round((raw - min) / step) * step));
        }
        int position(double value) {
            double fraction = (value-min) / (max-min);
            if ("temp".equals(track) && min < 7000 && max > 7000) {
                fraction = value <= 7000 ? .5 * (value-min)/(7000-min)
                    : .5 + .5 * Math.log1p((value-7000)/1000)/Math.log1p((max-7000)/1000);
            }
            return (int)Math.max(0,Math.min(steps(),Math.round(fraction*steps())));
        }
    }

    private final JSONObject translated;

    MobileDevelopTools(Context context) {
        JSONObject found = new JSONObject();
        if ("fr".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage())) {
            try (InputStream stream = context.getAssets().open("locales/fr.json")) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = stream.read(buffer)) != -1; ) data.write(buffer, 0, n);
                found = new JSONObject(new String(data.toByteArray(), StandardCharsets.UTF_8));
            } catch (IOException | JSONException fallback) { /* English fallback for missing upstream locale. */ }
        }
        translated = found;
    }

    String tr(String label) {
        if ("Tall".equals(label) && translated.length()>0) return "Haute";
        return translated.optString(label, label);
    }

    String title(String id) {
        return PANELS.stream().filter(panel -> panel.id().equals(id)).map(panel -> tr(panel.label())).findFirst().orElse(id);
    }

    static boolean quick(String key) { return "light".equals(key) || "color".equals(key) || "effects".equals(key); }

    List<Slider> sliders(JSONArray controls, String group) {
        ArrayList<Slider> result = new ArrayList<>();
        for (int i = 0; i < controls.length(); i++) {
            JSONObject row = controls.optJSONObject(i);
            if (row == null) continue;
            String key = row.optString("id"), section = row.optString("section");
            boolean show = switch (group) {
                case "light" -> key.startsWith("light.");
                case "hdr" -> key.startsWith("hdr.");
                default -> group.equals(section);
            };
            if (!show) continue;
            String label = tr(row.optString("label"));
            // Name the repeated controls by panel/role, instead of three indistinguishable “Detail” sliders.
            label = switch (key) {
                case "detail.sharpenDetail" -> tr("Sharpening") + " · " + label;
                case "detail.nrDetail" -> tr("Noise Reduction") + " · " + label;
                case "detail.nrColorDetail" -> tr("Color Noise Reduction") + " · " + label;
                default -> label;
            };
            if (key.startsWith("pointColor.") || key.startsWith("redEye.")) {
                String[] parts = key.split("\\.");
                if (parts.length == 3) label = "#" + (Integer.parseInt(parts[1])+1) + " · " + label;
            }
            double min = row.optDouble("min"), max = row.optDouble("max"), step = row.optDouble("step");
            if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step) || max <= min || step <= 0) continue;
            JSONObject track = row.optJSONObject("track");
            result.add(new Slider(key, label, min, max, step, row.optDouble("default",min),
                Math.max(0,Math.min(4,row.optInt("decimals"))),
                track==null?"plain":track.optString("kind"),track==null?"":track.optString("from"),track==null?"":track.optString("to")));
        }
        return result;
    }
}

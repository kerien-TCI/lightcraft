package ai.storyteller.lightcraft;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Mobile API; this deliberately exposes no desktop commands, user paths, or automation socket. */
final class NativeBridge implements LabEngine {
    static {
        System.loadLibrary("main");
    }

    private static native byte[] nativeRequest(String action, String args);

    @Override public byte[] request(String action, JSONObject args) throws Exception {
        byte[] result = nativeRequest(action, args.toString());
        if (result == null || result.length == 0) throw new LabEngine.Failure("unavailable");
        if (result[0] == '{') {
            JSONObject object = read(result);
            if (object.has("error")) throw new LabEngine.Failure(object.getString("error"));
        }
        return result;
    }

    static JSONObject read(byte[] bytes) throws JSONException {
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
}

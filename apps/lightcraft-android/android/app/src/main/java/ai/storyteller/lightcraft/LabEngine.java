package ai.storyteller.lightcraft;

import org.json.JSONObject;

/** Package-private seam for Android UI tests; production always binds to NativeBridge. */
interface LabEngine {
    byte[] request(String action, JSONObject args) throws Exception;

    final class Failure extends Exception {
        final String code;
        Failure(String code) { this.code = code; }
    }
}

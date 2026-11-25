package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native photo merge. Every write is an app-private derivative of previously imported photos. */
final class MobileMerge {
    interface Request { void call(String op,JSONObject args,Consumer<JSONObject> result,Consumer<Throwable> error); }
    private final Activity activity;
    private final Request bridge;
    private final Runnable refresh;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable next=this::poll;
    private AlertDialog progressDialog;
    private ProgressBar progress;
    private TextView status;
    private boolean pending;

    MobileMerge(Activity activity,Request bridge,Runnable refresh) {
        this.activity=activity; this.bridge=bridge; this.refresh=refresh;
    }

    void show(JSONArray ids) {
        bridge.call("merge_status",new JSONObject(),state -> {
            if (active(state)) { showProgress(); update(state); return; }
            if(ids.length()<2 || ids.length()>12) { notice(R.string.merge_count); return; }
            int[] labels=ids.length()<4?new int[]{R.string.merge_hdr,R.string.merge_pano}:
                new int[]{R.string.merge_hdr,R.string.merge_pano,R.string.merge_combined};
            CharSequence[] names=new CharSequence[labels.length];
            for(int i=0;i<labels.length;i++) names[i]=activity.getText(labels[i]);
            new AlertDialog.Builder(activity).setTitle(R.string.merge_title).setItems(names,(d,n)->options(ids,n))
                .setNegativeButton(android.R.string.cancel,null).show();
        },this::showError);
    }

    private void options(JSONArray ids,int kind) {
        LinearLayout body=new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView hint=new TextView(activity);
        hint.setText(R.string.merge_derivative); body.addView(hint);
        CheckBox alignment=checkbox(body,R.string.merge_align,true);
        Spinner deghost=spinner(body,R.string.merge_deghost,new int[]{R.string.merge_none,R.string.merge_low,R.string.merge_medium,R.string.merge_high});
        if(kind==1) { alignment.setVisibility(View.GONE); ((View)deghost.getParent()).setVisibility(View.GONE); }
        Spinner projection=spinner(body,R.string.merge_projection,new int[]{R.string.merge_auto,R.string.merge_spherical,R.string.merge_cylindrical,R.string.merge_perspective});
        CheckBox crop=checkbox(body,R.string.merge_crop,true),fill=checkbox(body,R.string.merge_fill,false);
        if(kind==0) { ((View)projection.getParent()).setVisibility(View.GONE); crop.setVisibility(View.GONE); fill.setVisibility(View.GONE); }
        Spinner bracket=spinner(body,R.string.merge_bracket,new int[]{R.string.merge_auto,R.string.merge_bracket2,R.string.merge_bracket3,R.string.merge_bracket4,R.string.merge_bracket5});
        if(kind!=2) ((View)bracket.getParent()).setVisibility(View.GONE);
        CheckBox auto=checkbox(body,R.string.merge_adjust,true);
        CheckBox reduced=checkbox(body,R.string.merge_reduced,false);
        ScrollView view=new ScrollView(activity); view.addView(body);
        new AlertDialog.Builder(activity).setTitle(R.string.merge_title).setView(view).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.merge_begin,(d,w)->{
                String[] keys={"hdr","panorama","hdrPanorama"}, stages={"none","low","medium","high"}, projections={"auto","spherical","cylindrical","perspective"};
                int count=bracket.getSelectedItemPosition();
                JSONObject args=object("ids",ids,"kind",keys[kind],"align",alignment.isChecked(),
                    "deghost",stages[deghost.getSelectedItemPosition()],"projection",projections[projection.getSelectedItemPosition()],
                    "autoCrop",crop.isChecked(),"fillEdges",fill.isChecked(),"autoSettings",auto.isChecked(),
                    "bracket",count==0?0:count+1,"budgetMiB",budgetMiB(),"reduced",reduced.isChecked());
                showProgress(); pending=true;
                bridge.call("merge_start",args,x->{pending=false;update(x);},x->{pending=false;dismissProgress();showError(x);});
            }).show();
    }

    private void showProgress() {
        if(progressDialog!=null) return;
        LinearLayout panel=new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(20),dp(8),dp(20),dp(8));
        status=new TextView(activity); status.setText(R.string.merge_working);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(status);
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); panel.addView(progress,new LinearLayout.LayoutParams(-1,dp(36)));
        progressDialog=new AlertDialog.Builder(activity).setTitle(R.string.merge_title).setView(panel)
            .setNegativeButton(R.string.merge_cancel,null).setCancelable(false).create();
        progressDialog.show();
        progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v-> {
            if(pending) return;
            bridge.call("merge_cancel",new JSONObject(),this::update,this::showError);
        });
    }

    private void poll() {
        if(progressDialog==null || activity.isFinishing() || activity.isDestroyed()) return;
        bridge.call("merge_status",new JSONObject(),this::update,e->{dismissProgress();showError(e);});
    }

    private static boolean active(JSONObject state) { return "running".equals(state.optString("state")) || "cancelling".equals(state.optString("state")); }

    private void update(JSONObject state) {
        ui.removeCallbacks(next);
        if(activity.isFinishing() || activity.isDestroyed()) return;
        if(active(state)) {
            showProgress();
            boolean cancelling="cancelling".equals(state.optString("state"));
            progress.setProgress(state.optInt("progress"),true);
            status.setText(cancelling?R.string.merge_cancelling:R.string.merge_working);
            progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!cancelling);
            ui.postDelayed(next,850);
            return;
        }
        dismissProgress();
        switch(state.optString("state")) {
            case "ready" -> { refresh.run(); notice(R.string.merge_saved); }
            case "cancelled" -> notice(R.string.merge_cancelled);
            case "failed" -> notice(errorId(state.optString("reason")));
            default -> { }
        }
    }

    void close() { ui.removeCallbacks(next); dismissProgress(); }

    private void dismissProgress() {
        ui.removeCallbacks(next);
        if(progressDialog!=null) { progressDialog.dismiss(); progressDialog=null; }
    }

    private int budgetMiB() {
        ActivityManager service=(ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
        if(service==null) return 400;
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        service.getMemoryInfo(info);
        return (int)Math.min(1200,Math.max(272,(info.availMem-info.threshold)/(4L<<20)));
    }

    private int dp(int px) { return Math.round(px*activity.getResources().getDisplayMetrics().density); }
    private CheckBox checkbox(LinearLayout pane,int text,boolean value) {
        CheckBox b=new CheckBox(activity); b.setText(text);b.setMinHeight(dp(48));b.setChecked(value);pane.addView(b);return b;
    }
    private Spinner spinner(LinearLayout pane,int title,int[] entries) {
        LinearLayout group=new LinearLayout(activity); group.setOrientation(LinearLayout.VERTICAL); pane.addView(group);
        TextView label=new TextView(activity);label.setText(title); group.addView(label);
        String[] names=new String[entries.length];
        for(int i=0;i<entries.length;i++) names[i]=activity.getString(entries[i]);
        Spinner list=new Spinner(activity);
        ArrayAdapter<String> values=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,names);
        values.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);list.setAdapter(values);
        list.setContentDescription(activity.getString(title));list.setMinimumHeight(dp(48));group.addView(list,new LinearLayout.LayoutParams(-1,dp(48)));
        return list;
    }

    private void showError(Throwable e) { notice(errorId(e instanceof LabEngine.Failure ? ((LabEngine.Failure)e).code : "")); }
    private void notice(int text) { if(!activity.isFinishing()&&!activity.isDestroyed()) new AlertDialog.Builder(activity).setTitle(R.string.merge_title)
        .setMessage(text).setPositiveButton(android.R.string.ok,null).show(); }

    private static int errorId(String code) { return switch(code) {
        case "merge_count" -> R.string.merge_count;
        case "merge_memory" -> R.string.merge_memory;
        case "merge_options" -> R.string.merge_options;
        case "merge_source" -> R.string.merge_source;
        case "merge_write" -> R.string.merge_write;
        default -> R.string.merge_failed;
    }; }
    private static JSONObject object(Object... values) {
        JSONObject out=new JSONObject();
        try { for(int i=0;i<values.length;i+=2) out.put((String)values[i],values[i+1]); }
        catch(JSONException e){throw new IllegalArgumentException(e);}return out;
    }
}

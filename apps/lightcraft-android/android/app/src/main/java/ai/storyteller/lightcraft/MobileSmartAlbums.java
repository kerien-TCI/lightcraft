package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native, recursive editor using the same schema and validation as the desktop catalogue. */
final class MobileSmartAlbums {
    interface Request { void call(String op, JSONObject args, Consumer<JSONObject> done, Consumer<Throwable> fail); }
    private final Activity activity;
    private final Request request;
    private final Runnable refresh;
    private final MobileDevelopTools words;
    private static final List<String> MODES=List.of("all","any","none");

    MobileSmartAlbums(Activity activity, Request request, Runnable refresh) {
        this.activity=activity; this.request=request;this.refresh=refresh;words=new MobileDevelopTools(activity);
    }

    void show(long id) {
        JSONObject args=id>=0?object("id",id):new JSONObject();
        request.call("smart_open",args,schema -> editor(id,schema),this::error);
    }
    private void editor(long id,JSONObject schema) {
        JSONObject rules=schema.optJSONObject("rules");
        if(rules==null) rules=object("match","all","rules",new JSONArray());
        final JSONObject root=rules;
        final JSONArray fields=array(schema,"fields"), albums=array(schema,"albums"), folders=array(schema,"folders");
        LinearLayout body=vertical();body.setPadding(dp(16),0,dp(16),dp(16));
        EditText name=input(activity.getString(R.string.smart_name),schema.optString("name"),120);
        body.addView(name);
        Spinner folder=null;
        if(id<0 && folders.length()>0) {
            List<String> fl=new ArrayList<>(); fl.add(activity.getString(R.string.smart_root));
            for(int i=0;i<folders.length();i++) fl.add(folders.optJSONObject(i).optString("name"));
            folder=spinner(fl,0,activity.getString(R.string.smart_folder));
            body.addView(folder);
        }
        final Spinner home=folder;
        if(schema.optBoolean("legacy")) emit(body,activity.getString(R.string.smart_keep_legacy));
        LinearLayout group=vertical();body.addView(group);
        drawGroup(group,root,fields,albums,1);
        TextView status=emit(body,"");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ScrollView scroll=new ScrollView(activity);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(id<0?R.string.smart_create:R.string.smart_edit)
            .setView(scroll).setNegativeButton(android.R.string.cancel,null).setNeutralButton(R.string.smart_count,null)
            .setPositiveButton(R.string.smart_save,null).create();
        dialog.show();
        if(dialog.getWindow()!=null) dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        final Consumer<Boolean> send=save-> {
            JSONObject payload=object("rules",root,"name",name.getText().toString());
            if(id>=0) put(payload,"id",id);
            if(home!=null && home.getSelectedItemPosition()>0) {
                put(payload,"parent",folders.optJSONObject(home.getSelectedItemPosition()-1).optLong("id"));
            }
            status.setText(R.string.smart_checking);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            // Snapshot an editable tree so mutating the dialog cannot alter a worker-owned payload.
            final JSONObject snapshot;
            try {snapshot=new JSONObject(payload.toString());}
            catch(JSONException e) {throw new IllegalStateException(e);}
            request.call(save?"smart_save":"smart_preview",snapshot,reply-> {
                if(!dialog.isShowing())return;
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                JSONArray problems=reply.optJSONArray("issues");
                if(problems!=null&&problems.length()>0) {status.setText(issues(problems));reveal(scroll);return;}
                if(save) {dialog.dismiss();refresh.run();}
                else {status.setText(activity.getString(R.string.smart_matched,reply.optInt("count")));reveal(scroll);}
            },e->{
                if(!dialog.isShowing())return;
                status.setText(failure(e));
                reveal(scroll);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
            });
        };
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->send.accept(true));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->send.accept(false));
    }

    private static void reveal(ScrollView scroll) { scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); }

    private void drawGroup(LinearLayout box,JSONObject group,JSONArray fields,JSONArray albums,int depth) {
        box.removeAllViews();
        if(depth>4){emit(box,activity.getString(R.string.smart_invalid));return;}
        if(!group.has("rules"))put(group,"rules",new JSONArray());
        final JSONArray rules=array(group,"rules");
        Spinner matcher=spinner(List.of(activity.getString(R.string.smart_all),activity.getString(R.string.smart_any),
            activity.getString(R.string.smart_none)),Math.max(0,MODES.indexOf(group.optString("match"))),activity.getString(R.string.smart_match));
        onChoice(matcher,index->put(group,"match",MODES.get(index)));
        box.addView(matcher);
        for(int i=0;i<rules.length();i++) {
            JSONObject rule=rules.optJSONObject(i);
            if(rule==null)continue;
            final int index=i;
            LinearLayout card=vertical();card.setPadding(dp(6),dp(4),dp(4),dp(4));
            box.addView(card);
            if(rule.optJSONObject("group")!=null){
                emit(card,activity.getString(R.string.smart_group_number,i+1));
                drawGroup(card,rule.optJSONObject("group"),fields,albums,depth+1);
            } else {
                JSONObject field=lookup(fields,"field",rule.optString("field"));
                String label=field==null?rule.optString("field"):words.tr(field.optString("label"));
                String op=field==null?rule.optString("op"):prettyOp(field,rule.optString("op"));
                Button edit=button(label+" "+op+" "+displayValue(field,albums,rule),()-> {
                    Consumer<JSONObject> apply=f->{put(rules,index,f);drawGroup(box,group,fields,albums,depth);};
                    if(field==null)chooseField(fields,albums,rule,apply);
                    else editValue(field,albums,rule,apply);
                });
                card.addView(edit);
            }
            Button remove=button(activity.getString(R.string.smart_remove),()->{
                rules.remove(index); drawGroup(box,group,fields,albums,depth);
            });
            card.addView(remove);
        }
        box.addView(button(activity.getString(R.string.smart_add_rule),()->{
            if(rules.length()<48) chooseField(fields,albums,null, rule->{rules.put(rule);drawGroup(box,group,fields,albums,depth);});
        }));
        if(depth<4)box.addView(button(activity.getString(R.string.smart_add_group),()->{
            if(rules.length()<48) {rules.put(object("group",object("match","all","rules",new JSONArray())));drawGroup(box,group,fields,albums,depth);}
        }));
    }

    private String displayValue(JSONObject field,JSONArray albums,JSONObject rule) {
        if(rule.isNull("value"))return "";
        Object value=rule.opt("value");
        if(field==null)return String.valueOf(value);
        if("album".equals(field.optString("kind"))) {
            for(int i=0;i<albums.length();i++) {
                JSONObject album=albums.optJSONObject(i);
                if(String.valueOf(value).equals(album.optString("id")))return album.optString("name");
            }
        }
        if(value instanceof Boolean)return activity.getString((Boolean)value?R.string.smart_yes:R.string.smart_no);
        JSONObject choice=field.optJSONObject("choiceLabels");
        if(choice!=null)return words.tr(choice.optString(String.valueOf(value),String.valueOf(value)));
        if(value instanceof JSONArray) {
            JSONArray range=(JSONArray)value;
            return range.optString(0)+" … "+range.optString(1);
        }
        return String.valueOf(value);
    }

    private void chooseField(JSONArray fields,JSONArray albums,JSONObject existing,Consumer<JSONObject> done) {
        ArrayList<CharSequence> labels=new ArrayList<>();
        ArrayList<Runnable> actions=new ArrayList<>();
        Map<String,List<JSONObject>> groups=new LinkedHashMap<>();
        for(int i=0;i<fields.length();i++){
            JSONObject f=fields.optJSONObject(i);
            if(f==null)continue;
            String group=f.isNull("group")?"":f.optString("group","");
            if(!group.isEmpty()) {groups.computeIfAbsent(group,ignored->new ArrayList<>()).add(f);continue;}
            labels.add(words.tr(f.optString("label")));
            actions.add(()->chooseValue(f,albums,existing,done));
        }
        groups.forEach((group,items)->{
            labels.add(words.tr(group)+" ›");
            actions.add(()-> {
                CharSequence[] names=items.stream().map(f->words.tr(f.optString("label"))).toArray(CharSequence[]::new);
                new AlertDialog.Builder(activity).setTitle(words.tr(group)).setItems(names,(d,i)->chooseValue(items.get(i),albums,existing,done))
                    .setNegativeButton(android.R.string.cancel,null).setNeutralButton(R.string.smart_field,(d,i)->chooseField(fields,albums,existing,done)).show();
            });
        });
        new AlertDialog.Builder(activity).setTitle(R.string.smart_field)
            .setItems(labels.toArray(new CharSequence[0]),(d,i)->actions.get(i).run())
            .setNegativeButton(android.R.string.cancel,null).show();
    }
    private void chooseValue(JSONObject field,JSONArray albums,JSONObject existing,Consumer<JSONObject> done) {
        editValue(field,albums,existing!=null&&field.optString("field").equals(existing.optString("field"))?existing:null,done);
    }

    private void editValue(JSONObject field,JSONArray albums,JSONObject old,Consumer<JSONObject> done) {
        JSONArray ops=array(field,"ops");
        String kind=field.optString("kind");
        ArrayList<String> labels=new ArrayList<>(),keys=new ArrayList<>();
        for(int i=0;i<ops.length();i++) {JSONObject item=ops.optJSONObject(i); keys.add(item.optString("op"));labels.add(words.tr(item.optString("label")));}
        if(keys.isEmpty())return;
        LinearLayout form=vertical(); form.setPadding(dp(16),0,dp(16),dp(14));
        LinearLayout value=vertical();
        final java.util.function.Supplier<?>[] reader={()->JSONObject.NULL};
        Spinner operators=spinner(labels,old==null?0:Math.max(0,keys.indexOf(old.optString("op"))),activity.getString(R.string.smart_operator));
        form.addView(operators);form.addView(value);
        Consumer<Integer> build=i -> {
            String op=keys.get(i);value.removeAllViews();
            Object v=old!=null&&op.equals(old.optString("op"))?old.opt("value"):null;
            reader[0]=valueField(value,field,albums,kind,op,v);
        };
        build.accept(operators.getSelectedItemPosition());onChoice(operators,build);
        new AlertDialog.Builder(activity).setTitle(words.tr(field.optString("label"))).setView(form)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.smart_apply,(d,n)->
                done.accept(object("field",field.optString("field"),"op",keys.get(operators.getSelectedItemPosition()),"value",reader[0].get()))).show();
    }

    private java.util.function.Supplier<Object> valueField(LinearLayout box,JSONObject field,JSONArray albums,String kind,String op,Object before) {
        if(op.equals("isEmpty")||op.equals("isNotEmpty"))return ()->JSONObject.NULL;
        String old=before==null||before==JSONObject.NULL?"":String.valueOf(before);
        if("bool".equals(kind)) {
            Spinner choice=spinner(List.of(activity.getString(R.string.smart_yes),activity.getString(R.string.smart_no)),
                "false".equals(old)||"no".equals(old)?1:0,activity.getString(R.string.smart_value));
            box.addView(choice);return ()->choice.getSelectedItemPosition()==0;
        }
        if("choice".equals(kind)||"album".equals(kind)) {
            List<Object> values=new ArrayList<>();List<String> labels=new ArrayList<>();
            if("album".equals(kind)){
                for(int i=0;i<albums.length();i++){JSONObject a=albums.optJSONObject(i);values.add(a.optLong("id"));labels.add(a.optString("name"));}
            } else {
                JSONArray keys=array(field,"choices");JSONObject names=field.optJSONObject("choiceLabels");
                for(int i=0;i<keys.length();i++){String k=keys.optString(i);values.add(k);labels.add(words.tr(names==null?k:names.optString(k,k)));}
            }
            if(labels.isEmpty()){emit(box,activity.getString(R.string.smart_no_album));return ()->JSONObject.NULL;}
            int selected=0;
            for(int i=0;i<values.size();i++)if(old.equals(String.valueOf(values.get(i))))selected=i;
            Spinner choice=spinner(labels,selected,activity.getString(R.string.smart_value));box.addView(choice);
            return ()->values.get(choice.getSelectedItemPosition());
        }
        if(op.equals("between")) {
            JSONArray oldPair=before instanceof JSONArray ? (JSONArray)before : new JSONArray();
            EditText a=input(activity.getString(R.string.smart_first),oldPair.optString(0),400),
                b=input(activity.getString(R.string.smart_second),oldPair.optString(1),400);
            box.addView(a);box.addView(b);
            return ()->new JSONArray().put(numberText(kind,a.getText().toString())).put(numberText(kind,b.getText().toString()));
        }
        if(op.equals("inLast")||op.equals("notInLast")) {
            JSONObject o=before instanceof JSONObject?(JSONObject)before:new JSONObject();
            String[] units={"hours","days","weeks","months","years"};
            List<String> names=List.of(activity.getString(R.string.smart_hours),activity.getString(R.string.smart_days),
                activity.getString(R.string.smart_weeks),activity.getString(R.string.smart_months),activity.getString(R.string.smart_years));
            EditText n=input(activity.getString(R.string.smart_value),o.optString("n",old.isEmpty()?"1":old),12);
            n.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);box.addView(n);
            Spinner unit=spinner(names,Math.max(0,List.of(units).indexOf(o.optString("unit","days"))),activity.getString(R.string.smart_unit));box.addView(unit);
            return ()->object("n",numberText("number",n.getText().toString()),"unit",units[unit.getSelectedItemPosition()]);
        }
        String hint=activity.getString("date".equals(kind)?R.string.smart_date_hint:R.string.smart_value);
        EditText input=input(hint,old,400);box.addView(input);
        return ()->numberText(kind,input.getText().toString());
    }

    private static String numberText(String kind,String value){
        String v=value.trim();
        return "number".equals(kind)?v.replace(',','.'):v;
    }

    private String issues(JSONArray list){
        StringBuilder out=new StringBuilder();
        for(int i=0;i<Math.min(6,list.length());i++){
            JSONObject row=list.optJSONObject(i);
            if(row==null)continue;
            if(out.length()>0)out.append('\n');
            JSONArray path=array(row,"path");
            for(int j=0;j<path.length();j++){if(j>0)out.append('.');out.append(path.optInt(j)+1);}
            out.append(" : ").append(words.tr(row.optString("message")));
        }
        return out.toString();
    }
    private String failure(Throwable e){
        String code=e instanceof LabEngine.Failure?((LabEngine.Failure)e).code:"";
        return activity.getString(switch(code){case "smart_name"->R.string.smart_name_missing;case "smart_limits"->R.string.smart_limits;default->R.string.smart_invalid;});
    }
    private String prettyOp(JSONObject field,String key){
        JSONObject o=lookup(array(field,"ops"),"op",key);
        return o==null?key:words.tr(o.optString("label"));
    }
    private void error(Throwable e){new AlertDialog.Builder(activity).setMessage(failure(e)).setPositiveButton(android.R.string.ok,null).show();}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout vertical(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView emit(LinearLayout box,String text){TextView v=new TextView(activity);v.setText(text);v.setPadding(0,dp(6),0,dp(6));box.addView(v);return v;}
    private Button button(String name,Runnable run){Button b=new Button(activity);b.setAllCaps(false);b.setText(name);b.setMinHeight(dp(48));b.setOnClickListener(v->run.run());return b;}
    private EditText input(String hint,String from,int limit) {
        EditText e=new EditText(activity);e.setHint(hint);e.setContentDescription(hint);e.setSingleLine(true);e.setText(from);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(limit)});e.setMinHeight(dp(48));return e;
    }
    private Spinner spinner(List<String> names,int selected,String label) {
        Spinner s=new Spinner(activity);ArrayAdapter<String> rows=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,names);
        rows.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(rows);s.setSelection(selected);
        s.setContentDescription(label);s.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(48)));return s;
    }
    private static void onChoice(Spinner spinner,Consumer<Integer> use) {
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> ignored){}
            public void onItemSelected(AdapterView<?> a,View v,int n,long id){use.accept(n);}
        });
    }
    private static JSONArray array(JSONObject o,String key){JSONArray a=o.optJSONArray(key);return a==null?new JSONArray():a;}
    private static JSONObject lookup(JSONArray a,String k,String v){
        for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&v.equals(o.optString(k)))return o;}return null;
    }
    private static JSONObject object(Object... p){JSONObject o=new JSONObject();for(int i=0;i<p.length;i+=2)put(o,(String)p[i],p[i+1]);return o;}
    private static void put(JSONObject o,String k,Object v){try{o.put(k,v);}catch(JSONException e){throw new IllegalArgumentException(e);}}
    private static void put(JSONArray a,int i,Object v){try{a.put(i,v);}catch(JSONException e){throw new IllegalArgumentException(e);}}
}

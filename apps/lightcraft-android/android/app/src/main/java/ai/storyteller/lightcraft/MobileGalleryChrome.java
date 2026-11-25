package ai.storyteller.lightcraft;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

/** Dense, image-first library grid. GridView continues to own long press, focus and click. */
final class MobileGalleryChrome {
    private static final int SURFACE=0xff15171b, TILE=0xff23262a, TEXT=0xfff5f7fa, SUB=0xffb7bdc6;

    private MobileGalleryChrome() {}

    static int columns(int widthDp) { return Math.max(2, Math.min(9, widthDp / 106)); }

    static void configure(GridView grid, int widthDp) {
        Context c=grid.getContext();
        grid.setNumColumns(columns(widthDp));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(c,2));
        grid.setVerticalSpacing(dp(c,2));
        grid.setClipToPadding(false);
        grid.setPadding(dp(c,2),dp(c,2),dp(c,2),dp(c,2));
        grid.setBackgroundColor(SURFACE);
    }

    static final class Tile extends FrameLayout {
        private final ImageView image;
        private final TextView empty, mark;
        private final Paint overlay=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check=new Path();
        private int tint;

        Tile(Context c) {
            super(c);
            setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            setBackgroundColor(TILE);
            // Leave click and keyboard handling to the enclosing grid; expose the full name.
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(view, info);
                    if (!(getParent() instanceof GridView grid) || !isEnabled()) return;
                    int position = grid.getPositionForView(view);
                    if (position == AdapterView.INVALID_POSITION) return;
                    int columns = Math.max(1, grid.getNumColumns());
                    info.setClassName(Button.class.getName());
                    info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(
                        position / columns, 1, position % columns, 1, false, isActivated()));
                    info.setSelected(isActivated());
                    info.setClickable(true);
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                    if(grid.getOnItemLongClickListener()!=null){
                        info.setLongClickable(true);
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
                    }
                }
                @Override public boolean performAccessibilityAction(View view, int action, Bundle args) {
                    if (getParent() instanceof GridView grid && isEnabled()) {
                        int position=grid.getPositionForView(view);
                        if(position!=AdapterView.INVALID_POSITION){
                            long id=grid.getItemIdAtPosition(position);
                            if(action==AccessibilityNodeInfo.ACTION_CLICK)return grid.performItemClick(view,position,id);
                            if(action==AccessibilityNodeInfo.ACTION_LONG_CLICK&&grid.getOnItemLongClickListener()!=null)
                                return grid.getOnItemLongClickListener().onItemLongClick(grid,view,position,id);
                        }
                    }
                    return super.performAccessibilityAction(view,action,args);
                }
            });
            empty=new TextView(c);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(SUB); empty.setTextSize(14);
            empty.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(empty,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
            image=new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(image,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
            mark=new TextView(c); mark.setGravity(Gravity.CENTER_VERTICAL);
            mark.setSingleLine(true); mark.setEllipsize(TextUtils.TruncateAt.END);
            mark.setTextColor(TEXT); mark.setTextSize(10);
            mark.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            mark.setIncludeFontPadding(false);
            mark.setPadding(dp(c,4),dp(c,3),dp(c,4),dp(c,3));
            GradientDrawable background=new GradientDrawable();
            background.setColor(0xd0090b0e);background.setCornerRadius(dp(c,3));
            mark.setBackground(background);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM|Gravity.START);
            badge.setMargins(dp(c,4),0,dp(c,4),dp(c,4));
            addView(mark,badge);
        }

        void bind(String display, String badge, int tagColor, boolean selected) {
            String title=display==null?"":display;
            String meta=badge==null?"":badge;
            empty.setText(title.isEmpty()?"·":new String(Character.toChars(title.codePointAt(0))));
            mark.setText(meta);mark.setVisibility(meta.isEmpty()?View.GONE:View.VISIBLE);
            // View tooltips intercept raw taps even on non-clickable cells. Use the full
            // accessibility name and let GridView own touch clicks, scrolling and long presses.
            setContentDescription(meta.isEmpty()?title:title+" · "+meta);
            // Grid itself owns item accessibility selection; don't rely on color alone.
            setSelected(selected);setActivated(selected);
            tint=tagColor;
            invalidate();
        }

        void bitmap(Bitmap bitmap) {
            image.setImageBitmap(bitmap);
            empty.setVisibility(bitmap==null?View.VISIBLE:View.INVISIBLE);
        }

        @Override protected void onMeasure(int w, int h) {
            int side=MeasureSpec.getSize(w);
            if(side>0)super.onMeasure(w,MeasureSpec.makeMeasureSpec(side,MeasureSpec.EXACTLY));
            else super.onMeasure(w,h);
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            float w=getWidth(),h=getHeight();
            if(w<=0||h<=0)return;
            boolean rtl=getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
            if(tint!=Color.TRANSPARENT) {
                float edge=dp(getContext(),8),x=rtl?w-edge:edge,y=edge;
                overlay.setStyle(Paint.Style.FILL);overlay.setColor(SURFACE);
                canvas.drawCircle(x,y,dp(getContext(),5),overlay);
                overlay.setColor(tint);
                canvas.drawCircle(x,y,dp(getContext(),3),overlay);
            }
            if(!isActivated())return;
            float line=Math.max(1,dp(getContext(),2)), radius=dp(getContext(),9);
            float x=rtl?dp(getContext(),14):w-dp(getContext(),14),y=dp(getContext(),14);
            overlay.setStrokeWidth(line);
            overlay.setStyle(Paint.Style.STROKE);overlay.setColor(TEXT);
            canvas.drawRect(line/2,line/2,w-line/2,h-line/2,overlay);
            overlay.setStyle(Paint.Style.FILL);overlay.setColor(TEXT);canvas.drawCircle(x,y,radius,overlay);
            overlay.setStyle(Paint.Style.STROKE);overlay.setColor(SURFACE);
            overlay.setStrokeCap(Paint.Cap.ROUND);overlay.setStrokeJoin(Paint.Join.ROUND);
            check.reset();check.moveTo(x-radius*.40f,y);check.lineTo(x-radius*.10f,y+radius*.28f);
            check.lineTo(x+radius*.43f,y-radius*.34f);canvas.drawPath(check,overlay);
        }
    }

    private static int dp(Context c,float px){return Math.round(px*c.getResources().getDisplayMetrics().density);}
}

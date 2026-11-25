package ai.storyteller.lightcraft;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.widget.ImageView;
import java.util.function.BiConsumer;

/** Tap on the actual fit-centered photo instead of mistaking the letterbox for image data. */
final class MobilePhotoSampler {
    static void show(Activity activity, Bitmap bitmap, BiConsumer<Float,Float> accepted) {
        ImageView image = new ImageView(activity);
        image.setImageBitmap(bitmap); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true); image.setContentDescription(activity.getString(R.string.mobile_sample_tip));
        int h = activity.getResources().getDisplayMetrics().heightPixels;
        image.setMaxHeight(Math.round(h*.65f));
        AlertDialog picker = new AlertDialog.Builder(activity).setTitle(R.string.mobile_sample_tip).setView(image)
            .setNegativeButton(android.R.string.cancel,null).create();
        image.setOnTouchListener((v,event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            image.performClick();
            Matrix inverse = new Matrix();
            if (!image.getImageMatrix().invert(inverse)) return true;
            float[] point = {event.getX()-image.getPaddingLeft(),event.getY()-image.getPaddingTop()};
            inverse.mapPoints(point);
            float x=point[0]/image.getDrawable().getIntrinsicWidth(), y=point[1]/image.getDrawable().getIntrinsicHeight();
            if (x>=0 && x<=1 && y>=0 && y<=1) { picker.dismiss(); accepted.accept(x,y); }
            return true;
        });
        picker.show();
    }
}

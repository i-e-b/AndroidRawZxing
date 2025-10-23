package com.ieb.zxingtest;


import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

public class AutoFitTextureView extends TextureView {
    private final String TAG = "CamViewTex";
    private int mRatioWidth = 0;
    private int mRatioHeight = 0;

    public AutoFitTextureView(Context context) {
        this(context, null);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
     * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
     * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     */
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        Log.i(TAG, "Aspect ratio updated");
        mRatioWidth = width;
        mRatioHeight = height;
        requestLayout();
    }



    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        int height = View.MeasureSpec.getSize(heightMeasureSpec);
        if (0 == mRatioWidth || 0 == mRatioHeight) {
            setMeasuredDimension(width, height);
        } else {
            if (width < height * mRatioWidth / mRatioHeight) {
                setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
            } else {
                setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
            }
        }
    }

    public void handleClick() {
        Bitmap bitmap = null;
        try {
            bitmap = getBitmap();
            if (bitmap == null){
                Log.w(TAG, "Failed to read texture bmp");
                return;
            }

            Log.w(TAG, "Captured: w="+bitmap.getWidth()+"; h="+bitmap.getHeight()+";");

            // Grab a random pixel for testing
            var pix = bitmap.getPixel(10, 10);
            var guess = (pix | (pix >> 8) | (pix >> 16)) & 0xFF;

            if (guess > 127) Log.w(TAG, "Image is bright?");
            else Log.w(TAG, "Image is dark?");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to get bitmap: " + t);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }
}

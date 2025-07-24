package com.ieb.zxingtest;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.VERTICAL;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/** @noinspection NullableProblems*/
public class Main extends Activity
{
    private final String TAG = "MainAct";
    private CameraController camControl;
    private AutoFitTextureView previewTexture;
    private TextView text;

    private volatile boolean updateInProgress;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        // Setup layout
        updateInProgress = false;
        var root = new LinearLayout(this);
        root.setOrientation(VERTICAL);
        setContentView(root);

        text = new TextView(this);
        previewTexture = new AutoFitTextureView(this);
        camControl = new CameraController(this, previewTexture, surfaceTexture -> updateReading());

        root.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, 0,1));
        root.addView(previewTexture, new LinearLayout.LayoutParams(MATCH_PARENT, 0,2));

        text.append("\r\nRunning test. Point camera at a QR code...");

        // Start camera feeding into the preview control
        camControl.onResume();
    }

    private void tryToFindQrCodeInBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            Log.w(TAG, "Invalid bitmap");
            return;
        }

        try {
            // Transfer to ZXing format
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            var pixels = new int[w*h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            var image = new RGBLuminanceSource(w,h, pixels);

            // Threshold down to a black&white image
            var thresholder = new HybridBinarizer(image);
            var binMap = new BinaryBitmap(thresholder);

            // Try to read the QR code image
            var reader = new QRCodeReader();
            var result = reader.decode(binMap);

            text.setText("\r\nFound QR code");
            text.append("\r\nBar code result: " + result.toString());
            text.append("\r\nBar code type: " + result.getBarcodeFormat().toString());

        } catch (com.google.zxing.NotFoundException nf) {
            // No QR code found. This is fine
        } catch (Exception e) {
            // If the capture is blurry or not at a good angle, we probably get a checksum error here.
            Log.w(TAG, "Could not read bar-code: "+e);
        }
    }

    /** Try to read a QR code from the current texture */
    private void updateReading() {
        if (updateInProgress) {
            return; // QR scanner is not keeping up with camera preview rate. Not really a problem.
        }
        updateInProgress = true;


        Bitmap bitmap = null;
        try {
            bitmap = previewTexture.getBitmap();
            if (bitmap == null){
                Log.w(TAG, "Failed to read texture bmp");
                return;
            }

            tryToFindQrCodeInBitmap(bitmap);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to get bitmap: " + t);
        } finally {
            if (bitmap != null) bitmap.recycle();
            updateInProgress = false;
        }
    }


    /** This is triggered if we had to request permission from the user */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Re-trigger camera control
        camControl.onResume();
    }

    /** pause camera control */
    @Override
    protected void onPause() {
        camControl.onPause();
        super.onPause();
    }

    /** resume camera control */
    @Override
    protected void onResume() {
        camControl.onResume();
        super.onResume();
    }
}
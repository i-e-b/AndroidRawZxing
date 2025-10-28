package com.ieb.zxingtest;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.VERTICAL;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.Binarizer;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

/**
 * @noinspection NullableProblems
 */
public class Main extends Activity {
    private final String TAG = "MainAct";
    private CameraFeedController camControl;
    private ImageView thresholdViewer;
    private ImageView luminanceViewer;
    private TextView text;

    private volatile boolean updateInProgress;
    private MultiFormatReader zxingReader;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        // Set up ZX-ing reader
        zxingReader = new MultiFormatReader();

        // Set up camera-to-bitmap feed
        camControl = new CameraFeedController(this, 1024, 768, 32, 256, this::updateReading);

        // Setup layout
        updateInProgress = false;
        var root = new LinearLayout(this);
        root.setOrientation(VERTICAL);
        setContentView(root);

        text = new TextView(this);

        luminanceViewer = new ImageView(this);
        thresholdViewer = new ImageView(this);

        root.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        root.addView(luminanceViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 3));
        root.addView(thresholdViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 3));

        text.append("\r\nRunning test. Point camera at a QR code or Code128 bar code...");

        // Start camera feeding into the preview control
        camControl.onResume();
    }

    @SuppressLint("SetTextI18n")
    private Result tryToFindBarCodeInBitmap(BinaryBitmap binMap) {
        if (binMap == null) {
            Log.w(TAG, "Invalid binMap");
            return null;
        }

        try {
            zxingReader.reset();
            var result = zxingReader.decode(binMap);


            runOnUiThread(() -> {
                if (testCycle) {
                    text.setText("\r\nFound Barcode (" + testScale + ", " + testExposure + ")");
                } else {
                    text.setText("\r\nFound Barcode (hybrid)");
                }
                text.append("\r\nBar code result: " + result.toString());
                text.append("\r\nBar code type: " + result.getBarcodeFormat().toString());
            });
            return result;
        } catch (com.google.zxing.NotFoundException nf) {
            // No code found. This is fine
        } catch (Exception e) {
            // If the capture is blurry or not at a good angle, we probably get a checksum error here.
            Log.w(TAG, "Could not read bar-code: " + e);
        }
        return null;
    }

    private static final int SCALE_MAX = 7; // 128-pixel spans
    private static final int SCALE_MIN = 3; // 8-pixel spans
    private static final int EXPOSURE_MAX = 10;
    private static final int EXPOSURE_MIN = -18;

    private static boolean testCycle = false;
    private static boolean invert = false;
    private static int testScale = SCALE_MAX;
    private static int testExposure = EXPOSURE_MAX;

    /**
     * Continually cycle through the various settings of HorizontalAverageBinarizer,
     * with a standard HybridBinarizer run between each one
     */
    private Binarizer pickThresholdParameters(LuminanceSource lum) {
        Binarizer thresholder;
        testCycle = !testCycle;

        if (testCycle) { // alternate between HAB and Zxing hybrid

            // Set the parameters beforehand, so the preview is accurate
            if (invert) { // try inverted image
                invert = false;
                testExposure -= 2; // cycle through exposure levels
                if (testExposure < EXPOSURE_MIN) { // when full exposure range has been tested...
                    testExposure = EXPOSURE_MAX; // ...reset...
                    testScale -= 1;              // ...and cycle the scale

                    if (testScale < SCALE_MIN) { // when scale has been cycled...
                        testScale = SCALE_MAX;   // ...reset
                    }
                }
            } else {
                invert = true;
            }

            // HAB thresholder
            if (invert) lum = lum.invert();
            thresholder = new UnsharpMaskBinarizer(lum, testScale, testExposure);

        } else { // Zxing Hybrid thresholder
            if (invert) lum = lum.invert();
            thresholder = new HybridBinarizer(lum);
        }
        return thresholder;
    }

    /**
     * Try to read a QR code from the current texture
     */
    private void updateReading(ByteImage image) {
        if (updateInProgress) {
            return; // QR scanner is not keeping up with camera preview rate. Not really a problem.
        }
        updateInProgress = true;

        try {
            updateVideoPreview(image.image, image.width, image.height);

            LuminanceSource lum = new PlanarYUVLuminanceSource(
                    image.image, image.width, image.height,
                    0, 0, image.width, image.height, false);

            // Rotate around a set of different image transforms.
            // Hopefully at least one of them will capture correctly.
            Binarizer thresholder = pickThresholdParameters(lum);

            // Convert greyscale to B&W
            var binMap = new BinaryBitmap(thresholder);

            // Scan for codes
            var result = tryToFindBarCodeInBitmap(binMap);
            //if (result != null) { // remove this 'if' for diagnostic view... but EPILEPSY WARNING!
                // Show a snap-shot of the thresholded image that worked
                updateThresholdPreview(binMap, result, invert);
            //}
        } catch (Throwable t) {
            Log.e(TAG, "Failed to scan image: " + t);
        } finally {
            updateInProgress = false;
        }
    }

    Bitmap prevLumBitmap = null;
    private static int[] lumTemp;

    /**
     * Show a greyscale preview of the camera luminance
     */
    private void updateVideoPreview(byte[] lumMap, int width, int height) {
        Bitmap.Config config = Bitmap.Config.ARGB_8888;

        if (lumTemp == null || lumTemp.length < lumMap.length) lumTemp = new int[lumMap.length];
        var colorInts = lumTemp;

        for (int i = 0; i < lumMap.length; i++) {
            var sample = lumMap[i] & 0xFF;
            var color = 0x00_01_01_01 * sample;
            colorInts[i] = 0xFF000000 + color;
        }

        prevLumBitmap = copyColorIntsToBitmap(prevLumBitmap, lumTemp, width, height, config);

        Bitmap finalBitmap = prevLumBitmap;
        runOnUiThread(() -> luminanceViewer.setImageBitmap(finalBitmap));
    }

    Bitmap threshBitmap = null;

    /**
     * Show a black&white preview of the barcode scanner thresholded map
     */
    private void updateThresholdPreview(BinaryBitmap binMap, Result result, boolean inverted) {
        Bitmap.Config config = Bitmap.Config.ARGB_8888;

        try {
            // Check result and size are valid
            if (binMap == null) return;
            var width = binMap.getWidth();
            var height = binMap.getHeight();
            if (width < 10 || height < 10) return;

            // get zone with bar code in it
            int minX = 0, maxX = 0;
            int minY = 0, maxY = 0;

            if (result != null) {
                minX = width;
                minY = height;
                var points = result.getResultPoints();
                for (com.google.zxing.ResultPoint point : points) {
                    int x = (int) point.getX(), y = (int) point.getY();
                    if (x > maxX) maxX = x;
                    if (x < minX) minX = x;
                    if (y > maxY) maxY = y;
                    if (y < minY) minY = y;
                }

                if (minY == maxY) {
                    // 2D code result are 1 pixel high, so we highlight around the match
                    minY -= 16;
                    maxY += 16;
                }
            }


            // render feedback image
            var pixels = binMap.getBlackMatrix().toColorInts(inverted, minX, maxX, minY, maxY);

            threshBitmap = copyColorIntsToBitmap(threshBitmap, pixels, width, height, config);

            // Draw on screen
            Bitmap finalBitmap = threshBitmap;
            runOnUiThread(() -> thresholdViewer.setImageBitmap(finalBitmap));
        } catch (NotFoundException e) {
            Log.e(TAG, "Failure in bin-map preview", e);
        }
    }

    /**
     * Copy "ColorInt" pixel to a bitmap. The bitmap is re-created if null or the wrong size
     */
    private Bitmap copyColorIntsToBitmap(Bitmap target, int[] pixels, int width, int height, Bitmap.Config config) {
        if (target == null) {
            var tmp = Bitmap.createBitmap(pixels, width, height, config);
            target = tmp.copy(Bitmap.Config.ARGB_8888, true);
            tmp.recycle();
        } else if (differentSize(target, width, height)) {
            target.recycle();
            var tmp = Bitmap.createBitmap(pixels, width, height, config);
            target = tmp.copy(Bitmap.Config.ARGB_8888, true);
            tmp.recycle();
        } else {
            target.setPixels(pixels, 0, width, 0, 0, width, height);
        }
        return target;
    }

    private boolean differentSize(Bitmap bitmap, int requiredWidth, int requiredHeight) {
        return bitmap.getWidth() != requiredWidth || bitmap.getHeight() != requiredHeight;
    }


    /**
     * This is triggered if we had to request permission from the user
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Re-trigger camera control
        camControl.onResume();
    }

    /**
     * pause camera control
     */
    @Override
    protected void onPause() {
        camControl.onPause();
        super.onPause();
    }

    /**
     * resume camera control
     */
    @Override
    protected void onResume() {
        camControl.onResume();
        super.onResume();
    }
}
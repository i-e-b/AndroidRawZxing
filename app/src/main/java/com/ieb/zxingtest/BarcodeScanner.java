package com.ieb.zxingtest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.util.Log;
import android.widget.ImageView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Binarizer;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.PresetListReader;
import com.google.zxing.Result;

import java.util.function.Consumer;

public class BarcodeScanner {
    private final String TAG = "BarcodeScanner";
    private final Activity act;

    private final CameraFeedController camControl;
    private final PresetListReader zxingReader;

    private Consumer<Result> resultCallback;
    private Consumer<String> errorMessageCallback;

    private ImageView previewOutput;
    private ImageView diagnosticOutput;
    private boolean constantDiagnostics = false;

    private volatile boolean updateInProgress;

    // Search range. Expand for slower but more extensive checks
    private static final int SCALE_MAX = 7; // 128-pixel spans
    private static final int SCALE_MIN = 4; // 16-pixel spans
    private static final int EXPOSURE_MAX = 12; // darkest exposure test (we assume codes are more likely to be faded than too dark)
    private static final int EXPOSURE_MIN = -4; // lightest exposure test

    public BarcodeScanner(Activity act) {
        this.act = act;

        // Set up ZX-ing reader
        zxingReader = new PresetListReader();
        zxingReader.setTryHarder(true);
        zxingReader.add(BarcodeFormat.QR_CODE);
        zxingReader.add(BarcodeFormat.CODE_128);
        zxingReader.add(BarcodeFormat.ITF);
        zxingReader.add(BarcodeFormat.DATA_MATRIX);

        // Set up camera-to-bitmap feed
        camControl = new CameraFeedController(act, 1024, 768, 32, 256, this::updateReading, this::onScannerError);
    }

    /** Start reading camera and scanning for barcodes */
    public void start() {
        camControl.onResume();
    }

    /** Pause reading camera */
    public void pause() {
        camControl.onPause();
    }

    /** (Optional) Add an image view that is updated for each captured frame */
    public void addPreview(ImageView imageView) {
        previewOutput = imageView;
    }

    /** (Optional) Add an image view that is updated with the internal thresholded view on capture */
    public void addDiagnosticView(ImageView thresholdViewer) {
        diagnosticOutput = thresholdViewer;
    }

    /** If set to `true`, the diagnostic view will be update with every captured frame.
     * If set to `false`, the diagnostic view is only updated when a barcode is captured.
     * Default is `false`*/
    public void setShowConstantDiagnostics(boolean setting) {
        constantDiagnostics = setting;
    }

    /** Set callback that is triggered when a barcode is detected */
    public void onCodeFound(Consumer<Result> codeFound) {
        resultCallback = codeFound;
    }

    /** Set a callback that is triggered when a camera fault is raised */
    public void onErrorMessage(Consumer<String> onError){
        errorMessageCallback = onError;
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
            if (result != null && resultCallback != null) resultCallback.accept(result);

            return result;
        } catch (com.google.zxing.NotFoundException nf) {
            // No code found. This is fine
        } catch (Exception e) {
            // If the capture is blurry or not at a good angle, we probably get a checksum error here.
            Log.w(TAG, "Could not read bar-code: " + e);
        }
        return null;
    }

    private static boolean invert = false;
    private static int testScale = SCALE_MAX;
    private static int testExposure = EXPOSURE_MAX;

    /**
     * Continually cycle through the various settings of UnsharpMaskBinarizer
     */
    private Binarizer pickThresholdParameters(LuminanceSource lum) {
        // Set the parameters beforehand, so the preview is accurate
        invert = !invert; // Alternate inverted and not
        if (invert) { // after trying inverted, change parameters
            testExposure -= 2; // cycle through exposure levels
            if (testExposure < EXPOSURE_MIN) { // when full exposure range has been tested...
                testExposure = EXPOSURE_MAX; // ...reset...
                testScale -= 1;              // ...and cycle the scale

                if (testScale < SCALE_MIN) { // when scale has been cycled...
                    testScale = SCALE_MAX;   // ...reset
                }
            }
        }

        // UMB thresholder
        return new UnsharpMaskBinarizer(lum, invert, testScale, testExposure);
    }

    private void onScannerError(String msg) {
        Log.w(TAG, msg);
        if (errorMessageCallback != null) errorMessageCallback.accept(msg);
    }

    /**
     * Try to read a QR code from the current texture
     */
    private void updateReading(ByteImage image) {
        if (updateInProgress) {
            Log.i(TAG, "Barcode scanner is running slower than camera");
            return; // QR scanner is not keeping up with camera preview rate. Not really a problem.
        }
        updateInProgress = true;

        try {
            if (previewOutput != null) updateVideoPreview(image.image, image.width, image.height);

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

            if (constantDiagnostics || result != null) {
                // Show a snap-shot of the thresholded image that worked
                if (diagnosticOutput != null) updateThresholdPreview(binMap, result, invert);
            }
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
        act.runOnUiThread(() -> previewOutput.setImageBitmap(finalBitmap));
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
            act.runOnUiThread(() -> diagnosticOutput.setImageBitmap(finalBitmap));
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
}

package com.ieb.zxingtest;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.VERTICAL;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

/** @noinspection NullableProblems*/
public class Main extends Activity
{
    private final String TAG = "MainAct";
    private CameraFeedController camControl;
    private ImageView thresholdViewer;
    private ImageView luminanceViewer;
    private TextView text;

    private boolean shouldInvert = false;
    private boolean useGlobal = false;

    private volatile boolean updateInProgress;
    private MultiFormatReader zxingReader;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        // Set up ZX-ing reader
        zxingReader = new MultiFormatReader();

        // Set up camera-to-bitmap feed
        camControl = new CameraFeedController(this, 1024, 768, 64, 256, this::updateReading);

        // Setup layout
        updateInProgress = false;
        var root = new LinearLayout(this);
        root.setOrientation(VERTICAL);
        setContentView(root);

        text = new TextView(this);

        luminanceViewer = new ImageView(this);
        thresholdViewer = new ImageView(this);

        root.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, 0,1));
        root.addView(luminanceViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0,3));
        root.addView(thresholdViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0,3));

        text.append("\r\nRunning test. Point camera at a QR code or Code128 bar code...");

        // Start camera feeding into the preview control
        camControl.onResume();
    }

    private Result tryToFindBarCodeInBitmap(BinaryBitmap binMap) {
        if (binMap == null) {
            Log.w(TAG, "Invalid binMap");
            return null;
        }

        try {
            zxingReader.reset();
            var result = zxingReader.decode(binMap);


            runOnUiThread(() -> {
                text.setText("\r\nFound Barcode");
                text.append("\r\nBar code result: " + result.toString());
                text.append("\r\nBar code type: " + result.getBarcodeFormat().toString());
            });
            return result;
        } catch (com.google.zxing.NotFoundException nf) {
            // No code found. This is fine
        } catch (Exception e) {
            // If the capture is blurry or not at a good angle, we probably get a checksum error here.
            Log.w(TAG, "Could not read bar-code: "+e);
        }
        return null;
    }

    /** try inverting the image on alternate frames. Zxing doesn't seem to find inverted QR codes. */
    private LuminanceSource maybeInvert(LuminanceSource source) {
        // Alternate between inverting and not. Helps with white-on-black codes
        shouldInvert = !shouldInvert;
        if (shouldInvert) return source.invert();
        return source;
    }

    private static int testCycles = 0;
    /** Try to read a QR code from the current texture */
    private void updateReading(ByteImage image) {
        if (updateInProgress) {
            return; // QR scanner is not keeping up with camera preview rate. Not really a problem.
        }
        updateInProgress = true;

        try {
            updateVideoPreview(image.image, image.width, image.height);

            if (testCycles > 2){
                thresholdHorz(image.image, image.width, image.height, 64);
            }
            testCycles++;
            if (testCycles > 4) {
                testCycles = 0;
                useGlobal = !useGlobal;
            }


            PlanarYUVLuminanceSource lum = new PlanarYUVLuminanceSource(
                    image.image, image.width, image.height,
                    0, 0, image.width, image.height, false);

            var binMap = convertToBinaryMap(lum, useGlobal);

            if (binMap == null) return;

            var result = tryToFindBarCodeInBitmap(binMap);
            if (result != null) {
                // Show a snap-shot of the thresholded image that worked
                updateThresholdPreview(binMap, result);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to scan image: " + t);
        } finally {
            updateInProgress = false;
        }
    }


    private BinaryBitmap convertToBinaryMap(LuminanceSource bitmap, boolean useGlobalThreshold) {
        if (bitmap == null) return null;

        try {
            // Alternate between inversions
            var image = maybeInvert(bitmap);

            // Threshold down to a black&white image
            var thresholder = (useGlobalThreshold) ? new GlobalHistogramBinarizer(image) : new HybridBinarizer(image);

            return new BinaryBitmap(thresholder);
        } catch (Exception e) {
            Log.w(TAG, "Could not read bitmap image: "+e);
        }
        return null;
    }

    private static byte[] thresholdTemp;
    /** threshold pixel based on a running average
     * @noinspection SameParameterValue*/
    private void thresholdHorz(byte[] image, int width, int height, int radius){
        int strength = 32;
        if (thresholdTemp == null || thresholdTemp.length < image.length) thresholdTemp = new byte[image.length];
        var tmp = thresholdTemp;

        // TODO: improve this so we don't lose the edges
        for (int y = radius; y < height - radius; y++) {
            int yOff = y * width;
            int sum = 0;
            int lead = yOff;
            int trail = yOff;
            int mid = yOff + (radius / 2);

            for (int i = 0; i < radius; i++) {
                sum += image[lead] & 0xFF;
                lead ++;
            }

            // decide running average
            for (int x = radius; x < width-radius; x++) {
                var actual = image[mid] & 0xFF;
                var target = sum / radius;

                // Push values away from centre point
                if (actual < target) tmp[mid] = pin(actual - strength);
                else tmp[mid] = pin(actual + strength);

                int incoming = image[lead] & 0xFF;
                int outgoing = image[trail] & 0xFF;

                sum += incoming - outgoing;

                lead++;
                trail++;
                mid++;
            }
        }

        System.arraycopy(tmp, 0, image, 0, image.length);
    }

    private byte pin(int v) {
        if (v < 0) return 0x00;
        if (v > 254) return (byte)0xFF;
        return (byte) v;
    }

    Bitmap prevLumBitmap = null;
    private static int[] lumTemp;

    /** Show a greyscale preview of the camera luminance */
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
        runOnUiThread(()-> luminanceViewer.setImageBitmap(finalBitmap));
    }

    Bitmap threshBitmap = null;
    /** Show a black&white preview of the barcode scanner thresholded map */
    private void updateThresholdPreview(BinaryBitmap binMap, Result result) {
        Bitmap.Config config = Bitmap.Config.ARGB_8888;

        try {
            // Check result and size are valid
            if (binMap == null || result == null) return;
            var width = binMap.getWidth();
            var height = binMap.getHeight();
            if (width < 10 || height < 10) return;

            // get zone with bar code in it
            var points = result.getResultPoints();
            int minX = width, maxX = 0;
            int minY = height, maxY = 0;
            for (com.google.zxing.ResultPoint point : points) {
                int x = (int) point.getX(), y = (int) point.getY();
                if (x > maxX) maxX = x; if (x < minX) minX = x;
                if (y > maxY) maxY = y; if (y < minY) minY = y;
            }

            if (minY == maxY) {
                // 2D code result are 1 pixel high, so we highlight around the match
                minY -= 16;
                maxY += 16;
            }


            // render feedback image
            var pixels = binMap.getBlackMatrix().toColorInts(shouldInvert, minX, maxX, minY, maxY);

            threshBitmap = copyColorIntsToBitmap(threshBitmap, pixels, width, height, config);

            // Draw on screen
            Bitmap finalBitmap = threshBitmap;
            runOnUiThread(() -> thresholdViewer.setImageBitmap(finalBitmap));
        } catch (NotFoundException e) {
            Log.e(TAG, "Failure in bin-map preview", e);
        }
    }

    /** Copy "ColorInt" pixel to a bitmap. The bitmap is re-created if null or the wrong size */
    private Bitmap copyColorIntsToBitmap(Bitmap target, int[] pixels, int width, int height, Bitmap.Config config) {
        if (target == null) {
            var tmp = Bitmap.createBitmap(pixels, width, height, config);
            target = tmp.copy(Bitmap.Config.ARGB_8888,true);
            tmp.recycle();
        } else if (differentSize(target, width, height)){
            target.recycle();
            var tmp = Bitmap.createBitmap(pixels, width, height, config);
            target = tmp.copy(Bitmap.Config.ARGB_8888,true);
            tmp.recycle();
        } else {
            target.setPixels(pixels, 0, width, 0, 0, width, height);
        }
        return target;
    }

    private boolean differentSize(Bitmap bitmap, int requiredWidth, int requiredHeight) {
        return bitmap.getWidth() != requiredWidth || bitmap.getHeight() != requiredHeight;
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
package com.ieb.zxingtest;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.VERTICAL;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
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
        camControl = new CameraFeedController(this, 1024, 768, this::updateReading);

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

    private boolean tryToFindBarCodeInBitmap(BinaryBitmap binMap) {
        if (binMap == null) {
            Log.w(TAG, "Invalid binMap");
            return false;
        }

        try {
            zxingReader.reset();
            var result = zxingReader.decode(binMap);

            runOnUiThread(() -> {
                text.setText("\r\nFound Barcode");
                text.append("\r\nBar code result: " + result.toString());
                text.append("\r\nBar code type: " + result.getBarcodeFormat().toString());
            });
            return true;
        } catch (com.google.zxing.NotFoundException nf) {
            // No QR code found. This is fine
        } catch (Exception e) {
            // If the capture is blurry or not at a good angle, we probably get a checksum error here.
            Log.w(TAG, "Could not read bar-code: "+e);
        }
        return false;
    }

    /** try inverting the image on alternate frames. Zxing doesn't seem to find inverted QR codes. */
    private LuminanceSource maybeInvert(LuminanceSource source) {
        // Alternate between inverting and not. Helps with white-on-black codes
        shouldInvert = !shouldInvert;
        if (shouldInvert) return source.invert();
        return source;
    }

    private static byte[] imageTmp;
    private static byte[] imageSrc;
    /** read luminance into a byte array, correcting for relative sensor rotation */
    private ByteImage imageToBytes(ImageReader reader, int insetX, int insetY) {
        try (Image image = reader.acquireNextImage()) {
        //try (Image image = reader.acquireLatestImage()) {
            if (image == null) return null;

            var planes = image.getPlanes();

            if (planes == null || planes.length < 1) {
                Log.w(TAG, "Invalid image planes");
                return null;
            }

            // Read plane into temp buffer
            var yPlane = planes[0];
            var buffer = yPlane.getBuffer();
            var bufferSize = buffer.remaining();
            if (imageTmp == null || imageTmp.length < bufferSize) imageTmp = new byte[bufferSize];
            var data = imageTmp;
            buffer.get(data);

            // Get parameters of the plane
            var srcWidth = image.getWidth();
            var srcHeight = image.getHeight();
            var dataWidth = yPlane.getRowStride();

            // Get parameter of output
            var rot = camControl.Orientation();
            var dstWidth = srcWidth;
            var dstHeight = srcHeight;
            if (rot == 90 || rot == 270){
                //noinspection SuspiciousNameCombination
                dstWidth = srcHeight;
                //noinspection SuspiciousNameCombination
                dstHeight = srcWidth;
            }
            dstWidth -= insetX * 2;
            dstHeight -= insetY * 2;

            // Ensure the final output is ready
            var requiredSize = srcWidth * srcHeight;
            if (imageSrc == null || imageSrc.length < requiredSize) {imageSrc = new byte[requiredSize];}
            var pkg = new ByteImage();
            pkg.image = imageSrc;
            pkg.width = dstWidth;
            pkg.height = dstHeight;

            // Copy from Y-plane into final image,
            // taking into account margins and rotations

            if (rot == 0){ // no flips
                int outp = 0;
                for (int y = 0; y < dstHeight; y++) {
                    var yOff = dataWidth * (y+ insetY);

                    // Copy a scan line
                    for (int x = 0; x < dstWidth; x++) {
                        var idx = yOff + x + insetX;
                        imageSrc[outp++] = data[idx];
                    }
                }
            } else if (rot == 180){ // Flip horz and vert
                int outp = 0;
                for (int y = dstHeight-1; y >= 0; y--) {
                    var yOff = dataWidth * (y+ insetY);

                    // Copy a scan line
                    for (int x = dstWidth - 1; x >= 0; x--) {
                        var idx = yOff + x + insetX;
                        imageSrc[outp++] = data[idx];
                    }
                }
            } else if (rot == 90) { // copy columns into rows, and flip y
                int outp = 0;
                for (int x = 0; x < dstHeight; x++) {

                    // Copy a scan line
                    for (int y = dstWidth-1; y >= 0; y--) {
                        var yy = y + insetX;
                        var yOff = dataWidth * (yy);
                        var idx = yOff + x + insetY;
                        imageSrc[outp++] = data[idx];
                    }
                }
            } else if (rot == 270) { // copy columns into rows
                int outp = 0;
                for (int x = 0; x < dstHeight; x++) {

                    // Copy a scan line
                    for (int y = 0; y < dstWidth; y++) {
                        var yOff = dataWidth * (y + insetX);
                        var idx = yOff + x + insetY;
                        imageSrc[outp++] = data[idx];
                    }
                }
            }

            return pkg;
        } catch (Exception e){
            Log.e(TAG, "Failed to read camera capture: " + e);
            return null;
        }
    }

    private static int testCycles = 0;
    /** Try to read a QR code from the current texture */
    private void updateReading(ImageReader reader) {
        if (updateInProgress) {
            return; // QR scanner is not keeping up with camera preview rate. Not really a problem.
        }
        updateInProgress = true;

        try {
            int insetX = 64;
            int insetY = 256;
            var image = imageToBytes(reader, insetX, insetY);
            if (image == null) return;

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

            if (tryToFindBarCodeInBitmap(binMap)) {
                // Show a snap-shot of the thresholded image that worked
                updateThresholdPreview(binMap);
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

                // Don't turn white areas into black lines or vice-versa:
                if (target > 224) target = 224;
                if (target < 32) target = 32;

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

        runOnUiThread(()-> {
            Bitmap bitmap = Bitmap.createBitmap(colorInts, width, height, config);
            luminanceViewer.setImageBitmap(bitmap);
            if (prevLumBitmap != null) prevLumBitmap.recycle();
            prevLumBitmap = bitmap;
        });
    }

    Bitmap prevThreshBitmap = null;
    /** Show a black&white preview of the barcode scanner thresholded map */
    private void updateThresholdPreview(BinaryBitmap binMap) {
        Bitmap.Config config = Bitmap.Config.RGB_565;
        Bitmap bitmap = null;
        try {
            // TODO: keep one bitmap and do setPixels instead.
            bitmap = Bitmap.createBitmap(binMap.getBlackMatrix().toColorInts(shouldInvert), binMap.getWidth(), binMap.getHeight(), config);
        } catch (NotFoundException e) {
            Log.e(TAG, "Failure in bin-map preview", e);
        }

        Bitmap finalBitmap = bitmap;
        runOnUiThread(() -> {
            thresholdViewer.setImageBitmap(finalBitmap);
            if (prevThreshBitmap != null) prevThreshBitmap.recycle();
            prevThreshBitmap = finalBitmap;
        });
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
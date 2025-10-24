package com.ieb.zxingtest;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.VERTICAL;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.Code128Reader;
import com.google.zxing.qrcode.QRCodeReader;

/** @noinspection NullableProblems*/
public class Main extends Activity
{
    private final String TAG = "MainAct";
    private CameraFeedController camControl;
    private TextureView cameraViewer;
    private ImageView thresholdViewer;
    private ImageView luminanceViewer;
    private TextView text;

    private boolean shouldInvert = false;

    private volatile boolean updateInProgress;
    private QRCodeReader qrReader;
    private Code128Reader code128Reader;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);


        qrReader = new QRCodeReader();
        code128Reader = new Code128Reader();

        // Setup layout
        updateInProgress = false;
        var root = new LinearLayout(this);
        root.setOrientation(VERTICAL);
        setContentView(root);

        text = new TextView(this);
        //cameraViewer = new AutoFitTextureView(this);
        //cameraViewer = new TextureView();

        //var test = new SurfaceTexture(true);
        //test.setDefaultBufferSize(CameraPreviewController.MAX_PREVIEW_WIDTH, CameraPreviewController.MAX_PREVIEW_HEIGHT);
        //cameraViewer.setSurfaceTexture(test);

        luminanceViewer = new ImageView(this);
        thresholdViewer = new ImageView(this);
        camControl = new CameraFeedController(this, this::updateReading);

        root.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, 0,1));
        //root.addView(cameraViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0,3));
        root.addView(luminanceViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0,3));
        root.addView(thresholdViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0,3));

        text.append("\r\nRunning test. Point camera at a QR code...");

        // Start camera feeding into the preview control
        camControl.onResume();
    }

    private void tryToFindQrCodeInBitmap(BinaryBitmap binMap) {
        if (binMap == null) {
            Log.w(TAG, "Invalid binMap");
            return;
        }

        try {
            qrReader.reset();
            var result = qrReader.decode(binMap);

            runOnUiThread(() -> {
                text.setText("\r\nFound QR code");
                text.append("\r\nBar code result: " + result.toString());
                text.append("\r\nBar code type: " + result.getBarcodeFormat().toString());
            });
        } catch (com.google.zxing.NotFoundException nf) {
            // No QR code found. This is fine
        } catch (Exception e) {
            // If the capture is blurry or not at a good angle, we probably get a checksum error here.
            Log.w(TAG, "Could not read bar-code: "+e);
        }
    }

    private void tryToFind128CodeInBitmap(BinaryBitmap binMap) {
        if (binMap == null) {
            Log.w(TAG, "Invalid binMap");
            return;
        }

        try {
            code128Reader.reset();
            var result = code128Reader.decode(binMap);

            runOnUiThread(() -> {
                text.setText("\r\nFound 128 code");
                text.append("\r\nBar code result: " + result.toString());
                text.append("\r\nBar code type: " + result.getBarcodeFormat().toString());
            });
        } catch (com.google.zxing.NotFoundException nf) {
            // No QR code found. This is fine
        } catch (Exception e) {
            // If the capture is blurry or not at a good angle, we probably get a checksum error here.
            Log.w(TAG, "Could not read bar-code: "+e);
        }
    }

    /** try inverting the image on alternate frames. Zxing doesn't seem to find inverted QR codes. */
    private LuminanceSource maybeInvert(LuminanceSource source) {
        /*shouldInvert = !shouldInvert;
        if (shouldInvert) return source.invert();*/
        return source;
    }

    /** read luminance into a byte array, correcting for relative sensor rotation */
    private byte[] imageToBytes(ImageReader reader) {
        //try (Image image = reader.acquireNextImage()) {
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) return null;

            var planes = image.getPlanes();

            if (planes == null || planes.length < 1) {
                Log.w(TAG, "Invalid image planes");
                return null;
            }

            var yPlane = planes[0];
            var buffer = yPlane.getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);

            var imgWidth = image.getWidth();
            var imgHeight = image.getHeight();
            var dataWidth = yPlane.getRowStride();

            var result = new byte[imgWidth * imgHeight];

            var rot = camControl.Orientation();
            int j = 0;
            if (rot == 90 || rot == 270){
                for (int x = 0; x < imgWidth; x++) {
                    for (int y = 0; y < imgHeight; y++) {
                        var yOff = dataWidth * y;
                        var idx = yOff + x;
                        result[j++] = data[idx];
                    }
                }
            } else {
                for (int y = 0; y < imgHeight; y++) {
                    var yOff = dataWidth * y;
                    for (int x = 0; x < imgWidth; x++) {
                        var idx = yOff + x;
                        result[j++] = data[idx];
                    }
                }
            }

            if (rot == 90){
                for (int y = 0; y < imgWidth; y++) {
                    var yOff = imgHeight * y;
                    var right = imgHeight - 1;
                    var left = 0;
                    while (left < right) {
                        var l = yOff + left;
                        var r = yOff + right;
                        var t = result[l];
                        result[l] = result[r];
                        result[r] = t;
                        left++;
                        right--;
                    }
                }
            }
            return result;
        }
    }

    /** Try to read a QR code from the current texture */
    private void updateReading(ImageReader reader) {
        if (updateInProgress) {
            return; // QR scanner is not keeping up with camera preview rate. Not really a problem.
        }
        updateInProgress = true;


        try {
            var imgHeight = reader.getHeight();
            var imgWidth = reader.getWidth();

            var rot = camControl.Orientation();
            if (rot == 90 || rot == 270) {
                imgHeight = reader.getWidth();
                imgWidth = reader.getHeight();
            }

            var image = imageToBytes(reader);
            if (image == null) return;

            //image = convolution3x3(image, imgWidth, imgHeight, conv_blur);
            //image = convolution3x3(image, imgWidth, imgHeight, conv_v_blur);
            //tryMaskingBarcodes(image, imgWidth, imgHeight);
            thresholdHorz(image, imgWidth, imgHeight);
            blurVert(image, imgWidth, imgHeight);

            // TODO: try a scan-line algorithm to whiten areas without much contrast
            // TODO: try enhancing contrast

            updateLumPreview(image, imgWidth, imgHeight);

            PlanarYUVLuminanceSource lum = new PlanarYUVLuminanceSource(image, imgWidth, imgHeight, 0, 0, imgWidth, imgHeight, false);

            var binMap = convertToBinaryMap(lum);

            if (binMap == null) return;

            updateThresholdPreview(binMap);

            tryToFindQrCodeInBitmap(binMap);
            tryToFind128CodeInBitmap(binMap);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to read camera capture: " + t);
        } finally {
            updateInProgress = false;
        }
    }


    private BinaryBitmap convertToBinaryMap(LuminanceSource bitmap) {
        if (bitmap == null) return null;

        try {
            // Transfer to ZXing format
            var image = maybeInvert(bitmap);

            // Threshold down to a black&white image
            var thresholder = new HybridBinarizer(image);
            //var thresholder = new GlobalHistogramBinarizer(image);

            return new BinaryBitmap(thresholder);
        } catch (Exception e) {
            Log.w(TAG, "Could not read bitmap image: "+e);
        }
        return null;
    }

    private static final int[] contrastMask = new int[1024];
    private static final int[] attentionMask = new int[1024];
    private void tryMaskingBarcodes(byte[] image, int width, int height) {
        int margin = 32;
        int threshold = 4;

        for (int y = margin; y < height - margin; y++) {
            var yOff = y * width;

            // build a contrast mask
            for (int x = margin; x < width-margin; x++) {
                var idx = yOff + x;

                var l3 = image[idx-3] & 0xFF;
                var l2 = image[idx-2] & 0xFF;
                var l1 = image[idx-1] & 0xFF;

                var c = image[idx] & 0xFF;

                var r1 = image[idx+1] & 0xFF;
                var r2 = image[idx+2] & 0xFF;
                var r3 = image[idx+3] & 0xFF;

                attentionMask[x] = 255;
                contrastMask[x] = (l1+c+c+r1) - (l2+l3+r2+r3);
            }

            // smear contrast mask with a size threshold
            for (int x = margin; x < width-margin; x++) {
                var n = 0;
                for (int i = 0; i < margin; i++) {
                    if (contrastMask[x + i] > 64) n++;
                }
                if (n > threshold) {
                    for (int i = 0; i < margin; i++) {
                        attentionMask[x - i] = 0;
                        attentionMask[x + i] = 0;
                    }
                }
            }

            // mask original image using the attention mask
            int min = 200;
            int max = 32;
            for (int x = margin; x < width-margin; x++) {
                var idx = yOff + x;
                var sample =((image[idx] & 0xFF) + attentionMask[x]); // Java byte slinging is a thing...
                image[idx] = pin(
                        (sample + attentionMask[x])
                );

                if (sample > 32 && sample < min) min = sample;
                if (sample < 200 && sample > max) max = sample;

            }
        }
    }

    /** Blur vertically only */
    private void blurVert(byte[] image, int width, int height) {
        final int margin = 4;
        var tmp = new byte[image.length];

        for (int x = margin; x < width - margin; x++) {
            int lead = x;
            int trail = x;
            int mid = x + (width * (margin/2));
            int sum = 0;

            for (int i = 0; i < margin; i++) {
                sum += image[lead] & 0xFF;
                lead += width;
            }

            for (int y = margin; y < height - margin; y++) {
                tmp[mid] = pin(sum / margin);

                int incoming = image[lead] & 0xFF;
                int outgoing = image[trail] & 0xFF;

                sum += incoming - outgoing;

                lead += width;
                trail += width;
                mid += width;
            }
        }

        System.arraycopy(tmp, 0, image, 0, image.length);
    }

    /** threshold pixel based on a running average */
    private void thresholdHorz(byte[] image, int width, int height){
        int margin = 16;
        var tmp = new byte[image.length];

        for (int y = margin; y < height - margin; y++) {
            int yOff = y * width;
            int sum = 0;
            int lead = yOff;
            int trail = yOff;
            int mid = yOff + (margin / 2);

            for (int i = 0; i < margin; i++) {
                sum += image[lead] & 0xFF;
                lead ++;
            }

            // decide running average
            for (int x = margin; x < width-margin; x++) {
                var actual = image[mid] & 0xFF;
                var target = sum / margin;

                // Don't turn white areas into black lines or vice-versa:
                if (target > 224) target = 224;
                if (target < 32) target = 32;

                if (actual < target) tmp[mid] = pin(actual - 64);
                else tmp[mid] = pin(actual + 64);

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

    private static final int[] conv_edge = {
            1,
            -1, -1, -1,
            -1,  8, -1,
            -1, -1, -1
    };
    private static final int[] conv_ridge = {
            1,
             0, -1,  0,
            -1,  4, -1,
             0, -1,  0
    };
    private static final int[] conv_sharpen = {
            1,
            -1, -2, -1,
            -2, 13, -2,
            -1, -2, -1
    };
    private static final int[] conv_blur= {
            16,
             1,  2,  1,
             2,  4, 2,
             1,  2,  1
    };
    private static final int[] conv_v_blur= {
            3,
            0,  1,  0,
            0,  1,  0,
            0,  1,  0
    };

    /** Experiments trying to improve capture */
    private byte[] convolution3x3(byte[] image, int width, int height, int[] factors) {
        var result = new byte[image.length];
        for (int y = 1; y < height-1; y++) {
            var yOff = y * width;
            for (int x = 1; x < width-1; x++) {
                var idx = yOff + x;

                var sample = 0;
                var dy = -width;
                var f = 1;
                for (int cy = 0; cy < 3; cy++) {
                    var dx = -1;
                    for (int cx = 0; cx < 3; cx++) {
                        sample += (image[idx + dy + dx] & 0xFF) * factors[f];
                        f++;
                    }
                    dy += width;
                }

                result[idx] = pin(sample / factors[0]);
            }
        }
        return result;
    }


    private byte pin(long v) {
        if (v < 0) return 0x00;
        if (v > 254) return (byte)0xFF;
        return (byte) v;
    }

    private byte pin(int v) {
        if (v < 0) return 0x00;
        if (v > 254) return (byte)0xFF;
        return (byte) v;
    }

    Bitmap prevLumBitmap = null;

    /** Show a greyscale preview of the camera luminance */
    private void updateLumPreview(byte[] lumMap, int width, int height) {
        Bitmap.Config config = Bitmap.Config.ARGB_8888;

        var colorInts = new int[lumMap.length];

        for (int i = 0; i < lumMap.length; i++) {
            var sample = lumMap[i] & 0xFF;
            var color = 0x00_01_01_01 * sample;
            colorInts[i] = 0xFF000000 + color;
        }

        Bitmap bitmap = Bitmap.createBitmap(colorInts, width, height, config);
        runOnUiThread(()-> {
            luminanceViewer.setImageBitmap(bitmap);
            if (prevLumBitmap != null) prevLumBitmap.recycle();
            prevLumBitmap = bitmap;
        });
    }

    private void updateLumPreview(int[] colorInts, int width, int height) {
        Bitmap.Config config = Bitmap.Config.ARGB_8888;

        Bitmap bitmap = Bitmap.createBitmap(colorInts, width, height, config);
        luminanceViewer.setImageBitmap(bitmap);

        if (prevLumBitmap != null) prevLumBitmap.recycle();
        prevLumBitmap = bitmap;
    }

    Bitmap prevThreshBitmap = null;
    /** Show a black&white preview of the barcode scanner thresholded map */
    private void updateThresholdPreview(BinaryBitmap binMap) {
        Bitmap.Config config = Bitmap.Config.RGB_565;
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(binMap.getBlackMatrix().toColorInts(), binMap.getWidth(), binMap.getHeight(), config);
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
package com.ieb.zxingtest;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.VERTICAL;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
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
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.Code128Reader;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;

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
        camControl = new CameraFeedController(this, reader -> updateReading(reader));

        root.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, 0,1));
        //root.addView(cameraViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0,3));
        root.addView(luminanceViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0,1));
        root.addView(thresholdViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0,1));

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

    /** Try to read a QR code from the current texture */
    private void updateReading(ImageReader reader) {
        if (updateInProgress) {
            return; // QR scanner is not keeping up with camera preview rate. Not really a problem.
        }
        updateInProgress = true;


        Image image = null;
        try {
            //image = reader.acquireLatestImage();
            image = reader.acquireNextImage();
            if (image == null) return;

            var planes = image.getPlanes();

            if (planes == null || planes.length < 3){
                Log.w(TAG, "Invalid image planes");
                return;
            }

            var yPlane = planes[0];
            var buffer = yPlane.getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);

            var imgWidth = image.getWidth();
            var imgHeight = image.getHeight();
            var dataWidth = yPlane.getRowStride();

            updateLumPreview(data, dataWidth, imgWidth, imgHeight);

            PlanarYUVLuminanceSource lum = new PlanarYUVLuminanceSource(data, dataWidth, imgHeight, 0, 0, imgWidth, imgHeight, false);

            var binMap = getBinMap(lum);

            if (binMap == null) return;

            updateThresholdPreview(binMap);

            tryToFindQrCodeInBitmap(binMap);
            tryToFind128CodeInBitmap(binMap);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to read camera capture: " + t);
        } finally {
            if (image != null) image.close();
            updateInProgress = false;
        }
    }

    Bitmap prevLumBitmap = null;

    /** Show a greyscale preview of the camera luminance */
    private void updateLumPreview(byte[] lumMap, int dataWidth, int width, int height) {
        Bitmap.Config config = Bitmap.Config.ARGB_8888;

        var colorInts = new int[lumMap.length];

        int j = 0;
        for (int y = 0; y < height; y++) {
            var yOff = dataWidth * y;
            for (int x = 0; x < width; x++) {
                var idx = yOff + x;
                var sample = lumMap[idx] & 0xFF;
                var color = 0x00_01_01_01 * sample;
                colorInts[j++] = 0xFF000000 + color;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(colorInts, width, height, config);
        luminanceViewer.setImageBitmap(bitmap);

        if (prevLumBitmap != null) prevLumBitmap.recycle();
        prevLumBitmap = bitmap;
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

        thresholdViewer.setImageBitmap(bitmap);

        if (prevThreshBitmap != null) prevThreshBitmap.recycle();
        prevThreshBitmap = bitmap;
    }

    /** Factor used to zoom in to image. This helps with badly printed codes */
    private double scaleFactor = 0.0;


    private BinaryBitmap getBinMap(Bitmap bitmap) {
        if (bitmap == null) return null;

        try {
            // Transfer to ZXing format
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();

            var pixels = new int[w*h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            var image = maybeInvert(new RGBLuminanceSource(w,h, pixels));

            // Threshold down to a black&white image
            var thresholder = new HybridBinarizer(image);
            //var thresholder = new GlobalHistogramBinarizer(image);

            return new BinaryBitmap(thresholder);
        } catch (Exception e) {
            Log.w(TAG, "Could not read bitmap image: "+e);
        }
        return null;
    }

    private BinaryBitmap getBinMap(LuminanceSource bitmap) {
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
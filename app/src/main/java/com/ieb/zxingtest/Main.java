package com.ieb.zxingtest;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.VERTICAL;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.Result;

/**
 * @noinspection NullableProblems
 */
public class Main extends Activity {
    private TextView text;

    private BarcodeScanner scanner;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        // Setup layout
        var root = new LinearLayout(this);
        root.setOrientation(VERTICAL);
        setContentView(root);

        text = new TextView(this);

        ImageView luminanceViewer = new ImageView(this);
        ImageView thresholdViewer = new ImageView(this);

        root.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        root.addView(luminanceViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 2));
        root.addView(thresholdViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 2));

        text.append("\r\nRunning test. Point camera at a QR code or Code128 bar code...");

        // Start camera feeding into the preview control
        scanner = new BarcodeScanner(this);
        scanner.onCodeFound(this::codeFound);
        scanner.onErrorMessage(this::cameraError);
        scanner.addPreview(luminanceViewer);
        scanner.showMatchBox(true);

        scanner.addDiagnosticView(thresholdViewer);
        scanner.setShowConstantDiagnostics(true);
        scanner.start();
    }

    /** Callback triggered if camera has a fault */
    private void cameraError(String msg) {
        runOnUiThread(() -> {
            text.setText("\r\nCamera fault: ");
            text.append(msg);
        });
    }

    /** Callback triggered when a barcode is detected */
    private void codeFound(Result result){
        scanner.pause(); // do this to stop scanning after a capture.
        // You could check other aspects of the code and decide if it is valid (Luhn codes etc)

        runOnUiThread(() -> {
            text.setText("\r\nFound Barcode");
            text.append("\r\nBar code result: " + result);
            text.append("\r\nBar code type: " + result.getBarcodeFormat().toString());
        });
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
        scanner.start();
    }

    /**
     * pause camera control
     */
    @Override
    protected void onPause() {
        scanner.pause();
        super.onPause();
    }

    /**
     * resume camera control
     */
    @Override
    protected void onResume() {
        scanner.start();
        super.onResume();
    }
}
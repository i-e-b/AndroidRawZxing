package com.ieb.zxingtest;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.widget.LinearLayout.VERTICAL;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.Result;

/**
 * @noinspection NullableProblems
 */
public class Main extends Activity {
    private TextView text;

    private BarcodeScanner scanner;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        scanner = new BarcodeScanner(this);

        // Setup layout
        var root = new ScrollView(this);
        setContentView(root);
        root.setFillViewport(true);

        var stack = new LinearLayout(this);
        stack.setOrientation(VERTICAL);
        root.addView(stack, new ViewGroup.MarginLayoutParams(MATCH_PARENT, MATCH_PARENT));

        text = new TextView(this);
        text.append("\r\nRunning test. Point camera at a QR code or Code128 bar code...");

        ImageView luminanceViewer = new ImageView(this);
        ImageView thresholdViewer = new ImageView(this);

        stack.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        stack.addView(luminanceViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 2));
        stack.addView(thresholdViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 2));




        var setYPlane = new Button(this);
        setYPlane.setText("Luminance");
        bindClick(setYPlane, v -> scanner.setCapturePlane(0), "Use luminance (normal)");
        stack.addView(setYPlane, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var setUPlane = new Button(this);
        setUPlane.setText("Blue/Yellow");
        bindClick(setUPlane, v -> scanner.setCapturePlane(1), "Use blue/yellow (for bad codes)");
        stack.addView(setUPlane, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var setVPlane = new Button(this);
        setVPlane.setText("Red/Green");
        bindClick(setVPlane, v -> scanner.setCapturePlane(2), "Use red/green (for bad codes)");
        stack.addView(setVPlane, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

       /* for (int i = 0; i < 20; i++) {
            var tmp1 = new TextView(this);
            tmp1.append("Test element "+i);
            stack.addView(tmp1, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }*/


        // Start camera feeding into the preview control
        scanner.onCodeFound(this::codeFound);
        scanner.onErrorMessage(this::cameraError);
        scanner.addPreview(luminanceViewer);
        scanner.showMatchBox(true);

        scanner.addDiagnosticView(thresholdViewer);
        scanner.setShowConstantDiagnostics(true);
        scanner.start();
    }


    private void bindClick(View thing, View.OnClickListener l, String helpMessage) {
        thing.setOnClickListener(l);

        if (helpMessage != null){
            bindHelpMsg(thing, helpMessage);
        }
    }

    /** Show a toast message with a brief description of the button. Shown when button is held down. */
    private void bindHelpMsg(View thing, String message) {
        thing.setOnLongClickListener(v ->
            {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                return true;
            }
        );
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
        //scanner.pause(); // do this to stop scanning after a capture.
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
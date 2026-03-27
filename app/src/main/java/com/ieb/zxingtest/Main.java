package com.ieb.zxingtest;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.widget.LinearLayout.VERTICAL;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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

        var stack = new LinearLayout(this);
        stack.setOrientation(VERTICAL);
        setContentView(stack);

        text = new TextView(this);
        text.append("\r\nRunning test. Point camera at a QR code or Code128 bar code...");

        ImageView luminanceViewer = new ImageView(this);
        ImageView thresholdViewer = new ImageView(this);

        stack.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        stack.addView(luminanceViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 3));
        stack.addView(thresholdViewer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 3));

        var ctrlScroll = new ScrollView(this);
        stack.addView(ctrlScroll, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 2));

        // Add 'advanced' controls
        var ctrlStack = new LinearLayout(this);
        ctrlStack.setOrientation(VERTICAL);
        ctrlScroll.addView(ctrlStack, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        addColorPlaneControls(ctrlStack);
        addExposureControl(ctrlStack);
        addThresholdScaleControl(ctrlStack);
        addMorphScaleControl(ctrlStack);
        addFourierScaleControl(ctrlStack);

        // TODO: 'Auto Mode' that scans through ranges of the exposure and scale controls


        // Start camera feeding into the preview control
        scanner.onCodeFound(this::codeFound);
        scanner.onErrorMessage(this::cameraError);
        scanner.addPreview(luminanceViewer);
        scanner.showMatchBox(true);

        scanner.addDiagnosticView(thresholdViewer);
        scanner.setShowConstantDiagnostics(true);
        scanner.start();
    }

    @SuppressLint("SetTextI18n")
    private void addColorPlaneControls(LinearLayout ctrlStack) {
        var setYPlane = new Button(this);
        setYPlane.setText("Luminance");
        bindClick(setYPlane, v -> scanner.setCapturePlane(0), "Use luminance (normal)");
        ctrlStack.addView(setYPlane, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var setUPlane = new Button(this);
        setUPlane.setText("Blue/Yellow");
        bindClick(setUPlane, v -> scanner.setCapturePlane(1), "Use blue/yellow (for bad codes)");
        ctrlStack.addView(setUPlane, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var setVPlane = new Button(this);
        setVPlane.setText("Red/Green");
        bindClick(setVPlane, v -> scanner.setCapturePlane(2), "Use red/green (for bad codes)");
        ctrlStack.addView(setVPlane, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    }

    @SuppressLint("SetTextI18n")
    private void addExposureControl(LinearLayout ctrlStack) {
        var label = new TextView(this);
        label.setText("Exposure");
        ctrlStack.addView(label, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var exposure = new SeekBar(this);
        exposure.setMin(-50);
        exposure.setMax(50);
        exposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                scanner.setExposure(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        ctrlStack.addView(exposure, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    }

    @SuppressLint("SetTextI18n")
    private void addThresholdScaleControl(LinearLayout ctrlStack) {
        var label = new TextView(this);
        label.setText("Threshold Scale");
        ctrlStack.addView(label, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var scale = new SeekBar(this);
        scale.setMin(2);
        scale.setMax(8);
        scale.setProgress(4);
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                scanner.setThresholdScale(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        ctrlStack.addView(scale, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    }

    @SuppressLint("SetTextI18n")
    private void addMorphScaleControl(LinearLayout ctrlStack) {
        var label = new TextView(this);
        label.setText("Morphological Closure Scale");
        ctrlStack.addView(label, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var scale = new SeekBar(this);
        scale.setMin(0);
        scale.setMax(4);
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                scanner.setMorphScale(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        ctrlStack.addView(scale, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    }

    @SuppressLint("SetTextI18n")
    private void addFourierScaleControl(LinearLayout ctrlStack) {
        var label = new TextView(this);
        label.setText("Fourier Scale");
        ctrlStack.addView(label, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        var scale = new SeekBar(this);
        scale.setMin(0);
        scale.setMax(32);
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                scanner.setFourierScale(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        ctrlStack.addView(scale, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
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
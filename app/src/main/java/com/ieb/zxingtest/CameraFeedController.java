package com.ieb.zxingtest;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A camera controller for live processing of a feed of images
 * @noinspection NullableProblems*/
public class CameraFeedController implements ImageReader.OnImageAvailableListener {
    private final Activity activity;
    private final int insetX;
    private final int insetY;
    ImageReader imageReader;

    /**
     * Set up camera control
     * @param main Hosting activity
     * @param captureWidth Width of image to capture (must be 1920 or less, must be supported by camera)
     * @param captureHeight Height of image to capture (must be 1080 or less, must be supported by camera)
     * @param insetX Area to crop on left and right of captured image before passing to updateTrigger.
     *               Must be less than half of captureWidth.
     * @param insetY Area to crop on top and bottom of captured image before passing to updateTrigger.
     *               Must be less than half of captureHeight
     * @param updateTrigger Trigger to call when a frame is captured
     */
    public CameraFeedController(Activity main,
                                int captureWidth, int captureHeight,
                                int insetX, int insetY,
                                Consumer<ByteImage> updateTrigger) {
        activity = main;
        if (captureWidth > 0 && captureWidth < 1920) CAPTURE_WIDTH = captureWidth;
        if (captureHeight > 0 && captureHeight < 1080) CAPTURE_HEIGHT = captureHeight;

        var maxInsetX = CAPTURE_WIDTH / 2;
        var maxInsetY = CAPTURE_HEIGHT / 2;

        this.insetX = insetX >= maxInsetX ? 0 : insetX;
        this.insetY = insetY >= maxInsetY ? 0 : insetY;

        this.updateTrigger = updateTrigger;
    }

    private static final int REQUEST_CAMERA_PERMISSION = 1;

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2BasicFragment";

    /**
     * Max preview width that is guaranteed by Camera2 API is 1920
     */
    public static int CAPTURE_WIDTH = 800;//1920;

    /**
     * Max preview height that is guaranteed by Camera2 API is 1080
     */
    public static int CAPTURE_HEIGHT = 600;//1080;

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * An  AutoFitTextureView for camera preview.
     */
    private final Consumer<ByteImage> updateTrigger;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;

            // TODO: feed back error info
        }

    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private CaptureRequest mPreviewRequest;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;

    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation;

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to capture.
     */
    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {}

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {}
    };

    /** Call this when hosting activity is resumed (or when starting camera control) */
    public void onResume() {
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        openCamera();
    }

    /** Call this when hosting activity is paused (or when ending camera control) */
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
    }

    private void requestCameraPermission() {
        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            //new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
            Log.e(TAG, "Need to show dialog?");
        } else {
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    /**
     * Sets up member variables related to camera.
     *
     */
    private void setUpCameraOutputs() {
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                //int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                //noinspection ConstantConditions
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available != null && available;

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Access denied when setting up camera outputs: e"+e);
        } catch (NullPointerException e) {
            Log.e(TAG, "Camera is not accessible: "+ e);
        }
    }

    /**
     * Opens the camera specified by mCameraId.
     */
    private void openCamera() {
        var permission = activity.checkSelfPermission(Manifest.permission.CAMERA);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }
        setUpCameraOutputs();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Access denied trying to open camera: "+e);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != imageReader) {
                imageReader.close();
                imageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            Log.w(TAG, "Failed to stop background thread"+e);
        }
    }



    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            if (imageReader == null) {
                // NOTE: ImageFormat.PRIVATE always works, but refuses to supply data.
                //       Docs claim that ImageFormat.JPEG always works, but this is not true.
                // ImageFormat.YUV_420_888 seems to be most reliable.
                imageReader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT, ImageFormat.YUV_420_888, 1);
                imageReader.setOnImageAvailableListener(this, null);
            }

            // This is the output Surface we need to start preview.
            Surface surface = imageReader.getSurface(); //new Surface(targetTexture);


            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(List.of(surface),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // Flash is automatically enabled when necessary.
                                setAutoFlash(mPreviewRequestBuilder);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Camera access denied during preview setup: "+e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {

                            // TODO: Fire off an error warning
                            //showToast("Failed (" + cameraCaptureSession.toString() + ")");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create camera preview session: "+e);
        }
    }

    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }


    private byte[] imageTmp;
    private byte[] imageSrc;
    /** read luminance into a byte array, correcting for relative sensor rotation */
    private ByteImage imageToBytes(ImageReader reader) {
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
            var rot = mSensorOrientation;
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

    private volatile boolean updateActive = false;

    @Override
    public void onImageAvailable(ImageReader reader) {
        if (updateActive) return; // consumer is not running fast enough

        try {
            updateActive = true;
            var image = imageToBytes(reader);
            if (image != null) updateTrigger.accept(image);
        } finally {
            updateActive = false;
        }
    }
}

/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.one.v2;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import com.android.camera.CaptureModuleUtil;
import com.android.camera.Exif;
import com.android.camera.app.MediaSaver.OnMediaSavedListener;
import com.android.camera.debug.DebugPropertyHelper;
import com.android.camera.debug.Log;
import com.android.camera.debug.Log.Tag;
import com.android.camera.exif.ExifInterface;
import com.android.camera.exif.ExifTag;
import com.android.camera.exif.Rational;
import com.android.camera.one.AbstractOneCamera;
import com.android.camera.one.OneCamera;
import com.android.camera.one.OneCamera.PhotoCaptureParameters.Flash;
import com.android.camera.session.CaptureSession;
import com.android.camera.util.CameraUtil;
import com.android.camera.util.CaptureDataSerializer;
import com.android.camera.util.JpegUtilNative;
import com.android.camera.util.Size;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * {@link OneCamera} implementation directly on top of the Camera2 API.
 */
public class OneCameraImpl extends AbstractOneCamera {

    /** Captures that are requested but haven't completed yet. */
    private static class InFlightCapture {
        final PhotoCaptureParameters parameters;
        final CaptureSession session;

        public InFlightCapture(PhotoCaptureParameters parameters,
                CaptureSession session) {
            this.parameters = parameters;
            this.session = session;
        }
    }

    private static final Tag TAG = new Tag("OneCameraImpl2");

    /** If true, will write data about each capture request to disk. */
    private static final boolean DEBUG_WRITE_CAPTURE_DATA = DebugPropertyHelper.writeCaptureData();
    /** If true, will log per-frame AF info. */
    private static final boolean DEBUG_FOCUS_LOG = DebugPropertyHelper.showFocusDebugLog();

    /** Default JPEG encoding quality. */
    private static final Byte JPEG_QUALITY = 90;

    /**
     * Set to ImageFormat.JPEG, to use the hardware encoder, or
     * ImageFormat.YUV_420_888 to use the software encoder. No other image
     * formats are supported.
     */
    private static final int sCaptureImageFormat = ImageFormat.YUV_420_888;

    /** Width and height of touch metering region as fraction of longest edge. */
    private static final float METERING_REGION_EDGE = 0.1f;
    /** Metering region weight between 0 and 1. */
    private static final float METERING_REGION_WEIGHT = 0.25f;
    /** Duration to hold after manual focus tap. */
    private static final int FOCUS_HOLD_MILLIS = 3000;
    /** Zero weight 3A region, to reset regions per API. */
    MeteringRectangle[] ZERO_WEIGHT_3A_REGION = new MeteringRectangle[]{
            new MeteringRectangle(0, 0, 1, 1, 0)
    };

    /**
     * CaptureRequest tags.
     * <ul>
     * <li>{@link #PRESHOT_TRIGGERED_AF}</li>
     * <li>{@link #CAPTURE}</li>
     * </ul>
     */
    public static enum RequestTag {
        /** Request that is part of a pre shot trigger. */
        PRESHOT_TRIGGERED_AF,
        /** Capture request (purely for logging). */
        CAPTURE,
        /** Tap to focus (purely for logging). */
        TAP_TO_FOCUS
    }

    /** Current CONTROL_AF_MODE request to Camera2 API. */
    private int mControlAFMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
    /** Last OneCamera.AutoFocusState reported. */
    private AutoFocusState mLastResultAFState = AutoFocusState.INACTIVE;
    /** Last OneCamera.AutoFocusMode reported. */
    private AutoFocusMode mLastResultAFMode = AutoFocusMode.CONTINUOUS_PICTURE;
    /** Flag to take a picture when in AUTO mode and the lens is stopped. */
    private boolean mTakePictureWhenLensStoppedAndAuto = false;
    /** Flag to take a picture when the lens is stopped. */
    private boolean mTakePictureWhenLensIsStopped = false;
    /** Takes a (delayed) picture with appropriate parameters. */
    private Runnable mTakePictureRunnable;
    /** Last time takePicture() was called in uptimeMillis. */
    private long mTakePictureStartMillis;
    /** Runnable that returns to CONTROL_AF_MODE = AF_CONTINUOUS_PICTURE. */
    private final Runnable mReturnToContinuousAFRunnable = new Runnable() {
        @Override
        public void run() {
            m3ARegions = ZERO_WEIGHT_3A_REGION;
            mControlAFMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            repeatingPreview(null);
        }
    };

    /** Current zoom value. 1.0 is no zoom. */
    private float mZoomValue = 1f;
    /** Current crop region: set from mZoomValue. */
    private Rect mCropRegion;
    /** Current AE and AF regions */
    private MeteringRectangle[] m3ARegions = ZERO_WEIGHT_3A_REGION;
    /** If partial results was OK, don't need to process total result. */
    private boolean mAutoFocusStateListenerPartialOK = false;

    /**
     * Common listener for preview frame metadata.
     */
    private final CameraCaptureSession.CaptureListener mAutoFocusStateListener = new
            CameraCaptureSession.CaptureListener() {
                // AF state information is sometimes available 1 frame before
                // onCaptureCompleted(), so we take advantage of that.
                @Override
                public void onCaptureProgressed(CameraCaptureSession session,
                        CaptureRequest request,
                        CaptureResult partialResult) {

                    if (partialResult.get(CaptureResult.CONTROL_AF_STATE) != null) {
                        mAutoFocusStateListenerPartialOK = true;
                        autofocusStateChangeDispatcher(partialResult);
                        if (DEBUG_FOCUS_LOG) {
                            //AutoFocusHelper.logExtraFocusInfo(partialResult);
                        }
                    } else {
                        mAutoFocusStateListenerPartialOK = false;
                    }
                    super.onCaptureProgressed(session, request, partialResult);
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                        CaptureRequest request,
                        TotalCaptureResult result) {
                    if (!mAutoFocusStateListenerPartialOK) {
                        autofocusStateChangeDispatcher(result);
                    }
                    if (DEBUG_FOCUS_LOG) {
                        AutoFocusHelper.logExtraFocusInfo(result);
                    }
                    super.onCaptureCompleted(session, request, result);
                }
            };
    /** Thread on which the camera operations are running. */
    private final HandlerThread mCameraThread;
    /** Handler of the {@link #mCameraThread}. */
    private final Handler mCameraHandler;
    /** The characteristics of this camera. */
    private final CameraCharacteristics mCharacteristics;
    /** The underlying Camera2 API camera device. */
    private final CameraDevice mDevice;

    /**
     * The aspect ratio (width/height) of the full resolution for this camera.
     * Usually the native aspect ratio of this camera.
     */
    private final double mFullSizeAspectRatio;
    /** The Camera2 API capture session currently active. */
    private CameraCaptureSession mCaptureSession;
    /** The surface onto which to render the preview. */
    private Surface mPreviewSurface;
    /**
     * A queue of capture requests that have been requested but are not done
     * yet.
     */
    private final LinkedList<InFlightCapture> mCaptureQueue =
            new LinkedList<InFlightCapture>();
    /** Whether closing of this device has been requested. */
    private volatile boolean mIsClosed = false;
    /** A callback that is called when the device is fully closed. */
    private CloseCallback mCloseCallback = null;

    /** Receives the normal captured images. */
    private final ImageReader mCaptureImageReader;
    ImageReader.OnImageAvailableListener mCaptureImageListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    InFlightCapture capture = mCaptureQueue.remove();

                    // Since this is not an HDR+ session, we will just save the
                    // result.
                    capture.session.startEmpty();
                    byte[] imageBytes = acquireJpegBytesAndClose(reader);
                    // TODO: The savePicture call here seems to block UI thread.
                    savePicture(imageBytes, capture.parameters, capture.session);
                    broadcastReadyState(true);
                    capture.parameters.callback.onPictureTaken(capture.session);
                }
            };

    /**
     * Instantiates a new camera based on Camera 2 API.
     *
     * @param device The underlying Camera 2 device.
     * @param characteristics The device's characteristics.
     * @param pictureSize the size of the final image to be taken.
     */
    OneCameraImpl(CameraDevice device, CameraCharacteristics characteristics, Size pictureSize) {
        mDevice = device;
        mCharacteristics = characteristics;
        mFullSizeAspectRatio = calculateFullSizeAspectRatio(characteristics);

        mCameraThread = new HandlerThread("OneCamera2");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());

        mCaptureImageReader = ImageReader.newInstance(pictureSize.getWidth(),
                pictureSize.getHeight(),
                sCaptureImageFormat, 2);
        mCaptureImageReader.setOnImageAvailableListener(mCaptureImageListener, mCameraHandler);
        Log.d(TAG, "New Camera2 based OneCameraImpl created.");
    }

    /**
     * Take picture, initiating an auto focus scan if needed.
     */
    @Override
    public void takePicture(final PhotoCaptureParameters params, final CaptureSession session) {
        if (mTakePictureWhenLensStoppedAndAuto || mTakePictureWhenLensIsStopped) {
            // Do not do anything when a picture is already in progress
            // that is waiting for a 3A to finish.
            return;
        }

        // Wait until the picture comes back.
        broadcastReadyState(false);

        mTakePictureRunnable = new Runnable() {
            @Override
            public void run() {
                takePictureNow(params, session);
            }
        };
        mTakePictureStartMillis = SystemClock.uptimeMillis();

        // TODO: First, check to see if we need to run pre-capture (flash).

        if (false /*a picture is available from the ZSL ring buffer*/) {

            // Process the ZSL picture and return that.
            // TODO: Insert ZSL code here.

        } else if (mLastResultAFMode == AutoFocusMode.CONTINUOUS_PICTURE
                && mLastResultAFState == AutoFocusState.STOPPED_UNFOCUSED) {
            // If in CONTINUOUS_PICTURE + unfocused, trigger an AF scan then
            // take a picture.
            Log.v(TAG, "Unfocused: Triggering auto focus scan.");
            mTakePictureWhenLensStoppedAndAuto = true;
            m3ARegions = ZERO_WEIGHT_3A_REGION;
            sendAutoFocusTriggerCaptureRequest(RequestTag.PRESHOT_TRIGGERED_AF);
        } else if (mLastResultAFState == AutoFocusState.SCANNING) {
            // If scanning, wait until the scan is done, then take the picture.
            Log.v(TAG, "Waiting until scan is done before taking shot.");
            mTakePictureWhenLensIsStopped = true;
        } else {
            // TODO: Run CONTROL_AF_TRIGGER_START and wait until lens locks.
            takePictureNow(params, session);
        }
    }

    /**
     * Take picture immediately. Parameters passed through from takePicture().
     */
    public void takePictureNow(PhotoCaptureParameters params, CaptureSession session) {
        long dt = SystemClock.uptimeMillis() - mTakePictureStartMillis;
        Log.v(TAG, "Taking shot with extra AF delay of " + dt + " ms.");
        // This will throw a RuntimeException, if parameters are not sane.
        params.checkSanity();
        try {
            // JPEG capture.
            CaptureRequest.Builder builder = mDevice
                    .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.setTag(RequestTag.CAPTURE);
            addBaselineCaptureKeysToRequest(builder);

            if (sCaptureImageFormat == ImageFormat.JPEG) {
                builder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY);
                builder.set(CaptureRequest.JPEG_ORIENTATION,
                        CameraUtil.getJpegRotation(params.orientation, mCharacteristics));
            }

            builder.addTarget(mPreviewSurface);
            builder.addTarget(mCaptureImageReader.getSurface());
            // TODO: Fix this.
            applyFlashMode(params.flashMode, builder);
            CaptureRequest request = builder.build();

            if (DEBUG_WRITE_CAPTURE_DATA) {
                final String debugDataDir = makeDebugDir(params.debugDataFolder,
                        "normal_capture_debug");
                Log.i(TAG, "Writing capture data to: " + debugDataDir);
                CaptureDataSerializer.toFile("Normal Capture", request, new File(debugDataDir,
                        "capture.txt"));
            }

            mCaptureSession.capture(request, mAutoFocusStateListener, mCameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Could not access camera for still image capture.");
            broadcastReadyState(true);
            params.callback.onPictureTakenFailed();
            return;
        }
        mCaptureQueue.add(new InFlightCapture(params, session));
    }

    @Override
    public void startPreview(Surface previewSurface, CaptureReadyCallback listener) {
        mPreviewSurface = previewSurface;
        setupAsync(mPreviewSurface, listener);
    }

    @Override
    public void setViewFinderSize(int width, int height) {
        throw new RuntimeException("Not implemented yet.");
    }

    @Override
    public boolean isFlashSupported(boolean enhanced) {
        throw new RuntimeException("Not implemented yet.");
    }

    @Override
    public boolean isSupportingEnhancedMode() {
        throw new RuntimeException("Not implemented yet.");
    }

    @Override
    public void close(CloseCallback closeCallback) {
        if (mIsClosed) {
            Log.w(TAG, "Camera is already closed.");
            return;
        }
        try {
            mCaptureSession.abortCaptures();
        } catch (CameraAccessException e) {
            Log.e(TAG, "Could not abort captures in progress.");
        }
        mIsClosed = true;
        mCloseCallback = closeCallback;
        mCameraThread.quitSafely();
        mDevice.close();
    }

    @Override
    public Size[] getSupportedSizes() {
        StreamConfigurationMap config = mCharacteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        return Size.convert(config.getOutputSizes(sCaptureImageFormat));
    }

    @Override
    public double getFullSizeAspectRatio() {
        return mFullSizeAspectRatio;
    }

    @Override
    public boolean isFrontFacing() {
        return mCharacteristics.get(CameraCharacteristics.LENS_FACING)
                == CameraMetadata.LENS_FACING_FRONT;
    }

    @Override
    public boolean isBackFacing() {
        return mCharacteristics.get(CameraCharacteristics.LENS_FACING)
                == CameraMetadata.LENS_FACING_BACK;
    }

    private void savePicture(byte[] jpegData, final PhotoCaptureParameters captureParams,
            CaptureSession session) {
        int heading = captureParams.heading;
        int width = 0;
        int height = 0;
        int rotation = 0;
        ExifInterface exif = null;
        try {
            exif = new ExifInterface();
            exif.readExif(jpegData);

            Integer w = exif.getTagIntValue(ExifInterface.TAG_PIXEL_X_DIMENSION);
            width = (w == null) ? width : w;
            Integer h = exif.getTagIntValue(ExifInterface.TAG_PIXEL_Y_DIMENSION);
            height = (h == null) ? height : h;

            // Get image rotation from EXIF.
            rotation = Exif.getOrientation(exif);

            // Set GPS heading direction based on sensor, if location is on.
            if (heading >= 0) {
                ExifTag directionRefTag = exif.buildTag(
                        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
                        ExifInterface.GpsTrackRef.MAGNETIC_DIRECTION);
                ExifTag directionTag = exif.buildTag(
                        ExifInterface.TAG_GPS_IMG_DIRECTION,
                        new Rational(heading, 1));
                exif.setTag(directionRefTag);
                exif.setTag(directionTag);
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read exif from gcam jpeg", e);
            exif = null;
        }
        session.saveAndFinish(jpegData, width, height, rotation, exif, new OnMediaSavedListener() {
            @Override
            public void onMediaSaved(Uri uri) {
                captureParams.callback.onPictureSaved(uri);
            }
        });
    }

    /**
     * Asynchronously sets up the capture session.
     *
     * @param previewSurface the surface onto which the preview should be
     *            rendered.
     * @param listener called when setup is completed.
     */
    private void setupAsync(final Surface previewSurface, final CaptureReadyCallback listener) {
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                setup(previewSurface, listener);
            }
        });
    }

    /**
     * Configures and attempts to create a capture session.
     *
     * @param previewSurface the surface onto which the preview should be
     *            rendered.
     * @param listener called when the setup is completed.
     */
    private void setup(Surface previewSurface, final CaptureReadyCallback listener) {
        try {
            if (mCaptureSession != null) {
                mCaptureSession.abortCaptures();
                mCaptureSession = null;
            }
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(previewSurface);
            outputSurfaces.add(mCaptureImageReader.getSurface());

            mDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateListener() {

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    listener.onSetupFailed();
                }

                @Override
                public void onConfigured(CameraCaptureSession session) {
                    mCaptureSession = session;
                    m3ARegions = ZERO_WEIGHT_3A_REGION;
                    mZoomValue = 1f;
                    mCropRegion = cropRegionForZoom(mZoomValue);
                    boolean success = repeatingPreview(null);
                    if (success) {
                        listener.onReadyForCapture();
                    } else {
                        listener.onSetupFailed();
                    }
                }

                @Override
                public void onClosed(CameraCaptureSession session) {
                    super.onClosed(session);
                    if (mCloseCallback != null) {
                        mCloseCallback.onCameraClosed();
                    }
                }
            }, mCameraHandler);
        } catch (CameraAccessException ex) {
            Log.e(TAG, "Could not set up capture session", ex);
            listener.onSetupFailed();
        }
    }

    /**
     * Adds current regions to CaptureRequest and base AF mode + AF_TRIGGER_IDLE.
     *
     * @param builder Build for the CaptureRequest
     */
    private void addBaselineCaptureKeysToRequest(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, m3ARegions);
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, m3ARegions);
        builder.set(CaptureRequest.SCALER_CROP_REGION, mCropRegion);
        builder.set(CaptureRequest.CONTROL_AF_MODE, mControlAFMode);
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
    }

    /**
     * Request preview capture stream with AF_MODE_CONTINUOUS_PICTURE.
     *
     * @return true if request was build and sent successfully.
     * @param tag
     */
    private boolean repeatingPreview(Object tag) {
        try {
            CaptureRequest.Builder builder = mDevice.
                    createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(mPreviewSurface);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            addBaselineCaptureKeysToRequest(builder);
            mCaptureSession.setRepeatingRequest(builder.build(), mAutoFocusStateListener,
                    mCameraHandler);
            Log.v(TAG, String.format("Sent repeating Preview request, zoom = %.2f", mZoomValue));
            return true;
        } catch (CameraAccessException ex) {
            Log.e(TAG, "Could not access camera setting up preview.", ex);
            return false;
        }
    }

    /**
     * Request preview capture stream with auto focus trigger cycle.
     */
    private void sendAutoFocusTriggerCaptureRequest(Object tag) {
        try {
            // Step 1: Request single frame CONTROL_AF_TRIGGER_START.
            CaptureRequest.Builder builder;
            builder = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(mPreviewSurface);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            mControlAFMode = CameraMetadata.CONTROL_AF_MODE_AUTO;
            addBaselineCaptureKeysToRequest(builder);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            builder.setTag(tag);
            mCaptureSession.capture(builder.build(), mAutoFocusStateListener, mCameraHandler);

            // Step 2: Call repeatingPreview to update mControlAFMode.
            repeatingPreview(tag);
            resumeContinuousAFAfterDelay(FOCUS_HOLD_MILLIS);
        } catch (CameraAccessException ex) {
            Log.e(TAG, "Could not execute preview request.", ex);
        }
    }

    /**
     * Resume AF_MODE_CONTINUOUS_PICTURE after FOCUS_HOLD_MILLIS.
     */
    private void resumeContinuousAFAfterDelay(int millis) {
        mCameraHandler.removeCallbacks(mReturnToContinuousAFRunnable);
        mCameraHandler.postDelayed(mReturnToContinuousAFRunnable, millis);
    }

    /**
     * This method takes appropriate action if camera2 AF state changes.
     * <ol>
     * <li>Reports changes in camera2 AF state to OneCamera.FocusStateListener.</li>
     * <li>Take picture after AF scan.</li>
     * <li>TODO: Take picture after AE_PRECAPTURE sequence for flash.</li>
     * </ol>
     */
    private void autofocusStateChangeDispatcher(CaptureResult result) {
        Integer nativeAFControlState = result.get(CaptureResult.CONTROL_AF_STATE);
        Integer nativeAFControlMode = result.get(CaptureResult.CONTROL_AF_MODE);
        Object tag = result.getRequest().getTag();

        // Convert to OneCamera mode and state.
        AutoFocusMode resultAFMode = AutoFocusHelper.modeFromCamera2Mode(nativeAFControlMode);
        AutoFocusState resultAFState = AutoFocusHelper.stateFromCamera2State(nativeAFControlState);

        boolean lensIsStopped = (resultAFState == AutoFocusState.STOPPED_FOCUSED ||
                resultAFState == AutoFocusState.STOPPED_UNFOCUSED);
        if (tag == RequestTag.PRESHOT_TRIGGERED_AF && lensIsStopped &&
                mTakePictureWhenLensStoppedAndAuto) {
            // Take the shot.
            mCameraHandler.post(mTakePictureRunnable);
            // Return to passive scanning.
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    m3ARegions = ZERO_WEIGHT_3A_REGION;
                    mControlAFMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
                    repeatingPreview(null);
                }
            });
            mTakePictureWhenLensStoppedAndAuto = false;
        }
        if (mTakePictureWhenLensIsStopped && lensIsStopped) {
            // Take the shot.
            mCameraHandler.post(mTakePictureRunnable);
            mTakePictureWhenLensIsStopped = false;
        }

        // Report state change when mode or state has changed.
        if (resultAFState != mLastResultAFState || resultAFMode != mLastResultAFMode
                && mFocusStateListener != null) {
            mFocusStateListener.onFocusStatusUpdate(resultAFMode, resultAFState);
        }
        mLastResultAFState = resultAFState;
        mLastResultAFMode = resultAFMode;
    }

    @Override
    public void triggerAutoFocus() {
        Log.v(TAG, "triggerAutoFocus()");
        m3ARegions = ZERO_WEIGHT_3A_REGION;
        sendAutoFocusTriggerCaptureRequest(null);
    }

    @Override
    public void triggerFocusAndMeterAtPoint(float nx, float ny) {
        Log.v(TAG, "triggerFocusAndMeterAtPoint(" + nx + "," + ny + ")");
        float points[] = new float[] {
                nx, ny
        };
        // Make sure the points are in [0,1] range.
        points[0] = CameraUtil.clamp(points[0], 0f, 1f);
        points[1] = CameraUtil.clamp(points[1], 0f, 1f);

        // Shrink points towards center if zoomed.
        if (mZoomValue > 1f) {
            Matrix zoomMatrix = new Matrix();
            zoomMatrix.postScale(1f / mZoomValue, 1f / mZoomValue, 0.5f, 0.5f);
            zoomMatrix.mapPoints(points);
        }

        // TODO: Make this work when preview aspect ratio != sensor aspect
        // ratio.
        Rect sensor = mCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        int edge = (int) (METERING_REGION_EDGE * Math.max(sensor.width(), sensor.height()));
        // x0 and y0 in sensor coordinate system, rotated 90 degrees from
        // portrait.
        int x0 = (int) (sensor.width() * points[1]);
        int y0 = (int) (sensor.height() * (1f - points[0]));
        int x1 = x0 + edge;
        int y1 = y0 + edge;

        // Make sure regions are inside the sensor area.
        x0 = CameraUtil.clamp(x0, 0, sensor.width() - 1);
        x1 = CameraUtil.clamp(x1, 0, sensor.width() - 1);
        y0 = CameraUtil.clamp(y0, 0, sensor.height() - 1);
        y1 = CameraUtil.clamp(y1, 0, sensor.height() - 1);
        int wt = (int) ((1 - METERING_REGION_WEIGHT) * MeteringRectangle.METERING_WEIGHT_MIN
                + METERING_REGION_WEIGHT * MeteringRectangle.METERING_WEIGHT_MAX);

        Log.v(TAG, "sensor 3A @ x0=" + x0 + " y0=" + y0 + " dx=" + (x1 - x0) + " dy=" + (y1 - y0));
        m3ARegions = new MeteringRectangle[]{new MeteringRectangle(x0, y0, x1 - x0, y1 - y0, wt)};
        sendAutoFocusTriggerCaptureRequest(RequestTag.TAP_TO_FOCUS);
    }

    @Override
    public float getMaxZoom() {
        return mCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
    }

    @Override
    public void setZoom(float zoom) {
        mZoomValue = zoom;
        mCropRegion = cropRegionForZoom(zoom);
        repeatingPreview(null);
    }

    @Override
    public Size pickPreviewSize(Size pictureSize, Context context) {
        float pictureAspectRatio = pictureSize.getWidth() / (float) pictureSize.getHeight();
        return CaptureModuleUtil.getOptimalPreviewSize(context, getSupportedSizes(),
                pictureAspectRatio);
    }

    private Rect cropRegionForZoom(float zoom) {
        Rect sensor = mCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        float zoomWidth = sensor.width() / zoom;
        float zoomHeight = sensor.height() / zoom;
        float zoomLeft = (sensor.width() - zoomWidth) / 2;
        float zoomTop = (sensor.height() - zoomHeight) / 2;
        return new Rect((int) zoomLeft, (int) zoomTop, (int) (zoomLeft + zoomWidth),
                (int) (zoomTop + zoomHeight));
    }

    /**
     * Calculate the aspect ratio of the full size capture on this device.
     *
     * @param characteristics the characteristics of the camera device.
     * @return The aspect ration, in terms of width/height of the full capture
     *         size.
     */
    private static double calculateFullSizeAspectRatio(CameraCharacteristics characteristics) {
        Rect activeArraySize =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        return (double) activeArraySize.width() / activeArraySize.height();
    }

    /**
     * Given an image reader, extracts the JPEG image bytes and then closes the
     * reader.
     *
     * @param reader the reader to read the JPEG data from.
     * @return The bytes of the JPEG image. Newly allocated.
     */
    private static byte[] acquireJpegBytesAndClose(ImageReader reader) {
        Image img = reader.acquireLatestImage();

        ByteBuffer buffer;

        if (img.getFormat() == ImageFormat.JPEG) {
            Image.Plane plane0 = img.getPlanes()[0];
            buffer = plane0.getBuffer();
        } else if (img.getFormat() == ImageFormat.YUV_420_888) {
            buffer = ByteBuffer.allocateDirect(img.getWidth() * img.getHeight() * 3);

            Log.v(TAG, "Compressing JPEG with software encoder.");
            int numBytes = JpegUtilNative.compressJpegFromYUV420Image(img, buffer, JPEG_QUALITY);

            if (numBytes < 0) {
                throw new RuntimeException("Error compressing jpeg.");
            }

            buffer.limit(numBytes);
        } else {
            throw new RuntimeException("Unsupported image format.");
        }

        byte[] imageBytes = new byte[buffer.remaining()];
        buffer.get(imageBytes);
        buffer.rewind();
        img.close();
        return imageBytes;
    }

    private void applyFlashMode(Flash flashMode, CaptureRequest.Builder requestBuilder) {
        switch (flashMode) {
            case ON:
                Log.d(TAG, "Flash mode ON");
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
                break;
            case OFF:
                Log.d(TAG, "Flash mode OFF");
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON);
                requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
            case AUTO:
            default:
                Log.d(TAG, "Flash mode AUTO");
                requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                break;
        }
    }
}
